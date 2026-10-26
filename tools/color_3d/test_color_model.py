import json,tempfile,unittest,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import numpy as np
from protocol import *
from server import ColorModel,SemanticSampleRecorder,start_semantic_preview,write_manifest,mark_interrupted_models

class Tests(unittest.TestCase):
    def test_manifest_creates_isolated_model_space(self):
        model=ColorModel();model.session=123;model.revision=24
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);write_manifest(root,model,321)
            data=json.loads((root/'123'/'manifest.json').read_text(encoding='utf-8'))
            self.assertEqual('123',data['modelId']);self.assertEqual('SCANNING',data['status'])
            self.assertEqual(321,data['coloredTriangles']);self.assertEqual('UNTIL_COMPLETE',data['processingPolicy'])
            self.assertNotIn('processingDeadlineSeconds',data)
    def frame(self):
        w,h=32,24;depth=np.full((h,w),1000,np.uint16);confidence=np.full((h,w),255,np.uint8);rgb=np.zeros((h,w,3),np.uint8);rgb[:,:,0]=220
        return RgbdFrame(1,1,1,1,1,1,w,h,30.,30.,w/2,h/2,np.eye(4),depth,confidence,rgb)
    def test_packet_round_trip(self):
        f=self.frame();head=struct.pack('>IHHQQQQQQIIffff16f',MAGIC,VERSION,RGBD_FRAME,f.session_id,f.frame_id,f.frame_timestamp_ns,f.depth_timestamp_ns,f.image_timestamp_ns,f.sent_at_ns,f.width,f.height,f.fx,f.fy,f.cx,f.cy,*f.camera_to_world.reshape(-1,order='F'))
        decoded=decode_frame(head+f.depth.astype('>u2').tobytes()+f.confidence.tobytes()+f.rgb.tobytes());self.assertEqual((24,32,3),decoded.rgb.shape);self.assertEqual(220,int(decoded.rgb[0,0,0]))
    def test_open3d_extracts_colored_plane(self):
        model=ColorModel(.05);f=self.frame()
        for i in range(4):f.frame_id=i+1;model.integrate(f)
        packet=model.mesh_packet(4);self.assertGreater(len(packet),40);self.assertEqual(COLORED_MESH,struct.unpack_from('>H',packet,6)[0])
    def test_semantic_samples_are_bounded_and_self_contained(self):
        with tempfile.TemporaryDirectory() as folder:
            recorder=SemanticSampleRecorder(Path(folder),interval=2,maximum=2);frame=self.frame()
            for revision in range(1,8):frame.frame_id=revision;recorder.offer(frame,revision)
            samples=list((Path(folder)/'1'/'semantic_samples').glob('*.npz'));self.assertEqual(2,len(samples))
            with np.load(samples[0]) as saved:
                self.assertEqual((24,32,3),saved['rgb'].shape);self.assertEqual((24,32),saved['depth_mm'].shape)
                self.assertEqual(1,int(saved['frame_id']))
    def test_default_semantic_budget_covers_long_scans(self):
        with tempfile.TemporaryDirectory() as folder:
            # Relocalization needs many overlapping views; at one sample per two frames a long
            # scan must still be able to fill the budget rather than being capped at a dozen.
            recorder=SemanticSampleRecorder(Path(folder));self.assertEqual(240,recorder.maximum)
            self.assertEqual(2,recorder.interval)
    def test_semantic_preview_reports_missing_samples(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);model=ColorModel();model.session=9;write_manifest(root,model)
            start_semantic_preview(root,9)
            manifest=json.loads((root/'9'/'manifest.json').read_text(encoding='utf-8'))
            self.assertEqual('NO_SAMPLES',manifest['semanticSegmentationStatus']);self.assertEqual(0,manifest['semanticSampleCount'])
    def test_interrupted_scan_is_recoverable_not_failed(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);model=ColorModel();model.session=11;write_manifest(root,model)
            self.assertEqual(1,mark_interrupted_models(root))
            manifest=json.loads((root/'11'/'manifest.json').read_text(encoding='utf-8'))
            self.assertEqual('INTERRUPTED_RECOVERABLE',manifest['status']);self.assertIn('已保存',manifest['recoveryHint'])

if __name__=='__main__':unittest.main()
