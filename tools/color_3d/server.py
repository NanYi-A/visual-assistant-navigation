import argparse, json, os, queue, socket, struct, subprocess, sys, threading, time, uuid
from pathlib import Path
import numpy as np
import open3d as o3d
from protocol import decode_frame,encode_mesh,decode_finish,decode_segment,decode_model_request,encode_model_status,encode_localization_result,message_type,FINISH_HOME,START_SEGMENT,MODEL_REQUEST,OPTIMIZED_MESH,MAX_BYTES
from rgbd_dataset import RgbdDatasetRecorder,load_dataset
TOOLS_DIR=Path(__file__).resolve().parents[1]
# Both entries are required: `tools` exposes the navigation package, while
# `tools/navigation` exposes its flat sibling modules (occupancy, standable, ...).
for _nav in (TOOLS_DIR/'navigation',TOOLS_DIR):
    if str(_nav) not in sys.path: sys.path.insert(0,str(_nav))

_manifest_lock=threading.RLock()
gpu_work_lock=threading.Lock()

def write_json_atomic(target,data):
    with _manifest_lock:
        target=Path(target);target.parent.mkdir(parents=True,exist_ok=True)
        temporary=target.with_name(f'.{target.name}.{threading.get_ident()}.{uuid.uuid4().hex}.tmp')
        temporary.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
        os.replace(temporary,target)

class SemanticSampleRecorder:
    # interval 24 (the old default) kept one frame in twenty-four, so neighbouring stored views
    # showed completely different content and PnP never found more than 7-15 inliers. Two keeps
    # the overlap that relocalization actually needs.
    def __init__(self,models,interval=2,maximum=240):
        self.models=Path(models);self.interval=max(1,interval);self.maximum=max(1,maximum);self.session=None;self.saved=0;self.priority_sessions=set()
    def mark_priority(self,session):self.priority_sessions.add(int(session))
    def offer(self,frame,revision):
        if self.session!=frame.session_id:self.session=frame.session_id;self.saved=0
        interval=4 if frame.session_id in self.priority_sessions else self.interval
        maximum=min(self.maximum,16) if frame.session_id in self.priority_sessions else self.maximum
        if self.saved>=maximum or (revision-1)%interval:return None
        valid=(frame.depth>0)&(frame.confidence>=180)
        if valid.mean()<0.05:return None
        folder=self.models/str(frame.session_id)/'semantic_samples';folder.mkdir(parents=True,exist_ok=True)
        target=folder/f'frame_{frame.frame_id:08d}.npz';temporary=folder/f'.frame_{frame.frame_id:08d}.tmp.npz'
        np.savez_compressed(temporary,rgb=frame.rgb,depth_mm=frame.depth,confidence=frame.confidence,
            intrinsics=np.array((frame.fx,frame.fy,frame.cx,frame.cy),np.float32),camera_to_world=frame.camera_to_world.astype(np.float32),
            frame_id=np.int64(frame.frame_id),frame_timestamp_ns=np.int64(frame.frame_timestamp_ns),image_timestamp_ns=np.int64(frame.image_timestamp_ns))
        temporary.replace(target);self.saved+=1;return target

class ColorModel:
    def __init__(self,voxel=0.04,max_triangles=50000):
        self.voxel=voxel;self.max_triangles=max_triangles;self.session=None;self.revision=0;self._new_volume()
    def _new_volume(self):
        self.volume=o3d.pipelines.integration.ScalableTSDFVolume(self.voxel,self.voxel*3,o3d.pipelines.integration.TSDFVolumeColorType.RGB8)
    def integrate(self,frame):
        if self.session!=frame.session_id:self.session=frame.session_id;self.revision=0;self._new_volume()
        depth=frame.depth.copy();depth[frame.confidence<180]=0
        rgbd=o3d.geometry.RGBDImage.create_from_color_and_depth(o3d.geometry.Image(frame.rgb),o3d.geometry.Image(depth),depth_scale=1000.0,depth_trunc=6.0,convert_rgb_to_intensity=False)
        intrinsic=o3d.camera.PinholeCameraIntrinsic(frame.width,frame.height,frame.fx,frame.fy,frame.cx,frame.cy)
        cv_from_ar=np.diag([1.0,-1.0,-1.0,1.0]);world_from_cv=frame.camera_to_world@cv_from_ar
        self.volume.integrate(rgbd,intrinsic,np.linalg.inv(world_from_cv));self.revision+=1
    def mesh_packet(self,source_frame):
        mesh=self.extract_mesh()
        return encode_mesh(self.session,self.revision,source_frame,self.voxel,np.asarray(mesh.vertices),np.asarray(mesh.triangles),np.asarray(mesh.vertex_normals),np.asarray(mesh.vertex_colors))
    def extract_mesh(self):
        mesh=self.volume.extract_triangle_mesh();mesh.remove_degenerate_triangles();mesh.remove_duplicated_triangles();mesh.remove_duplicated_vertices();mesh.remove_non_manifold_edges()
        if len(mesh.triangles)>self.max_triangles:mesh=mesh.simplify_quadric_decimation(self.max_triangles)
        mesh.compute_vertex_normals()
        return mesh

