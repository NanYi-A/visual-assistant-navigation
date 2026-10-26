"""Conservatively align an interrupted RGB-D scan segment to its parent model.

The tool never merges data.  It writes a reviewable transform and only sets
mergeAllowed when independent image pairs agree and their depth surfaces overlap.
"""
import argparse, json, math, os, sys
from pathlib import Path
import cv2
import numpy as np
from scipy.spatial import cKDTree

CV_FROM_AR=np.diag([1.,-1.,-1.,1.])

class LearnedMatcher:
    """ALIKED + LightGlue matcher; ALIKED has permissive pretrained-weight licensing."""
    def __init__(self):
        lightglue=Path(os.environ.get('LIGHTGLUE_PATH', 'models/LightGlue'))
        if not lightglue.exists():raise RuntimeError(f'LightGlue source missing: {lightglue}')
        sys.path.insert(0,str(lightglue))
        import torch
        from lightglue import ALIKED,LightGlue
        from lightglue.utils import numpy_image_to_torch,rbd
        self.torch=torch;self.rbd=rbd;self.to_tensor=numpy_image_to_torch
        requested=os.environ.get('VA_RELOCALIZATION_DEVICE','auto').lower()
        self.device=torch.device('cpu' if requested=='cpu' or not torch.cuda.is_available() else 'cuda')
        if self.device.type=='cpu':torch.set_num_threads(max(1,min(4,os.cpu_count() or 1)))
        self.extractor=ALIKED(max_num_keypoints=2048).eval().to(self.device)
        self.matcher=LightGlue(features='aliked',filter_threshold=.10).eval().to(self.device)
        self.cache={}

    def features(self,frame):
        key=str(frame['path'])
        if key not in self.cache:
            image=self.to_tensor(np.ascontiguousarray(frame['rgb'])).to(self.device)
            self.cache[key]=self.extractor.extract(image,resize=1024)
        return self.cache[key]

    def match(self,parent,child):
        with self.torch.inference_mode():
            p=self.features(parent);c=self.features(child)
            result=self.matcher({'image0':p,'image1':c})
            pr,cr,mr=[self.rbd(x) for x in (p,c,result)]
            matches=mr['matches'].detach().cpu().numpy()
            if not len(matches):return np.empty((0,2)),np.empty((0,2))
            return (pr['keypoints'][matches[:,0]].detach().cpu().numpy(),
                cr['keypoints'][matches[:,1]].detach().cpu().numpy())

_learned_matcher=None
def learned_matcher():
    global _learned_matcher
    if _learned_matcher is None:_learned_matcher=LearnedMatcher()
    return _learned_matcher

def load_frames(folder):
    frames=[]
    # Merged versions prefix filenames with their source model to avoid frame-id
    # collisions. Every file in this dedicated folder is a validated RGB-D sample.
    for path in sorted(Path(folder).glob('*.npz')):
        with np.load(path) as data:
            frames.append({'path':path,'rgb':data['rgb'].copy(),'depth':data['depth_mm'].copy(),
                'confidence':data['confidence'].copy(),'k':data['intrinsics'].astype(float).copy(),
                'pose':data['camera_to_world'].astype(float).copy()})
    return frames

def camera_matrix(k):
    fx,fy,cx,cy=k;return np.array([[fx,0,cx],[0,fy,cy],[0,0,1]],dtype=np.float64)

def world_points(frame,step=4):
    depth=frame['depth'];conf=frame['confidence'];ys,xs=np.mgrid[0:depth.shape[0]:step,0:depth.shape[1]:step]
    z=depth[ys,xs].astype(float)/1000.;valid=(z>.15)&(z<6.)&(conf[ys,xs]>=128);xs=xs[valid];ys=ys[valid];z=z[valid]
    if not len(z):return np.empty((0,3))
    fx,fy,cx,cy=frame['k'];cv=np.column_stack(((xs-cx)*z/fx,(ys-cy)*z/fy,z,np.ones(len(z))))
    return (frame['pose']@CV_FROM_AR@cv.T).T[:,:3]

