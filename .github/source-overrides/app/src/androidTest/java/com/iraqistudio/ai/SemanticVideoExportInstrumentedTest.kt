package com.iraqistudio.ai

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class SemanticVideoExportInstrumentedTest {
    @Test
    fun semanticRegionEffectsAreRenderedIntoAnExportedMp4() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "semantic-export-input.mp4")
        val output = File(context.cacheDir, "semantic-export-output.mp4")
        instrumentation.context.assets.open("semantic_export_test.mp4").use { source ->
            input.outputStream().use { destination -> source.copyTo(destination) }
        }
        output.delete()

        val settings = mapOf(
            VideoColorRegion.SKY to RegionColorSettings(brightness = 0.35f),
            VideoColorRegion.BACKGROUND to RegionColorSettings(brightness = 0.35f)
        )
        val effects = buildVideoEffectsWithSemanticRegions(
            context = context,
            preset = "طبيعي",
            brightness = 0f,
            saturation = 0f,
            blurSigma = 0f,
            regionSettings = settings
        )
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val completed = CountDownLatch(1)
        var exportFailure: Throwable? = null
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completed.countDown()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    exportFailure = exportException
                    completed.countDown()
                }
            })
            .build()

        try {
            transformer.start(editedItem, output.absolutePath)
            assertTrue("Semantic video export timed out", completed.await(8, TimeUnit.MINUTES))
            assertNotNull("Transformer failed to export semantic video frames", output)
            exportFailure?.let { throw AssertionError("Semantic MP4 export failed", it) }
            assertTrue("Exported MP4 is missing or empty", output.exists() && output.length() > 4096L)

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val outputFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertTrue("Exported video has no readable duration", duration >= 500L)
                assertNotNull("Exported video has no decodable frame", outputFrame)

                val inputRetriever = MediaMetadataRetriever()
                try {
                    inputRetriever.setDataSource(input.absolutePath)
                    val inputFrame = inputRetriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    assertNotNull("Input fixture has no decodable frame", inputFrame)
                    if (inputFrame != null && outputFrame != null) {
                        val inputLuma = meanLuma(inputFrame)
                        val outputLuma = meanLuma(outputFrame)
                        assertTrue(
                            "Semantic brightness did not reach the exported MP4 (input=$inputLuma, output=$outputLuma)",
                            outputLuma > inputLuma + 12.0
                        )
                    }
                    inputFrame?.recycle()
                } finally {
                    inputRetriever.release()
                    outputFrame?.recycle()
                }
            } finally {
                retriever.release()
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun meanLuma(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.averageOf { pixel ->
            0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
        }
    }
}