def recv_exact(conn,n):
    out=bytearray()
    while len(out)<n:
        part=conn.recv(n-len(out))
        if not part:raise EOFError
        out+=part
    return bytes(out)

def write_manifest(models,model,triangles=0):
    with _manifest_lock:
        model_id=str(model.session);folder=models/model_id;folder.mkdir(parents=True,exist_ok=True)
        target=folder/'manifest.json';previous={}
        if target.exists():
            try:previous=json.loads(target.read_text(encoding='utf-8'))
            except (OSError,json.JSONDecodeError):pass
        now=time.time();closed=previous.get('status') in {'PROCESSING','READY','FAILED','INTERRUPTED_RECOVERABLE'}
        data={**previous,'schemaVersion':1,'modelId':model_id,'name':previous.get('name',f'家庭模型 {time.strftime("%Y-%m-%d %H:%M")}'),'status':previous.get('status') if closed else 'SCANNING','createdAt':previous.get('createdAt',now),'updatedAt':now,'colorRevision':model.revision,'coloredTriangles':triangles,'cognitionStatus':previous.get('cognitionStatus') if closed else 'SCANNING','processingPolicy':'UNTIL_COMPLETE','lastProgressAt':now}
        data.pop('processingDeadlineSeconds',None);write_json_atomic(target,data)

def finish_model(models,session,places):
    with _manifest_lock:
        folder=models/str(session);semantic=folder/'semantics';semantic.mkdir(parents=True,exist_ok=True)
        write_json_atomic(semantic/'places.json',{'schemaVersion':1,'modelId':str(session),'places':places})
        manifest=folder/'manifest.json';data=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {'schemaVersion':1,'modelId':str(session),'createdAt':time.time()}
        dataset=folder/'rgbd_dataset';keyframes=len(load_dataset(dataset)) if dataset.exists() else 0
        data.update({'status':'PROCESSING','cognitionStatus':'PROCESSING','updatedAt':time.time(),'lastProgressAt':time.time(),'processingPolicy':'UNTIL_COMPLETE','processingStage':'SEMANTIC_INFERENCE','placeCount':len(places),
                     'rgbdDatasetStatus':'READY' if keyframes else 'EMPTY','rgbdKeyframes':keyframes,'rgbdDatasetPath':'rgbd_dataset'})
        data.pop('processingDeadlineSeconds',None);write_json_atomic(manifest,data)

def update_manifest(models,session,**changes):
    with _manifest_lock:
        folder=models/str(session);manifest=folder/'manifest.json';data=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {'schemaVersion':1,'modelId':str(session),'createdAt':time.time()}
        data.update(changes);data['updatedAt']=time.time();write_json_atomic(manifest,data)

