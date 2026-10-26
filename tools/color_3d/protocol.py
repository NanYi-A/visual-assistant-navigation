import io, struct, json
from dataclasses import dataclass
import numpy as np

MAGIC=0x56413344; VERSION=2; RGBD_FRAME=5; COLORED_MESH=6; MAX_BYTES=4*1024*1024
FINISH_HOME=7
START_SEGMENT=8
LOCALIZATION_RESULT=9
MODEL_REQUEST=10
MODEL_STATUS=11
OPTIMIZED_MESH=12
HEADER=144

@dataclass
class RgbdFrame:
    session_id:int; frame_id:int; frame_timestamp_ns:int; depth_timestamp_ns:int; image_timestamp_ns:int; sent_at_ns:int
    width:int; height:int; fx:float; fy:float; cx:float; cy:float; camera_to_world:np.ndarray
    depth:np.ndarray; confidence:np.ndarray; rgb:np.ndarray

def decode_frame(data:bytes)->RgbdFrame:
    if not HEADER<=len(data)<=MAX_BYTES: raise ValueError('invalid RGB-D packet size')
    values=struct.unpack_from('>IHHQQQQQQIIffff16f',data,0)
    magic,version,kind=values[:3]
    if (magic,version,kind)!=(MAGIC,VERSION,RGBD_FRAME): raise ValueError('invalid RGB-D header')
    session,frame,frame_ts,depth_ts,image_ts,sent_at,width,height=values[3:11]
    if width<=0 or height<=0 or len(data)!=HEADER+width*height*6: raise ValueError('RGB-D dimensions mismatch')
    matrix=np.array(values[15:31],dtype=np.float64).reshape((4,4),order='F')
    offset=HEADER; pixels=width*height
    depth=np.frombuffer(data,dtype='>u2',count=pixels,offset=offset).astype(np.uint16).reshape(height,width);offset+=pixels*2
    confidence=np.frombuffer(data,dtype=np.uint8,count=pixels,offset=offset).reshape(height,width);offset+=pixels
    rgb=np.frombuffer(data,dtype=np.uint8,count=pixels*3,offset=offset).reshape(height,width,3).copy()
    return RgbdFrame(session,frame,frame_ts,depth_ts,image_ts,sent_at,width,height,*values[11:15],matrix,depth,confidence,rgb)

def message_type(data:bytes)->int:
    if len(data)<8:return -1
    magic,version,kind=struct.unpack_from('>IHH',data,0)
    return kind if (magic,version)==(MAGIC,VERSION) else -1

def decode_finish(data:bytes):
    stream=io.BytesIO(data);magic,version,kind,session,count=struct.unpack('>IHHQI',stream.read(20))
    if (magic,version,kind)!=(MAGIC,VERSION,FINISH_HOME) or not 0<=count<=1000:raise ValueError('invalid finish packet')
    def utf():
        size=struct.unpack('>H',stream.read(2))[0];return stream.read(size).decode('utf-8')
    places=[]
    for _ in range(count):
        pid=utf();name=utf();created,*pose=struct.unpack('>Q6f',stream.read(32));places.append({'placeId':pid,'name':name,'createdAt':created,'anchorPoseMap':{'x':pose[0],'y':pose[1],'z':pose[2],'forwardX':pose[3],'forwardY':pose[4],'forwardZ':pose[5]},'userConfirmed':True})
    if stream.read(1):raise ValueError('trailing finish data')
    return session,places

def decode_segment(data:bytes):
    if len(data)!=24: raise ValueError('invalid segment packet size')
    magic,version,kind,session,parent=struct.unpack('>IHHQQ',data)
    if (magic,version,kind)!=(MAGIC,VERSION,START_SEGMENT): raise ValueError('invalid segment packet')
    return session,parent

def decode_model_request(data:bytes):
    if len(data)!=16:raise ValueError('invalid model request')
    magic,version,kind,session=struct.unpack('>IHHQ',data)
    if (magic,version,kind)!=(MAGIC,VERSION,MODEL_REQUEST):raise ValueError('invalid model request header')
    return session

def encode_model_status(session,status):
    payload=json.dumps(status,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    return struct.pack('>IHHQI',MAGIC,VERSION,MODEL_STATUS,session,len(payload))+payload

def encode_localization_result(session,parent,report):
    status=1 if report.get('mergeAllowed') else 0;geometry=report.get('geometry',{});transform=report.get('transformParentFromSegment') or np.eye(4).tolist()
    return struct.pack('>IHHQQBIIff16f',MAGIC,VERSION,LOCALIZATION_RESULT,session,parent,status,
        int(report.get('consistentPairCount',0)),int(report.get('totalInliers',0)),float(geometry.get('overlapFraction',0)),
        float(geometry.get('medianDistanceMeters') or -1),*np.asarray(transform,dtype=np.float32).reshape(-1))

def encode_mesh(session_id:int,revision:int,source_frame_id:int,voxel_size:float,vertices:np.ndarray,triangles:np.ndarray,normals:np.ndarray,colors:np.ndarray,kind:int=COLORED_MESH)->bytes:
    count=min(len(triangles),(MAX_BYTES-40)//57);out=io.BytesIO();out.write(struct.pack('>IHHQQQfI',MAGIC,VERSION,kind,session_id,revision,source_frame_id,voxel_size,count))
    for tri in triangles[:count]:
        pts=vertices[tri].astype(np.float32);normal=normals[tri].mean(axis=0).astype(np.float32);length=np.linalg.norm(normal);normal=normal/length if length>1e-8 else np.array((0,1,0),np.float32)
        out.write(struct.pack('>12f',*(pts.reshape(-1).tolist()+normal.tolist())))
        out.write(np.clip(np.rint(colors[tri]*255),0,255).astype(np.uint8).tobytes())
    return out.getvalue()