def estimate_pair(parent,child):
    parent_points,child_points=learned_matcher().match(parent,child)
    obj=[];img=[];fx,fy,cx,cy=parent['k'];world_from_parent_cv=parent['pose']@CV_FROM_AR
    for parent_point,child_point in zip(parent_points,child_points):
        u,v=parent_point;x=int(round(float(u)));y=int(round(float(v)))
        if not(0<=x<parent['depth'].shape[1] and 0<=y<parent['depth'].shape[0]):continue
        z=float(parent['depth'][y,x])/1000.
        if not(.15<z<6.) or parent['confidence'][y,x]<128:continue
        p=world_from_parent_cv@np.array([(u-cx)*z/fx,(v-cy)*z/fy,z,1.]);obj.append(p[:3]);img.append(child_point)
    if len(obj)<8:return None
    ok,rvec,tvec,inliers=cv2.solvePnPRansac(np.asarray(obj,np.float32),np.asarray(img,np.float32),camera_matrix(child['k']),None,
        flags=cv2.SOLVEPNP_EPNP,reprojectionError=2.5,confidence=.999,iterationsCount=150)
    if not ok or inliers is None or len(inliers)<7:return None
    indices=inliers[:,0];projected,_=cv2.projectPoints(np.asarray(obj,np.float32)[indices],rvec,tvec,camera_matrix(child['k']),None)
    rmse=float(np.sqrt(np.mean(np.sum((projected[:,0]-np.asarray(img)[indices])**2,axis=1))))
    rotation,_=cv2.Rodrigues(rvec);child_cv_from_parent=np.eye(4);child_cv_from_parent[:3,:3]=rotation;child_cv_from_parent[:3,3]=tvec[:,0]
    parent_from_child_world=np.linalg.inv(child_cv_from_parent)@np.linalg.inv(child['pose']@CV_FROM_AR)
    return {'transform':parent_from_child_world,'inliers':len(indices),'correspondences':len(obj),'rmse':rmse,
        'parent':parent['path'].name,'child':child['path'].name}

def rotation_degrees(a,b):
    relative=a[:3,:3]@b[:3,:3].T;return math.degrees(math.acos(float(np.clip((np.trace(relative)-1)/2,-1,1))))

def consensus(candidates,translation_limit=.30,rotation_limit=12.):
    if not candidates:return []
    best=[]
    for seed in candidates:
        group=[c for c in candidates if np.linalg.norm(c['transform'][:3,3]-seed['transform'][:3,3])<=translation_limit and rotation_degrees(c['transform'],seed['transform'])<=rotation_limit]
        distinct_parent=len({c['parent'] for c in group});distinct_child=len({c['child'] for c in group})
        if distinct_parent>=2 and distinct_child>=2 and sum(c['inliers'] for c in group)>sum(c['inliers'] for c in best):best=group
    return best

def geometry_overlap(parent_frames,child_frames,transform):
    parent=np.concatenate([world_points(f) for f in parent_frames]) if parent_frames else np.empty((0,3))
    child=np.concatenate([world_points(f) for f in child_frames]) if child_frames else np.empty((0,3))
    if len(parent)<100 or len(child)<100:return {'samples':0,'overlapFraction':0.,'medianDistanceMeters':None}
    child=(transform@np.column_stack((child,np.ones(len(child)))).T).T[:,:3]
    distances=cKDTree(parent).query(child,k=1,workers=-1)[0];near=distances[distances<.30]
    return {'samples':int(len(distances)),'overlapFraction':float(np.mean(distances<.12)),
        'medianDistanceMeters':float(np.median(near)) if len(near) else None}

def alignment_allowed(agreed,inliers,geometry):
    median=geometry.get('medianDistanceMeters');overlap=geometry.get('overlapFraction',0.)
    if median is None:return False
    # Ordinary evidence keeps the original strict geometry threshold. A substantially
    # stronger learned-feature consensus may tolerate ARCore/TSDF surface noise up to
    # 15 cm, but only when many independent pairs and a large overlap agree.
    strict=len(agreed)>=2 and inliers>=20 and overlap>=.08 and median<=.10
    strong=len(agreed)>=4 and inliers>=100 and overlap>=.15 and median<=.15
    # Dense multi-frame agreement is substantially harder to obtain by chance.  Give
    # this case a small amount of extra tolerance for ARCore raw-depth surface noise;
    # do not extend the relaxed limit to short or weak scans.
    dense=len(agreed)>=10 and inliers>=300 and overlap>=.30 and median<=.18
    return strict or strong or dense