def mark_interrupted_models(models):
    models=Path(models)
    if not models.exists():return 0
    changed=0
    for manifest in models.glob('*/manifest.json'):
        try:
            data=json.loads(manifest.read_text(encoding='utf-8'))
            now=time.time();changed_entry=False
            if data.get('status')=='SCANNING':
                data.update({'status':'INTERRUPTED_RECOVERABLE','cognitionStatus':'INTERRUPTED_RECOVERABLE',
                             'updatedAt':now,'lastProgressAt':now,
                             'recoveryHint':'已保存彩色网格、占据地图和语义样本；可保留本段或后续重定位合并'})
                changed_entry=True
            # Processing threads do not survive a server restart. Do not leave model
            # library cards claiming that abandoned GPU jobs are still running forever.
            if data.get('relocalizationStatus')=='PROCESSING' and now-float(data.get('lastProgressAt',0))>60:
                data.update({'relocalizationStatus':'INTERRUPTED','processingStage':'RELOCALIZATION_INTERRUPTED',
                             'processingError':'后端重启时定位任务中断；请重新执行家庭自动定位','updatedAt':now,'lastProgressAt':now})
                changed_entry=True
            if data.get('semanticSegmentationStatus')=='PROCESSING' and now-float(data.get('semanticProcessingHeartbeatAt',data.get('lastProgressAt',0)))>300:
                data.update({'semanticSegmentationStatus':'INTERRUPTED','semanticSegmentationError':'后端重启或推理进程长时间无响应；可稍后重试','updatedAt':now})
                changed_entry=True
            if not changed_entry:continue
            temporary=manifest.with_name('manifest.recovery.tmp.json')
            temporary.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8');temporary.replace(manifest);changed+=1
        except Exception as error:print(f'cannot mark interrupted model {manifest.parent.name}: {error}',flush=True)
    return changed

