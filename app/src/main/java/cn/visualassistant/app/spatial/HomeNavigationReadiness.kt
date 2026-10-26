package cn.visualassistant.app.spatial

/** A saved scan/map is not automatically a walkable, human-clearance-checked navigation map. */
data class HomeNavigationReadiness(val ready: Boolean, val message: String)

object HomeNavigationReadinessGate {
    fun evaluate(
        hasMapCells: Boolean,
        backendValidatedNavigationGraph: Boolean,
        verifiedGraphConsumedByPlanner: Boolean = false,
        supervisedTestMode: Boolean = false,
        liveLocalizedPose: Boolean = false
    ): HomeNavigationReadiness = when {
        !hasMapCells -> HomeNavigationReadiness(false, "家庭地图尚未生成")
        supervisedTestMode && liveLocalizedPose -> HomeNavigationReadiness(
            true,
            "陪同实验导航已启用：路线只是候选，必须由陪同者确认安全"
        )
        !backendValidatedNavigationGraph || !verifiedGraphConsumedByPlanner -> HomeNavigationReadiness(
            false,
            "扫描地图已保存，但安全通行图尚未完成验证并接入路线规划；目前不能提供行走路线"
        )
        else -> HomeNavigationReadiness(true, "导航图已通过后端验证")
    }
}
