package com.iraqistudio.ai

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkySegmentationInstrumentedTest {
    @Test
    fun detectsSkyOnAndroidRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = context.assets.open("ade20k_sky_test.jpg").use {
            BitmapFactory.decodeStream(it)
        } ?: error("تعذر تحميل صورة اختبار السماء")

        val mask = segmentSky(context, bitmap)
        val skyPixels = mask.labels.count { (it.toInt() and 0xff) == 1 }
        bitmap.recycle()

        assertTrue(
            "Android TFLite sky model did not identify enough sky pixels: $skyPixels",
            skyPixels > 1000
        )
    }
}
