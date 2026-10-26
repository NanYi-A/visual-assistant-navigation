"""Render DFormerv2 semantic previews from bounded RGB-D samples saved by color_3d."""

from __future__ import annotations

import argparse
import importlib
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import torch


def unrotate_labels(image: np.ndarray, rotation: int) -> np.ndarray:
    inverse = {90: cv2.ROTATE_90_COUNTERCLOCKWISE, 180: cv2.ROTATE_180,
               270: cv2.ROTATE_90_CLOCKWISE}.get(rotation)
    return cv2.rotate(image, inverse) if inverse is not None else image


def project_semantic_points(depth_mm: np.ndarray, depth_confidence: np.ndarray,
                            labels: np.ndarray, model_confidence: np.ndarray,
                            intrinsics: np.ndarray, camera_to_world: np.ndarray,
                            minimum_model_confidence: float = 0.55) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Project trusted semantic pixels into the same AR world frame used by the TSDF."""
    height, width = depth_mm.shape
    if labels.shape != (height, width):
        labels = cv2.resize(labels, (width, height), interpolation=cv2.INTER_NEAREST)
        model_confidence = cv2.resize(model_confidence, (width, height), interpolation=cv2.INTER_LINEAR)
    valid = ((depth_confidence >= 180) & (depth_mm >= 200) & (depth_mm <= 6000)
             & (model_confidence >= minimum_model_confidence))
    ys, xs = np.nonzero(valid)
    if len(xs) == 0:
        return np.empty((0, 3), np.float32), np.empty(0, np.uint8), np.empty(0, np.float32)
    fx, fy, cx, cy = intrinsics.astype(np.float32)
    z = depth_mm[ys, xs].astype(np.float32) / 1000.0
    camera_cv = np.stack(((xs.astype(np.float32) - cx) * z / fx,
                          (ys.astype(np.float32) - cy) * z / fy, z,
                          np.ones_like(z)), axis=1)
    cv_from_ar = np.diag([1.0, -1.0, -1.0, 1.0]).astype(np.float32)
    world_from_cv = camera_to_world.astype(np.float32) @ cv_from_ar
    world = (world_from_cv @ camera_cv.T).T[:, :3]
    return world, labels[ys, xs].astype(np.uint8), model_confidence[ys, xs].astype(np.float32)


def write_semantic_voxels(output: Path, observations: list[tuple[np.ndarray, np.ndarray, np.ndarray]],
                          colors: np.ndarray, class_names: list[str], voxel_size: float = 0.08) -> dict:
    votes: dict[tuple[int, int, int], dict] = {}
    for frame_index, (points, labels, confidence) in enumerate(observations):
        keys = np.floor(points / voxel_size).astype(np.int32)
        for key_array, label, score in zip(keys, labels, confidence):
            key = tuple(int(value) for value in key_array)
            cell = votes.setdefault(key, {'scores': {}, 'frames': set(), 'count': 0})
            cell['scores'][int(label)] = cell['scores'].get(int(label), 0.0) + float(score)
            cell['frames'].add(frame_index); cell['count'] += 1
    rows = []
    for key, cell in votes.items():
        label, score = max(cell['scores'].items(), key=lambda item: item[1])
        total = sum(cell['scores'].values())
        rows.append((key, label, score / total if total else 0.0, len(cell['frames']), cell['count']))
    rows.sort(key=lambda row: row[0])
    centers = np.asarray([[(axis + 0.5) * voxel_size for axis in row[0]] for row in rows], np.float32)
    labels = np.asarray([row[1] for row in rows], np.uint8)
    agreement = np.asarray([row[2] for row in rows], np.float32)
    frame_counts = np.asarray([row[3] for row in rows], np.uint8)
    observation_counts = np.asarray([row[4] for row in rows], np.uint16)
    np.savez_compressed(output / 'semantic_voxels.npz', xyz=centers, labels=labels,
                        agreement=agreement, frame_counts=frame_counts,
                        observation_counts=observation_counts,
                        voxel_size=np.float32(voxel_size), class_names=np.asarray(class_names))
    with (output / 'semantic_voxels.ply').open('w', encoding='ascii', newline='\n') as stream:
        stream.write('ply\nformat ascii 1.0\n')
        stream.write(f'element vertex {len(centers)}\nproperty float x\nproperty float y\nproperty float z\n')
        stream.write('property uchar red\nproperty uchar green\nproperty uchar blue\n')
        stream.write('property uchar class_id\nproperty float agreement\nproperty uchar frame_count\nend_header\n')
        for point, label, certainty, seen in zip(centers, labels, agreement, frame_counts):
            color = colors[int(label)]
            stream.write(f'{point[0]:.4f} {point[1]:.4f} {point[2]:.4f} {color[0]} {color[1]} {color[2]} {label} {certainty:.4f} {seen}\n')
    trusted = (frame_counts >= 2) & (agreement >= 0.7)
    trusted_ids, trusted_counts = np.unique(labels[trusted], return_counts=True)
    trusted_order = np.argsort(trusted_counts)[::-1]
    return {'voxelSizeMeters': voxel_size, 'voxelCount': len(rows),
            'multiFrameVoxelCount': int(np.count_nonzero(frame_counts >= 2)),
            'highAgreementVoxelCount': int(np.count_nonzero(agreement >= 0.7)),
            'trustedVoxelCount': int(np.count_nonzero(trusted)),
            'boundsMeters': {'minimum': centers.min(axis=0).round(3).tolist(),
                             'maximum': centers.max(axis=0).round(3).tolist()} if len(centers) else None,
            'trustedClasses': [
                {'id': int(trusted_ids[index]), 'name': class_names[int(trusted_ids[index])],
                 'voxels': int(trusted_counts[index])}
                for index in trusted_order
            ]}


def palette(count: int) -> np.ndarray:
    colors = np.zeros((count, 3), np.uint8)
    for index in range(count):
        value = index
        for shift in range(8):
            colors[index, 0] |= ((value >> 0) & 1) << (7 - shift)
            colors[index, 1] |= ((value >> 1) & 1) << (7 - shift)
            colors[index, 2] |= ((value >> 2) & 1) << (7 - shift)
            value >>= 3
    return colors


def normalize(image: np.ndarray, mean: list[float], std: list[float]) -> np.ndarray:
    result = image.astype(np.float32) / 255.0
    return (result - np.asarray(mean, np.float32)) / np.asarray(std, np.float32)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--samples", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rotation", type=int, choices=(0,90,180,270), default=90,
        help="Clockwise rotation from ARCore camera coordinates to an upright phone view")
    parser.add_argument("--save-predictions", action="store_true",
        help="Keep large per-frame logits-derived arrays for debugging")
    args = parser.parse_args()

    sys.path.insert(0, str(args.source.resolve()))
    config = importlib.import_module("local_configs.NYUDepthv2.DFormerv2_S").C
    config.pretrained_model = None
    from models.builder import EncoderDecoder

    model = EncoderDecoder(cfg=config, norm_layer=torch.nn.BatchNorm2d, syncbn=False)
    checkpoint = torch.load(args.weights, map_location="cpu", weights_only=False)
    state = checkpoint.get("model", checkpoint.get("state_dict", checkpoint))
    incompatible = model.load_state_dict(state, strict=False)
    if incompatible.missing_keys or incompatible.unexpected_keys:
        raise RuntimeError(f"checkpoint mismatch: {incompatible}")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.eval().to(device);args.output.mkdir(parents=True,exist_ok=True)
    colors=palette(len(config.class_names));results=[];world_observations=[]

    for sample_path in sorted(args.samples.glob("frame_*.npz")):
        with np.load(sample_path) as sample:
            rgb=sample["rgb"].copy();depth=sample["depth_mm"].copy();confidence=sample["confidence"].copy()
            intrinsics=sample["intrinsics"].copy();camera_to_world=sample["camera_to_world"].copy()
        original_depth=depth.copy();original_depth_confidence=confidence.copy()
        rotate_code={90:cv2.ROTATE_90_CLOCKWISE,180:cv2.ROTATE_180,270:cv2.ROTATE_90_COUNTERCLOCKWISE}.get(args.rotation)
        if rotate_code is not None:
            rgb=cv2.rotate(rgb,rotate_code);depth=cv2.rotate(depth,rotate_code);confidence=cv2.rotate(confidence,rotate_code)
        target=(480,640) if rgb.shape[0]>rgb.shape[1] else (640,480)
        rgb_resized=cv2.resize(rgb,target,interpolation=cv2.INTER_LINEAR)
        depth=np.where(confidence>=180,depth,0)
        depth_u8=np.clip(np.rint(depth.astype(np.float32)*255.0/10000.0),0,255).astype(np.uint8)
        depth_resized=cv2.resize(depth_u8,target,interpolation=cv2.INTER_LINEAR)
        # The official NYUv2 loader uses BGR for color and repeats its one-channel depth three times.
        rgb_input=normalize(rgb_resized[...,::-1],[0.485,0.456,0.406],[0.229,0.224,0.225]).transpose(2,0,1)
        depth3=np.repeat(depth_resized[...,None],3,axis=2)
        depth_input=normalize(depth3,[0.48]*3,[0.28]*3).transpose(2,0,1)
        rgb_tensor=torch.from_numpy(np.ascontiguousarray(rgb_input))[None].to(device)
        depth_tensor=torch.from_numpy(np.ascontiguousarray(depth_input))[None].to(device)
        if device.type=="cuda":torch.cuda.synchronize()
        started=time.perf_counter()
        with torch.inference_mode(),torch.autocast(device_type=device.type,dtype=torch.float16,enabled=device.type=="cuda"):
            logits=model(rgb_tensor,depth_tensor)
            probabilities=torch.softmax(logits.float(),dim=1)
            model_confidence_tensor,labels_tensor=probabilities.max(1)
            labels=labels_tensor[0].cpu().numpy().astype(np.uint8)
            model_confidence=model_confidence_tensor[0].cpu().numpy().astype(np.float32)
        if device.type=="cuda":torch.cuda.synchronize()
        elapsed=(time.perf_counter()-started)*1000
        semantic=colors[labels];overlay=np.clip(rgb_resized.astype(np.float32)*0.55+semantic.astype(np.float32)*0.45,0,255).astype(np.uint8)
        stem=sample_path.stem
        cv2.imwrite(str(args.output/f'{stem}_rgb.png'),cv2.cvtColor(rgb_resized,cv2.COLOR_RGB2BGR))
        cv2.imwrite(str(args.output/f'{stem}_depth.png'),depth_resized)
        cv2.imwrite(str(args.output/f'{stem}_semantic.png'),cv2.cvtColor(semantic,cv2.COLOR_RGB2BGR))
        cv2.imwrite(str(args.output/f'{stem}_overlay.png'),cv2.cvtColor(overlay,cv2.COLOR_RGB2BGR))
        labels_camera=unrotate_labels(labels,args.rotation)
        confidence_camera=unrotate_labels(model_confidence,args.rotation)
        points_world,point_labels,point_confidence=project_semantic_points(
            original_depth,original_depth_confidence,labels_camera,confidence_camera,intrinsics,camera_to_world)
        world_observations.append((points_world,point_labels,point_confidence))
        if args.save_predictions:
            np.savez_compressed(args.output/f'{stem}_prediction.npz',labels=labels,
                                confidence=model_confidence,labels_camera=labels_camera,
                                confidence_camera=confidence_camera)
        ids,counts=np.unique(labels,return_counts=True);order=np.argsort(counts)[::-1][:8]
        results.append({'sample':sample_path.name,'inferenceMs':round(elapsed,1),'topClasses':[
            {'id':int(ids[i]),'name':config.class_names[int(ids[i])],'percent':round(float(counts[i])*100/labels.size,2)} for i in order]})
    voxel_summary=write_semantic_voxels(args.output,world_observations,colors,list(config.class_names))
    summary={'schemaVersion':2,'model':'DFormerv2_Small_NYU','rotationDegreesClockwise':args.rotation,
             'minimumProjectionConfidence':0.55,'sampleCount':len(results),
             'semanticVoxels':voxel_summary,'results':results}
    model_root=args.output.parents[1]
    required=(model_root/'cognition'/'occupancy.vamap',model_root/'scan'/'colored_mesh.ply')
    if all(path.exists() for path in required):
        try:
            from fuse_semantic_geometry import fuse
            fused=fuse(model_root)
            summary['geometryFusion']={'status':'READY','path':'cognition/semantic_geometry.json',
                                       'navigationPath':'cognition/semantic_navigation.json',
                                       **fused['counts'],'navigationCandidates':fused['navigationCandidates']}
        except Exception as error:
            summary['geometryFusion']={'status':'FAILED','error':str(error)[:1000]}
    else:
        summary['geometryFusion']={'status':'WAITING_INPUTS','missing':[str(path.relative_to(model_root)) for path in required if not path.exists()]}
    (args.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
