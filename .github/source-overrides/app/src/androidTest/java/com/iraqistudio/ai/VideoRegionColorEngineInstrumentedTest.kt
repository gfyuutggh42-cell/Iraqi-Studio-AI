package com.iraqistudio.ai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoRegionColorEngineInstrumentedTest {
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

    @Test
    fun applyAllKeepsFourIndependentRegionSettings() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val base = Color.rgb(90, 110, 130)
        source.setPixels(intArrayOf(base, base, base, base), 0, 2, 0, 0, 2, 2)
        val person = SegmentationMasks(2, 2, byteArrayOf(3, 0, 4, 0))
        val sky = SegmentationMasks(2, 2, byteArrayOf(0, 1, 0, 0))
        try {
            val faceOnly = VideoRegionColorEngine.applyAll(
                source, person, sky,
                mapOf(VideoColorRegion.FACE to RegionColorSettings(brightness = 0.2f))
            )
            try {
                val pixels = IntArray(4)
                faceOnly.getPixels(pixels, 0, 2, 0, 0, 2, 2)
                assertNotEquals(base, pixels[0])
                assertEquals(base, pixels[1])
                assertEquals(base, pixels[2])
                assertEquals(base, pixels[3])
            } finally {
                faceOnly.recycle()
            }

            val allRegions = VideoRegionColorEngine.applyAll(
                source, person, sky,
                VideoColorRegion.values().associateWith { RegionColorSettings(brightness = 0.2f) }
            )
            try {
                val pixels = IntArray(4)
                allRegions.getPixels(pixels, 0, 2, 0, 0, 2, 2)
                for (index in pixels.indices) assertNotEquals("region pixel $index should be graded", base, pixels[index])
            } finally {
                allRegions.recycle()
            }
        } finally {
            source.recycle()
        }
    }

}
