package com.iraqistudio.ai

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoRegionColorEngineTest {
    @Test
    fun eachRegionChangesOnlyItsOwnPixels() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val base = Color.rgb(90, 110, 130)
        source.setPixels(intArrayOf(base, base, base, base), 0, 2, 0, 0, 2, 2)
        val person = SegmentationMasks(2, 2, byteArrayOf(3, 0, 4, 0))
        val sky = SegmentationMasks(2, 2, byteArrayOf(0, 1, 0, 0))
        val settings = RegionColorSettings(brightness = 0.2f)
        try {
            for ((region, selectedIndex) in listOf(
                VideoColorRegion.FACE to 0,
                VideoColorRegion.SKY to 1,
                VideoColorRegion.CLOTHING to 2,
                VideoColorRegion.BACKGROUND to 3
            )) {
                val output = VideoRegionColorEngine.apply(source, person, sky, region, settings)
                try {
                    val result = IntArray(4)
                    output.getPixels(result, 0, 2, 0, 0, 2, 2)
                    assertNotEquals("selected region must change: $region", base, result[selectedIndex])
                    for (index in result.indices) {
                        if (index != selectedIndex) {
                            assertEquals("other region changed for $region at pixel $index", base, result[index])
                        }
                    }
                } finally {
                    output.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }
}
