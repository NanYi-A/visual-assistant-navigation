# 彩色家庭三维模型后端

该服务只负责给家人查看可辨认的彩色三维表面。安全判断、障碍急停和路径规划仍由手机本地逻辑与 38400 Kotlin 服务负责，不能用彩色模型替代安全地图。

## 启动

直接双击项目根目录的（推荐 `.bat`）：

```text
.\一键启动双后台.bat
```

兼容的 `.cmd` 入口也保留：

```text
.\一键启动双后台.cmd
```

也可以在 PowerShell 中运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\color_3d\start_backends.ps1
```

脚本会启动：

- `38400`：Kotlin 导航地图；
- `38401`：Python/Open3D RGB-D 彩色 TSDF；
- 日志和导出的模型：`.\tools\color_3d\output`。

启动器会留下一项隐藏守护进程，每 5 秒检查两个端口，任一后端退出会自动重新拉起。`output/watchdog.json` 是实时心跳，`reuseOldHomeModel` 固定为 `false`。每次重新双击一键启动器都会删除旧导航地图和旧彩色 PLY，从空家庭模型开始；同一次采集中的服务自动重启不会主动删除本轮数据。

需要停止时运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\color_3d\stop_backends.ps1
```

彩色服务每融合 8 帧向手机发送一次简化网格，每 24 帧覆盖导出 `latest_colored_mesh.ply`。RGB 与 ARCore 帧时间差超过 120 毫秒时会丢弃该帧，避免颜色贴到错误表面。

家庭扫描时，彩色服务每 24 个已融合帧最多保存一个严格同帧的 RGB/Depth/Confidence 样本，每个模型最多 12 个，位置为 `models/<model-id>/semantic_samples`。点击“结束扫描”后，服务自动调用本机 DFormerv2 Small NYUv2 权重，在 `models/<model-id>/semantics/dformerv2` 生成原图、深度图、语义图、叠加图和 `summary.json`；处理状态写入 manifest 的 `semanticSegmentationStatus`。这些文件用于人工判断模型是否适配当前手机和家庭，当前不会参与安全导航。

补扫分段结束后先执行保守重定位。只有 `relocalization.json` 同时满足多组独立帧一致、深度表面重叠且 `mergeAllowed=true`，才自动在父模型的 `versions/merge_<segment-id>/` 生成不可变融合候选。父模型和子模型原文件不会覆盖；合并版本包含彩色网格、变换后的用户地点和合并关键帧。父 manifest 记录活动候选路径，碰撞认知固定标记为 `NEEDS_REBUILD_AFTER_MERGE`、`safeForNavigation=false`，在重新生成并验证导航认知前不得用于行走指令。

## 手机连接

调试启动参数为 `online3dHost=<电脑 WLAN IPv4>`、`online3dPort=38400`、`color3dPort=38401`。进入软件默认仍是户外即时模式；只有点击“采集家庭路线”才上传彩色 RGB-D 并显示彩色模型。

## 当前边界

- 同一 ARCore 会话内可以累计彩色表面，尚未实现跨会话视觉重定位，因此重启扫描后不能直接把不同坐标原点的模型拼接。
- 手机当前是 Canvas 软件绘制并限制约 10,000 个三角面，已经比灰色网格易辨认；全屏 OpenGL 查看器、固定俯视/第一人称视角、门洞与房间标签仍是后续项。
- 必须继续从多个方向扫描墙、地面、门框与家具；未观测区域保持空缺，不进行会影响导航的猜测补面。
