package com.iraqistudio.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPortraitEngineInstrumentedTest {
    @Test
    fun videoModeSegmenterProcessesTimestampedFrameAndBuildsPortraitFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val decoded = instrumentation.context.assets.open("ade20k_sky_test.jpg").use {
            BitmapFactory.decodeStream(it)
        } ?: error("تعذر تحميل صورة الاختبار")

        // Keep this instrumentation smoke test deliberately small. The model
        // itself still receives a real frame, while the pixel-compositing test
        // avoids spending minutes processing a large photographic fixture on
        // the software-rendered Android 10 CI emulator.
        val source = Bitmap.createScaledBitmap(decoded, 256, 256, true)
        if (source !== decoded) decoded.recycle()

        try {
            VideoPersonSegmenter(appContext).use { segmenter ->
                val mask = segmenter.segmentFrame(source, 1000L)
                assertTrue(mask.width > 0)
                assertTrue(mask.height > 0)
                assertEquals(mask.width * mask.height, mask.labels.size)

                val output = makePortraitBlurFrame(source, mask, 12)
                try {
                    assertEquals(source.width, output.width)
                    assertEquals(source.height, output.height)
                    assertEquals(Bitmap.Config.ARGB_8888, output.config)
                } finally {
                    output.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }
}
