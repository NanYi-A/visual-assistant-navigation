# 设计文档索引

本目录收录的是**实际落地的技术方案与调研结论**，不是产品需求文档。每份文档都对应代码里的一组真实实现，可作为"为什么这么设计"的追溯依据。

> 这些文档描述的是**方案与决策过程**，当前实现的权威说明请看仓库根目录的 [README](../README.md) 与源码注释。

---

## 按主题分组

### 感知与融合

| 文档 | 内容 |
|---|---|
| [SEMANTIC_DEPTH_FUSION_PLAN.md](SEMANTIC_DEPTH_FUSION_PLAN.md) | 语义（YOLO 检测框）与深度（ARCore Raw Depth）的对象级融合改造方案 —— 旋转补偿、框内取交集、中位数定位、分位距作可信度判据 |
| [ARCORE_25D_GRID_PLAN.md](ARCORE_25D_GRID_PLAN.md) | ARCore 深度转 2.5D 可通行栅格的试验方案 —— 绿色为已观测可走区、红色为障碍及其安全膨胀区 |

### 地图构建与重定位

| 文档 | 内容 |
|---|---|
| [HOME_NAVIGATION_PLAN.md](HOME_NAVIGATION_PLAN.md) | 家庭地图与室内导航的总体项目规范（体素地图、占格策略、目的地语义） |
| [SEMI_AUTONOMOUS_HOME_MAP_PLAN.md](SEMI_AUTONOMOUS_HOME_MAP_PLAN.md) | 半自动家庭地图与目的地导航方案 —— 如何让用户用最少的操作建立可用地图 |
| [HOME_RELOCALIZATION_V2.md](HOME_RELOCALIZATION_V2.md) | 重定位 V2 方案（SuperPoint + LightGlue 特征匹配） —— 手机在陌生视角下如何认出自己的家 |
| [HOME_MODEL_LIBRARY_PLAN.md](HOME_MODEL_LIBRARY_PLAN.md) | 家庭模型库与认知模型实施方案 |
| [CAPTURE_HEALTH_REPORT.md](CAPTURE_HEALTH_REPORT.md) | 家庭扫描数据体检报告 —— 对实际采集数据的质量分析（漂移、稀疏、噪声） |

### 路径规划与可靠性

| 文档 | 内容 |
|---|---|
| [LOCAL_NAVIGATION_PLAN.md](LOCAL_NAVIGATION_PLAN.md) | 局部持久栅格与安全引导方案 —— 只走"已确认可通行"的格子，未知区域宁可不说 |
| [CSM_AND_ROUTE_RELIABILITY_PLAN.md](CSM_AND_ROUTE_RELIABILITY_PLAN.md) | 路线可靠性与 CSM 几何重定位改造方案 —— 路径平滑、转向指令正确性、失败原因分类 |
| [HOME_NAVIGATION_RELIABILITY_SPRINT.md](HOME_NAVIGATION_RELIABILITY_SPRINT.md) | 家庭室内导航可靠性冲刺 —— 针对"路径走不通 / 转向说错"的一轮专项改进 |

### 在线三维后端

| 文档 | 内容 |
|---|---|
| [ONLINE_3D_NAVIGATION_V3.md](ONLINE_3D_NAVIGATION_V3.md) | 在线三维环境理解与闭环导航 V3 —— 手机端与 PC 端如何分工（自研 VA3D 协议） |
| [LIGHTWEIGHT_3D_BACKEND.md](LIGHTWEIGHT_3D_BACKEND.md) | 轻量三维导航后端方案 —— 为什么不用 RTAB-Map 这类重型方案 |
| [RECONSTRUCTION_OPTIONS_SURVEY.md](RECONSTRUCTION_OPTIONS_SURVEY.md) | 家庭三维重建方案调研与选型建议 —— 对比多种方案的取舍依据 |
| [LOCAL_SPATIAL_INTELLIGENCE_V2.md](LOCAL_SPATIAL_INTELLIGENCE_V2.md) | 纯本地空间智能 V2 方案 —— 不依赖在线后端的本地实现路径 |
| [NAVIGATION_COGNITION_PLAN.md](NAVIGATION_COGNITION_PLAN.md) | 导航认知层实施方案 —— 在几何之上加一层"语义理解" |

---

## 运行时截图

`images/` 目录是应用真实运行截图（已做隐私脱敏：遮挡了真实 IP 与房间名）。

| 文件 | 内容 |
|---|---|
| `01-main-screen.jpg` | 实时取景 + YOLO 检测框 + 风险播报文案（"危险，请停下，前方近处有鼠标…"） |
| `02-route-mapping.jpg` | 家庭路线建图：深度点云叠加可通行栅格（绿色）/ 障碍（红色），右侧为实时检测小窗 |
| `03-voxel-occupancy.jpg` | 空间占据视图：75,997 个空间单元的体素地图，含用户确认的地点标注 |
| `04-raw-scan.jpg` | 原始扫描模型（ARCore 位姿直接融合，未做离线重定位）与优化后模型对比 |
| `05-outdoor-scan.jpg` | 户外楼梯场景扫描：可通行格统计 + 两侧表面距离播报 |

---

## 为什么这些文档值得公开

一般的学生项目只交代码，而这里每个模块背后都有**方案对比、失败分析和验证记录**。比如：

- 深度融合为什么用"分位距"而不是"标准差"判断可信度 —— 见 `SEMANTIC_DEPTH_FUSION_PLAN.md`
- 路径平滑为什么必须"处处已确认可通行"才保留 —— 见 `CSM_AND_ROUTE_RELIABILITY_PLAN.md`
- 为什么放弃 RTAB-Map 自建轻量后端 —— 见 `RECONSTRUCTION_OPTIONS_SURVEY.md`

这些是**工程判断力**的直接证据，比任何形容词都有说服力。