def start_semantic_preview(models,session):
    models=Path(models).resolve();project=Path(__file__).resolve().parents[2];root=project.parent;samples=models/str(session)/'semantic_samples';output=models/str(session)/'semantics'/'dformerv2'
    source=root/'models'/'dformerv2'/'source';weights=root/'models'/'dformerv2'/'DFormerv2_Small_NYU.pth';script=project/'tools'/'semantic_3d'/'infer_dformerv2_samples.py'
    sample_count=len(list(samples.glob('frame_*.npz'))) if samples.exists() else 0
    if not sample_count:
        update_manifest(models,session,semanticSegmentationStatus='NO_SAMPLES',semanticSampleCount=0);return
    missing=[str(path) for path in (Path(sys._base_executable),source,weights,script) if not path.exists()]
    if missing:
        update_manifest(models,session,semanticSegmentationStatus='NOT_CONFIGURED',semanticSampleCount=sample_count,semanticSegmentationError='missing: '+', '.join(missing));return
    update_manifest(models,session,semanticSegmentationStatus='PROCESSING',semanticSampleCount=sample_count,semanticSegmentationModel='DFormerv2_Small_NYU')
    def run():
        command=[sys._base_executable,str(script),'--source',str(source),'--weights',str(weights),'--samples',str(samples),'--output',str(output)]
        try:
            update_manifest(models,session,semanticSegmentationStatus='PROCESSING',processingStage='WAITING_FOR_GPU',lastProgressAt=time.time())
            with gpu_work_lock:
                process=subprocess.Popen(command,cwd=source,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                while process.poll() is None:
                    update_manifest(models,session,semanticSegmentationStatus='PROCESSING',processingStage='SEMANTIC_INFERENCE',semanticProcessingHeartbeatAt=time.time(),lastProgressAt=time.time())
                    time.sleep(5)
                stdout,stderr=process.communicate()
            if process.returncode:raise RuntimeError((stderr or stdout)[-1200:])
            summary=json.loads((output/'summary.json').read_text(encoding='utf-8'))
            fusion=summary.get('geometryFusion',{})
            changes={'semanticSegmentationStatus':'READY','semanticSampleCount':summary.get('sampleCount',sample_count),'semanticSegmentationSummary':'semantics/dformerv2/summary.json','semanticGeometryStatus':fusion.get('status','WAITING_INPUTS')}
            if fusion.get('path'):changes['semanticGeometryPath']=fusion['path']
            changes.update({'processingStage':'SEMANTIC_READY','lastProgressAt':time.time()})
            update_manifest(models,session,**changes)
            print(f'model {session} semantic previews ready: {summary.get("sampleCount",0)} samples',flush=True)
        except Exception as error:
            update_manifest(models,session,semanticSegmentationStatus='FAILED',semanticSegmentationError=str(error)[-1200:]);print(f'model {session} semantic preview failed: {error}',flush=True)
    threading.Thread(target=run,daemon=True,name=f'semantic-{session}').start()

def start_navigation_cognition(models,session,pixel_stride=6):
    """Run the S1-S4 cognition pipeline over the recorded RGB-D keyframes."""
    import navigation
    dataset=Path(models).resolve()/str(session)/'rgbd_dataset'
    if not (dataset/'metadata.jsonl').exists():
        update_manifest(models,session,cognitionReportStatus='NO_DATASET');return
    output=Path(models).resolve()/str(session)/'cognition';output.mkdir(parents=True,exist_ok=True)
    update_manifest(models,session,cognitionReportStatus='PROCESSING',processingStage='NAVIGATION_COGNITION',lastProgressAt=time.time())
    def run():
        try:
            report=navigation.build_navigation_model(dataset,pixel_stride=pixel_stride)
            payload=report.to_dict()
            (output/'coverage_report.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2),encoding='utf-8')
            update_manifest(models,session,cognitionReportStatus=payload['status'],
                cognitionReport='cognition/coverage_report.json',
                cognitionReadyForNavigation=payload['readyForNavigation'],
                cognitionSupportCoverage=payload['supportCoverage'],
                cognitionReachableRatio=payload['reachableRatio'],
                processingStage='COGNITION_READY' if payload['readyForNavigation'] else 'NEEDS_RESCAN',
                lastProgressAt=time.time())
            print(f"model {session} cognition {payload['status']}: support={payload['supportCoverage']*100:.0f}% reachable={payload['reachableRatio']*100:.0f}%",flush=True)
        except Exception as error:
            update_manifest(models,session,cognitionReportStatus='FAILED',cognitionReportError=str(error)[-1200:],lastProgressAt=time.time())
            print(f'model {session} navigation cognition failed: {error}',flush=True)
    threading.Thread(target=run,daemon=True,name=f'cognition-{session}').start()

def start_segment_relocalization(models,session,on_result=lambda packet:None):
    models=Path(models).resolve();folder=models/str(session);segment=folder/'segment.json'
    if not segment.exists():return
    script=Path(__file__).resolve().parents[1]/'relocalization'/'relocalize_segment.py'
    update_manifest(models,session,relocalizationStatus='PROCESSING',processingStage='RELOCALIZATION',lastProgressAt=time.time())
    def run():
        try:
            relocalization_python=os.environ.get('RELOCALIZATION_PYTHON',sys._base_executable)
            # Interactive home localization must not wait behind background DFormer
            # segmentation. Run the bounded feature matcher on CPU so a long semantic
            # inference cannot hold the only GPU lock and stall the user's route flow.
            worker_env={**os.environ,'VA_RELOCALIZATION_DEVICE':'cpu'}
            result=subprocess.run([relocalization_python,str(script),'--models',str(models),'--segment',str(session)],
                capture_output=True,text=True,timeout=30,check=True,env=worker_env)
            report=json.loads((folder/'relocalization.json').read_text(encoding='utf-8'))
            update_manifest(models,session,relocalizationStatus=report['status'],relocalizationPath='relocalization.json',
                relocalizationMergeAllowed=bool(report['mergeAllowed']),processingStage='RELOCALIZATION_READY',lastProgressAt=time.time())
            print(f"segment {session} relocalization {report['status']}: pairs={report['consistentPairCount']} overlap={report['geometry']['overlapFraction']:.3f}",flush=True)
            on_result(encode_localization_result(session,int(report['parentModelId']),report))
            if report.get('mergeAllowed'):
                merge_script=Path(__file__).resolve().parents[1]/'relocalization'/'merge_segment.py'
                update_manifest(models,session,mergeStatus='PROCESSING',processingStage='MERGING_VERSION',lastProgressAt=time.time())
                merged=subprocess.run([sys.executable,str(merge_script),'--models',str(models),'--segment',str(session)],capture_output=True,text=True,timeout=240,check=True)
                merge_report=json.loads((models/str(report['parentModelId'])/'versions'/f'merge_{session}'/'merge_report.json').read_text(encoding='utf-8'))
                update_manifest(models,session,mergeStatus=merge_report['status'],mergedVersion=merge_report['version'],processingStage='MERGED_REVIEW_REQUIRED',lastProgressAt=time.time())
                print(f"segment {session} merged into parent {report['parentModelId']} candidate={merge_report['version']} triangles={merge_report['mergedTriangles']}",flush=True)
        except Exception as error:
            detail=str(error)
            if isinstance(error,subprocess.CalledProcessError):
                detail=(error.stderr or error.stdout or detail)[-4000:]
            update_manifest(models,session,relocalizationStatus='FAILED',processingStage='RELOCALIZATION_FAILED',processingError=detail[-1200:],lastProgressAt=time.time())
            try:
                metadata=json.loads(segment.read_text(encoding='utf-8'))
                failed={'schemaVersion':2,'parentModelId':metadata['parentModelId'],'segmentId':str(session),
                    'status':'LOCALIZATION_FAILED','mergeAllowed':False,'consistentPairCount':0,'totalInliers':0,
                    'geometry':{'overlapFraction':0.0,'medianDistanceMeters':None},'failure':detail[-1200:]}
                write_json_atomic(folder/'relocalization.json',failed)
                on_result(encode_localization_result(session,int(metadata['parentModelId']),failed))
            except Exception as notify_error:
                print(f'segment {session} failed-result delivery error: {notify_error}',flush=True)
            print(f'segment {session} relocalization failed: {detail}',flush=True)
    threading.Thread(target=run,daemon=True,name=f'relocalize-{session}').start()

def serve(port,voxel,output,models,sample_interval=2,sample_max=240):
    model=ColorModel(voxel);latest=queue.Queue(maxsize=1);client={'socket':None};lock=threading.Lock();output.mkdir(parents=True,exist_ok=True);models.mkdir(parents=True,exist_ok=True)
    samples=SemanticSampleRecorder(models,interval=sample_interval,maximum=sample_max)
    interrupted=mark_interrupted_models(models)
    if interrupted:print(f'marked {interrupted} interrupted model(s) as recoverable',flush=True)
    def send_result(packet):
        with lock:
            conn=client['socket']
            if conn:
                try:conn.sendall(struct.pack('>I',len(packet))+packet)
                except OSError:client['socket']=None
    rgbd=RgbdDatasetRecorder(models)
    def send_current_model(session):
        # No offline re-registration step any more: the ARCore-posed TSDF is the
        # authoritative geometry, so a model request replays that scan mesh.
        folder=models/str(session);mesh=None
        with lock:
            live=model.session==session and model.revision>0
        if live:
            try: mesh=model.extract_mesh()
            except RuntimeError: mesh=None
        if mesh is None or not len(mesh.triangles):
            scan=folder/'scan'/'colored_mesh.ply'
            if scan.is_file(): mesh=o3d.io.read_triangle_mesh(str(scan))
        if mesh is None or not len(mesh.triangles):
            send_result(encode_model_status(session,{'status':'PROCESSING','available':False,'navigationReady':False,'message':'原始扫描尚未生成，请继续采集'}));return
        mesh.remove_degenerate_triangles();mesh.remove_duplicated_triangles();mesh.remove_non_manifold_edges()
        if len(mesh.triangles)>45000:mesh=mesh.simplify_quadric_decimation(45000)
        mesh.compute_vertex_normals();colors=np.asarray(mesh.vertex_colors)
        if len(colors)!=len(mesh.vertices):colors=np.full((len(mesh.vertices),3),.72)
        packet=encode_mesh(session,model.revision if live else int(time.time()),0,.02,np.asarray(mesh.vertices),np.asarray(mesh.triangles),np.asarray(mesh.vertex_normals),colors,OPTIMIZED_MESH)
        send_result(encode_model_status(session,{'status':'READY','available':True,'navigationReady':False,
            'optimizedPoseCoverage':1.0,'triangleCount':(len(packet)-40)//57,
            'message':'原始扫描模型（ARCore 位姿直接融合，未经离线重定位）；导航认知层尚未生成'}))
        send_result(packet)
    def worker():
        while True:
            frame=latest.get();started=time.perf_counter()
            if abs(frame.image_timestamp_ns-frame.frame_timestamp_ns)>120_000_000:
                print(f'drop unsynchronised RGB-D frame={frame.frame_id} deltaMs={abs(frame.image_timestamp_ns-frame.frame_timestamp_ns)/1e6:.1f}',flush=True);continue
            previous_session=model.session;model.integrate(frame)
            if previous_session!=model.session:write_manifest(models,model)
            sample=samples.offer(frame,model.revision)
            if sample:print(f'saved semantic RGB-D sample {sample}',flush=True)
            if model.revision%8:continue
            mesh=model.extract_mesh();packet=encode_mesh(model.session,model.revision,frame.frame_id,model.voxel,np.asarray(mesh.vertices),np.asarray(mesh.triangles),np.asarray(mesh.vertex_normals),np.asarray(mesh.vertex_colors))
            if model.revision%24==0:
                scan=models/str(model.session)/'scan';scan.mkdir(parents=True,exist_ok=True);temporary=scan/'colored_mesh.tmp.ply';destination=scan/'colored_mesh.ply'
                if o3d.io.write_triangle_mesh(str(temporary),mesh,write_ascii=False,compressed=True,write_vertex_normals=True,write_vertex_colors=True):temporary.replace(destination)
                write_manifest(models,model,len(mesh.triangles))
            with lock:
                conn=client['socket']
                if conn:
                    try:conn.sendall(struct.pack('>I',len(packet))+packet)
                    except OSError:client['socket']=None
            print(f'color frames={model.revision} mesh={(len(packet)-40)//57} packet={len(packet)/1048576:.2f}MB time={time.perf_counter()-started:.2f}s',flush=True)
    threading.Thread(target=worker,daemon=True).start()
    with socket.create_server(('0.0.0.0',port),reuse_port=False) as server:
        print(f'VA3D RGB-D Open3D server listening on {port}; voxel={voxel*100:.1f}cm',flush=True)
        while True:
            conn,_=server.accept()
            with conn:
                with lock:client['socket']=conn
                try:
                    while True:
                        size=struct.unpack('>I',recv_exact(conn,4))[0]
                        if not 8<=size<=MAX_BYTES:raise ValueError('invalid packet length')
                        payload=recv_exact(conn,size)
                        if message_type(payload)==START_SEGMENT:
                            session,parent=decode_segment(payload);folder=models/str(session);folder.mkdir(parents=True,exist_ok=True)
                            samples.mark_priority(session)
                            (folder/'segment.json').write_text(json.dumps({'schemaVersion':1,'segmentId':str(session),'parentModelId':str(parent),'status':'WAITING_RELOCALIZATION','mergeAllowed':False},ensure_ascii=False,indent=2),encoding='utf-8')
                            print(f'segment {session} belongs to {parent}; kept isolated pending relocalization',flush=True);continue
                        if message_type(payload)==MODEL_REQUEST:
                            session=decode_model_request(payload);send_current_model(session);continue
                        if message_type(payload)==FINISH_HOME:
                            session,places=decode_finish(payload);finish_model(models,session,places);start_navigation_cognition(models,session);start_segment_relocalization(models,session,send_result);start_semantic_preview(models,session);print(f'model {session} submitted with {len(places)} confirmed places',flush=True);continue
                        frame=decode_frame(payload)
                        # Preserve the dense temporal sequence before the bounded Open3D
                        # preview queue drops frames, so navigation cognition can replay it
                        # without inheriting the slower mesh worker's cadence.
                        if abs(frame.image_timestamp_ns-frame.frame_timestamp_ns)<=120_000_000:
                            keyframe=rgbd.offer(frame)
                            if keyframe:
                                if keyframe['count']==1 or keyframe['count']%10==0:
                                    update_manifest(models,frame.session_id,rgbdDatasetStatus='RECORDING',rgbdKeyframes=keyframe['count'],
                                        rgbdDatasetPath='rgbd_dataset',lastProgressAt=time.time())
                                print(f"saved RGB-D keyframe {frame.frame_id} reason={keyframe['record']['keyframeReason']} count={keyframe['count']}",flush=True)
                        if latest.full():latest.get_nowait()
                        latest.put_nowait(frame)
                except (EOFError,OSError,ValueError) as error:print(f'RGB-D client closed: {error}',flush=True)
                finally:
                    with lock:
                        if client['socket'] is conn:client['socket']=None

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=38401);parser.add_argument('--voxel-cm',type=float,default=4);parser.add_argument('--output',type=Path,default=Path('tools/color_3d/output'));parser.add_argument('--models',type=Path,default=Path('models'));parser.add_argument('--sample-interval',type=int,default=2,help='Keep one in every N RGB-D frames as a relocalization sample. The old default of 24 produced frames so far apart that neighbouring views shared no features, which is what made relocalization fail with only 7-15 PnP inliers per pair.');parser.add_argument('--sample-max',type=int,default=240,help='Cap on stored samples per session, so a long walk cannot fill the disk.')
    args=parser.parse_args();serve(args.port,args.voxel_cm/100,args.output,args.models,sample_interval=args.sample_interval,sample_max=args.sample_max)
