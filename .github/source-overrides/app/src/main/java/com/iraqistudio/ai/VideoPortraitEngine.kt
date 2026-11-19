package com.iraqistudio.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

/**
 * Stateful VIDEO-mode person-part segmentation.
 * Keep one instance for the whole clip so MediaPipe can process successive
 * timestamped frames without reloading the model for every frame.
 */
class VideoPersonSegmenter(context: Context) : AutoCloseable {
    private val segmenter: ImageSegmenter

    init {
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_multiclass.tflite").build())
            .setRunningMode(RunningMode.VIDEO)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        segmenter = ImageSegmenter.createFromOptions(context, options)
    }

    @Synchronized
    fun segmentFrame(frame: Bitmap, timestampMs: Long): SegmentationMasks {
        require(timestampMs >= 0L) { "وقت الإطار غير صالح" }
        val result = segmenter.segmentForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        val maskImage = result.categoryMask().orElseThrow {
            IllegalStateException("لم يُرجع نموذج الأشخاص قناع الفصل")
        }
        val buffer = ByteBufferExtractor.extract(maskImage).duplicate()
        val labels = ByteArray(buffer.remaining())
        buffer.get(labels)
        return SegmentationMasks(maskImage.width, maskImage.height, labels)
    }

    override fun close() {
        segmenter.close()
    }
}

/**
 * Applies a portrait-style background blur to one decoded video frame.
 * Non-zero selfie-multiclass labels are kept sharp; only the background is
 * blurred. The caller supplies a fresh segmentation mask for each timestamp.
 */
fun makePortraitBlurFrame(
    source: Bitmap,
    personMask: SegmentationMasks,
    blurStrength: Int = 18
): Bitmap {
    require(blurStrength in 2..40) { "قوة التمويه يجب أن تكون بين 2 و40" }
    val width = source.width
    val height = source.height
    val sample = maxOf(2, blurStrength)
    val smallWidth = maxOf(1, width / sample)
    val smallHeight = maxOf(1, height / sample)
    val reduced = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
    val blurred = Bitmap.createScaledBitmap(reduced, width, height, true)
    if (reduced !== blurred) reduced.recycle()

    val originalPixels = IntArray(width * height)
    val blurredPixels = IntArray(width * height)
    source.getPixels(originalPixels, 0, width, 0, 0, width, height)
    blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)

    // Feather the segmentation boundary slightly to reduce hard cut-out edges.
    val output = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val isPerson = personMask.categoryAt(x, y, width, height) != 0
            var nearbyPerson = 0
            var samples = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx * 2).coerceIn(0, width - 1)
                val ny = (y + dy * 2).coerceIn(0, height - 1)
                if (personMask.categoryAt(nx, ny, width, height) != 0) nearbyPerson++
                samples++
            }
            val alpha = when {
                isPerson && nearbyPerson >= 7 -> 1f
                isPerson -> 0.88f
                nearbyPerson >= 7 -> 0.12f
                else -> 0f
            }
            val foreground = originalPixels[index]
            val background = blurredPixels[index]
            val inverse = 1f - alpha
            val a = Color.alpha(foreground)
            val r = (Color.red(foreground) * alpha + Color.red(background) * inverse).toInt()
            val g = (Color.green(foreground) * alpha + Color.green(background) * inverse).toInt()
            val b = (Color.blue(foreground) * alpha + Color.blue(background) * inverse).toInt()
            output[index] = Color.argb(a, r, g, b)
        }
    }
    blurred.recycle()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(output, 0, width, 0, 0, width, height)
    }
}