def shortlist_pairs(parent_frames,child_frames,limit=5):
    """Use fast ORB retrieval to bound learned pair matching for each live frame."""
    def descriptors(frame):
        gray=cv2.cvtColor(frame['rgb'],cv2.COLOR_RGB2GRAY)
        return cv2.ORB_create(nfeatures=900,scaleFactor=1.2,nlevels=6).detectAndCompute(gray,None)[1]
    parent_descriptors=[descriptors(frame) for frame in parent_frames]
    child_descriptors=[descriptors(frame) for frame in child_frames]
    matcher=cv2.BFMatcher(cv2.NORM_HAMMING)
    shortlisted=[]
    for child_index,child_desc in enumerate(child_descriptors):
        scores=[]
        for parent_index,parent_desc in enumerate(parent_descriptors):
            score=0
            if child_desc is not None and parent_desc is not None and len(child_desc)>=2 and len(parent_desc)>=2:
                matches=matcher.knnMatch(child_desc,parent_desc,k=2)
                score=sum(1 for pair in matches if len(pair)==2 and pair[0].distance<.78*pair[1].distance and pair[0].distance<64)
            scores.append((score,parent_index))
        # Ties are broken by the parent index, not by the order the tuples happen to
        # compare in.  Two frames with the same score must always produce the same
        # shortlist, otherwise the same recording is verified differently each run.
        ranked=sorted(scores,key=lambda item:(-item[0],item[1]))
        # ORB is only a cheap retrieval hint, not a localization decision. Camera
        # exposure, motion blur, and viewpoint changes can push every score below
        # the old hard cutoff, which silently prevented ALIKED/LightGlue from ever
        # checking the pair. Always let the learned matcher inspect a small fallback
        # set; PnP consensus and RGB-D overlap remain the actual acceptance gates.
        eligible=[(score,index) for score,index in ranked if score>=6]
        selected=[index for _,index in (eligible[:limit] if eligible else ranked[:min(2,limit,len(parent_frames))])]
        shortlisted.extend((parent_frames[index],child_frames[child_index]) for index in selected)
    return shortlisted

def run(models,segment_id):
    segment_dir=Path(models)/str(segment_id);meta=json.loads((segment_dir/'segment.json').read_text(encoding='utf-8'));parent_id=meta['parentModelId']
    parent_dir=Path(models)/parent_id;manifest_path=parent_dir/'manifest.json'
    manifest=json.loads(manifest_path.read_text(encoding='utf-8')) if manifest_path.exists() else {}
    parent=load_frames(parent_dir/manifest.get('activeKeyframesPath','semantic_samples'));child=load_frames(segment_dir/'semantic_samples')
    # Expensive learned matching is only run for visually plausible parent frames.
    shortlisted=shortlist_pairs(parent,child)
    candidates=[]
    for pf,cf in shortlisted:
        result=estimate_pair(pf,cf)
        if result:candidates.append(result)
    agreed=consensus(candidates);transform=None
    if agreed:
        transform=max(agreed,key=lambda c:c['inliers'])['transform'];geometry=geometry_overlap(parent,child,transform)
    else:geometry={'samples':0,'overlapFraction':0.,'medianDistanceMeters':None}
    inliers=sum(c['inliers'] for c in agreed);allowed=alignment_allowed(agreed,inliers,geometry)
    status='ALIGNED_REVIEW_REQUIRED' if allowed else 'NEEDS_OVERLAP_RESCAN'
    report={'schemaVersion':2,'matcher':'ALIKED+LightGlue','parentModelId':parent_id,'segmentId':str(segment_id),'status':status,'mergeAllowed':allowed,
        'candidatePairCount':len(candidates),'shortlistedPairCount':len(shortlisted),'consistentPairCount':len(agreed),'totalInliers':inliers,'geometry':geometry,
        'transformParentFromSegment':transform.tolist() if transform is not None else None,
        'pairs':[{k:v for k,v in c.items() if k!='transform'} for c in agreed]}
    (segment_dir/'relocalization.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    meta.update({'status':status,'mergeAllowed':allowed,'relocalization':'relocalization.json'});(segment_dir/'segment.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--models',type=Path,required=True);parser.add_argument('--segment',required=True);args=parser.parse_args()
    print(json.dumps(run(args.models,args.segment),ensure_ascii=False,indent=2))
