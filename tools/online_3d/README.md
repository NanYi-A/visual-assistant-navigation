# VA3D 电脑接收端（阶段 A）

本目录当前提供零第三方依赖的数据接收、无损录制、协议校验和粗点云回放。它用于先验证手机的深度、位姿、内参、时间戳和坐标方向，尚未包含 nvblox、ESDF 或导航规划。

## 启动

```powershell
cd .\tools\online_3d
python server.py --port 38400
```

Android 空间页通过 intent extra 开启实验上行：

```powershell
adb shell am start -n cn.visualassistant.app/.spatial.SpatialActivity \
  --ez autoStart true --es online3dHost 电脑局域网IP --ei online3dPort 38400
```

没有 `online3dHost` 时完全关闭上行，现有本地模式不受影响。连接失败时使用容量为一的最新帧队列，不阻塞 ARCore，也不累积旧数据。

## 回放与导出

```powershell
python replay.py recordings\capture_xxx.va3d
```

回放会验证包结构、单一 session 和严格递增的 Raw Depth 时间戳，并输出可在 CloudCompare/Open3D 中查看的 PLY。PLY 只是坐标验证产物，不是最终导航地图。
