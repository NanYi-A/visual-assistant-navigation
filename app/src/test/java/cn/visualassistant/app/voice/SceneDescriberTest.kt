package cn.visualassistant.app.voice

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class SceneDescriberTest {
    private fun person(left: Float) = Detection(0, "行人", .9f, NormalizedBox(left, .2f, left + .08f, .7f))
    private fun stable(items: List<Detection>) = SceneDescriber().apply {
        update(items, 100)
        update(items, 300)
    }

    @Test fun countsMultipleInstancesAndDirections() {
        val scene = stable(listOf(person(.40f), person(.53f), person(.8f)))
        assertTrue(scene.answer("前面都有些什么", 400).startsWith("前方有两位行人，右侧有一位行人。"))
    }

    @Test fun answersOnlyRequestedSide() {
        val scene = stable(listOf(person(.1f), person(.8f)))
        assertTrue(scene.answer("左边有什么", 400).startsWith("左侧有一位行人。"))
    }

    @Test fun ignoresNewFalsePositiveAndLowConfidence() {
        val scene = SceneDescriber()
        scene.update(emptyList(), 100)
        scene.update(listOf(person(.4f), person(.8f).copy(confidence = .1f)), 300)
        assertTrue(scene.answer("前面有什么", 400).startsWith("当前画面暂时没有识别到明显目标。暂不能判断避让方向"))
    }

    @Test fun doesNotUseDisappearedObjects() {
        val scene = stable(listOf(person(.4f)))
        scene.update(emptyList(), 500)
        assertTrue(scene.answer("看看前面", 600).startsWith("当前画面暂时没有识别到明显目标。暂不能判断避让方向"))
    }

    @Test fun rejectsStaleScene() {
        val scene = stable(listOf(person(.4f)))
        assertTrue(scene.answer("前面有什么", 3_000).contains("过期"))
    }

    @Test fun oneHistoricObjectCannotConfirmTwoCurrentObjects() {
        val scene = SceneDescriber()
        scene.update(listOf(person(.4f)), 100)
        scene.update(listOf(person(.4f), person(.41f)), 300)
        assertTrue(scene.answer("前面有什么", 400).startsWith("前方有一位行人。"))
    }

    @Test fun explainsUnsupportedQuestionsAndNoFrames() {
        val scene = SceneDescriber()
        assertTrue(scene.answer("天气如何", 100).contains("可以问我"))
        assertTrue(scene.answer("前面有什么", 100).contains("还没有"))
    }

    @Test fun navigationQuestionsAndSideQuestionsUseWholeScene() {
        val obstacle = person(.65f).copy(box = NormalizedBox(.65f, .2f, .98f, .85f))
        val scene = stable(listOf(obstacle))
        for (question in listOf("怎么走", "往哪边走", "怎么避开障碍物", "左边有什么", "前面都有些什么")) {
            assertTrue(question, scene.answer(question, 400).contains("可优先观察左侧"))
        }
    }

    @Test fun staleSceneNeverGivesDirection() {
        val scene = stable(listOf(person(.1f)))
        assertFalse(scene.answer("往哪边走", 3000).contains("可优先观察"))
    }
}
