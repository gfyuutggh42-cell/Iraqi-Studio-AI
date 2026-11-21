package com.iraqistudio.ai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoRegionColorEngineInstrumentedTest {
    @Test
    fun skyModelIsRequiredOnlyForActiveSkyOrBackgroundControls() {
        assertTrue("sky adjustment must request sky inference", requiresSkySegmentation(
            mapOf(VideoColorRegion.SKY to RegionColorSettings(brightness = 0.1f))
        ))
        assertTrue("background adjustment must request sky inference to exclude sky", requiresSkySegmentation(
            mapOf(VideoColorRegion.BACKGROUND to RegionColorSettings(brightness = 0.1f))
        ))
        assertTrue("combined sky/background adjustment must request sky inference", requiresSkySegmentation(
            mapOf(
                VideoColorRegion.SKY to RegionColorSettings(brightness = 0.1f),
                VideoColorRegion.BACKGROUND to RegionColorSettings(brightness = 0.1f)
            )
        ))
        assertEquals(false, requiresSkySegmentation(
            mapOf(VideoColorRegion.FACE to RegionColorSettings(brightness = 0.1f))
        ))
        assertEquals(false, requiresSkySegmentation(
            mapOf(VideoColorRegion.CLOTHING to RegionColorSettings(saturation = 1.2f))
        ))
        assertEquals(false, requiresSkySegmentation(emptyMap()))
    }

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
    fun portraitAndLandscapeMasksAreCroppedToAspectPreservedFrameArea() {
        val source = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val prepared = prepareSemanticFrame(source)
        try {
            assertEquals(512, prepared.contentWidth)
            assertEquals(288, prepared.contentHeight)
            assertEquals(0, prepared.offsetX)
            assertEquals(112, prepared.offsetY)

            // Simulate the 256x256 MediaPipe category mask. The content rectangle
            // occupies rows 56..199 after the 16:9 image is letterboxed.
            val labels = ByteArray(256 * 256) { index ->
                if (index / 256 in 56..199) 4 else 0
            }
            val cropped = cropSemanticMask(SegmentationMasks(256, 256, labels), prepared)
            assertEquals(256, cropped.width)
            assertEquals(144, cropped.height)
            assertTrue("letterbox pixels must not leak into the mapped mask", cropped.labels.all { it.toInt() == 4 })
        } finally {
            prepared.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun faceRegionIncludesFaceAndExposedBodySkinWithoutChangingOtherRegions() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val base = Color.rgb(90, 110, 130)
        source.eraseColor(base)
        // MediaPipe Selfie Multiclass labels: 2 = body skin, 3 = face skin.
        val person = SegmentationMasks(2, 2, byteArrayOf(3, 2, 4, 0))
        val sky = SegmentationMasks(2, 2, byteArrayOf(0, 0, 0, 1))
        try {
            val output = VideoRegionColorEngine.apply(
                source, person, sky, VideoColorRegion.FACE,
                RegionColorSettings(brightness = 0.2f)
            )
            try {
                val pixels = IntArray(4)
                output.getPixels(pixels, 0, 2, 0, 0, 2, 2)
                assertNotEquals("face skin must change", base, pixels[0])
                assertNotEquals("exposed body skin must change", base, pixels[1])
                assertEquals("clothing must remain untouched", base, pixels[2])
                assertEquals("background/sky must remain untouched", base, pixels[3])
            } finally {
                output.recycle()
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
