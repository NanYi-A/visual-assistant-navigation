"""Create an immutable, rollback-safe parent+segment model version.

Only a segment whose relocalization report explicitly has mergeAllowed=true is
accepted. The original parent and child files are never overwritten.
"""
import argparse, json, shutil, time, uuid
from pathlib import Path
import numpy as np
import open3d as o3d

def read_json(path, default=None):
    return json.loads(Path(path).read_text(encoding='utf-8')) if Path(path).is_file() else default

def atomic_json(path, value):
    path=Path(path);tmp=path.with_name(path.name+'.tmp')
    tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8');tmp.replace(path)

def transform_pose(pose, transform):
    point=transform@np.array([pose['x'],pose['y'],pose['z'],1.],float)
    direction=transform[:3,:3]@np.array([pose.get('forwardX',0.),pose.get('forwardY',0.),pose.get('forwardZ',-1.)],float)
    out=dict(pose);out.update(x=float(point[0]),y=float(point[1]),z=float(point[2]),forwardX=float(direction[0]),forwardY=float(direction[1]),forwardZ=float(direction[2]));return out

def merged_places(parent_dir, child_dir, transform):
    parent=read_json(parent_dir/'semantics'/'places.json',{'places':[]}) or {'places':[]}
    child=read_json(child_dir/'semantics'/'places.json',{'places':[]}) or {'places':[]}
    places=list(parent.get('places',[]));ids={p.get('placeId') for p in places}
    for place in child.get('places',[]):
        item=dict(place);item['anchorPoseMap']=transform_pose(item['anchorPoseMap'],transform)
        if item.get('placeId') in ids:item['placeId']=str(uuid.uuid4())
        item['sourceSegmentId']=child_dir.name;places.append(item);ids.add(item.get('placeId'))
    return {'schemaVersion':1,'modelId':parent_dir.name,'places':places}

def copy_samples(source, destination, transform=None, prefix='p'):
    destination.mkdir(parents=True,exist_ok=True);count=0
    for path in sorted(source.glob('frame_*.npz')):
        with np.load(path) as data:values={key:data[key].copy() for key in data.files}
        if transform is not None:values['camera_to_world']=(transform@values['camera_to_world'].astype(float)).astype(np.float32)
        np.savez_compressed(destination/f'{prefix}_{path.name}',**values);count+=1
    return count

def merge(models, segment_id, simplify_triangles=100000):
    models=Path(models).resolve();child=models/str(segment_id)
    segment=read_json(child/'segment.json');report=read_json(child/'relocalization.json')
    if not segment or not report:raise ValueError('segment metadata or relocalization report missing')
    if not report.get('mergeAllowed') or report.get('status')!='ALIGNED_REVIEW_REQUIRED':raise ValueError('segment did not pass conservative relocalization checks')
    transform=np.asarray(report.get('transformParentFromSegment'),dtype=float)
    if transform.shape!=(4,4) or not np.isfinite(transform).all():raise ValueError('invalid parent-from-segment transform')
    parent=models/str(segment['parentModelId']);parent_mesh_path=parent/'scan'/'colored_mesh.ply';child_mesh_path=child/'scan'/'colored_mesh.ply'
    if not parent_mesh_path.is_file() or not child_mesh_path.is_file():raise ValueError('parent or segment colored mesh missing')
    version_name=f'merge_{child.name}';versions=parent/'versions';destination=versions/version_name
    if destination.exists():return read_json(destination/'merge_report.json')
    temporary=versions/f'.{version_name}.tmp';shutil.rmtree(temporary,ignore_errors=True);(temporary/'scan').mkdir(parents=True);(temporary/'semantics').mkdir();(temporary/'semantic_samples').mkdir()
    parent_mesh=o3d.io.read_triangle_mesh(str(parent_mesh_path));child_mesh=o3d.io.read_triangle_mesh(str(child_mesh_path));child_mesh.transform(transform)
    before_parent=len(parent_mesh.triangles);before_child=len(child_mesh.triangles);combined=parent_mesh+child_mesh
    combined.remove_degenerate_triangles();combined.remove_duplicated_triangles();combined.remove_duplicated_vertices();combined.remove_non_manifold_edges();combined.merge_close_vertices(.008)
    if len(combined.triangles)>simplify_triangles:combined=combined.simplify_quadric_decimation(simplify_triangles)
    combined.compute_vertex_normals();mesh_target=temporary/'scan'/'colored_mesh.ply'
    if not o3d.io.write_triangle_mesh(str(mesh_target),combined,write_ascii=False,compressed=True,write_vertex_normals=True,write_vertex_colors=True):raise RuntimeError('failed to write merged mesh')
    places=merged_places(parent,child,transform);atomic_json(temporary/'semantics'/'places.json',places)
    parent_manifest=read_json(parent/'manifest.json',{}) or {};active_samples=parent_manifest.get('activeKeyframesPath','semantic_samples')
    parent_count=copy_samples(parent/active_samples,temporary/'semantic_samples',prefix='p');child_count=copy_samples(child/'semantic_samples',temporary/'semantic_samples',transform,prefix=f's{child.name}')
    result={'schemaVersion':1,'status':'MERGED_REVIEW_REQUIRED','parentModelId':parent.name,'segmentId':child.name,'version':version_name,
        'createdAt':time.time(),'transformParentFromSegment':transform.tolist(),'sourceRelocalization':'../../../'+child.name+'/relocalization.json',
        'parentTriangles':before_parent,'segmentTriangles':before_child,'mergedTriangles':len(combined.triangles),'parentKeyframes':parent_count,'segmentKeyframes':child_count,
        'placeCount':len(places['places']),'scanPath':f'versions/{version_name}/scan/colored_mesh.ply','keyframesPath':f'versions/{version_name}/semantic_samples',
        'placesPath':f'versions/{version_name}/semantics/places.json','cognitionStatus':'NEEDS_REBUILD','safeForNavigation':False}
    atomic_json(temporary/'merge_report.json',result);versions.mkdir(parents=True,exist_ok=True);temporary.replace(destination)
    parent_manifest.update({'activeMergedVersion':version_name,'activeScanPath':result['scanPath'],'activeKeyframesPath':result['keyframesPath'],'activePlacesPath':result['placesPath'],
        'cognitionStatus':'NEEDS_REBUILD_AFTER_MERGE','safeForNavigation':False,'updatedAt':time.time(),'modelRevision':int(parent_manifest.get('modelRevision',0))+1})
    atomic_json(parent/'manifest.json',parent_manifest)
    segment.update({'status':'MERGED_REVIEW_REQUIRED','mergedVersion':version_name,'mergedIntoParent':parent.name});atomic_json(child/'segment.json',segment)
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--models',type=Path,required=True);parser.add_argument('--segment',required=True);parser.add_argument('--max-triangles',type=int,default=100000);args=parser.parse_args()
    print(json.dumps(merge(args.models,args.segment,args.max_triangles),ensure_ascii=False,indent=2))
