package cn.visualassistant.app.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxTest {
    @Test fun `landscape image is vertically padded and restored`() {
        val transform = LetterboxTransform(1280, 720)
        assertEquals(0.5f, transform.scale, 0.0001f)
        assertEquals(140f, transform.padY, 0.001f)
        val restored = transform.restore(160f, 230f, 480f, 410f)
        assertEquals(.25f, restored.left, .001f)
        assertEquals(.25f, restored.top, .001f)
        assertEquals(.75f, restored.right, .001f)
        assertEquals(.75f, restored.bottom, .001f)
    }
}

