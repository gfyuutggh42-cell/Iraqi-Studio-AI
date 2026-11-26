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
 * Stateful VIDEO-mode segmentation. Keep one instance for the complete clip;
 * timestamps must be strictly increasing and expressed in milliseconds.
 */
class VideoPersonSegmenter(context: Context) : PersonVideoFrameSegmenter {
    private val segmenter: ImageSegmenter
    private var lastTimestampMs = -1L

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
    override fun segmentFrame(frame: Bitmap, timestampMs: Long): SegmentationMasks {
        require(!frame.isRecycled) { "الإطار غير صالح" }
        require(timestampMs > lastTimestampMs) { "يجب أن تكون أوقات الإطارات متزايدة" }
        val result = segmenter.segmentForVideo(BitmapImageBuilder(frame).build(), timestampMs)
        val maskImage = result.categoryMask().orElseThrow {
            IllegalStateException("لم يُرجع نموذج الأشخاص قناع الفصل")
        }
        val buffer = ByteBufferExtractor.extract(maskImage).duplicate()
        val labels = ByteArray(buffer.remaining())
        buffer.get(labels)
        lastTimestampMs = timestampMs
        return SegmentationMasks(maskImage.width, maskImage.height, labels)
    }

    override fun close() = segmenter.close()
}

/**
 * Portrait background blur for one decoded frame.
 *
 * This is a frame-level image operation only. A video decoder/encoder/muxer
 * must call it for every frame and preserve timestamps/audio before it can be
 * advertised as a complete video export feature.
 */
fun makePortraitBlurFrame(
    source: Bitmap,
    personMask: SegmentationMasks,
    blurStrength: Int = 18
): Bitmap {
    require(!source.isRecycled) { "الصورة المصدر غير صالحة" }
    require(blurStrength in 2..40) { "قوة التمويه يجب أن تكون بين 2 و40" }

    val width = source.width
    val height = source.height
    val count = width * height

    // Precompute the segmentation once. Avoid repeated model-mask lookups in
    // the compositing loop, which were a major cost on Android 10 devices.
    val foreground = ByteArray(count)
    for (y in 0 until height) {
        for (x in 0 until width) {
            foreground[y * width + x] =
                if (personMask.categoryAt(x, y, width, height) != 0) 1 else 0
        }
    }

    // Multi-scale down/up sampling gives a soft, inexpensive background blur.
    // Keep the reduction moderate to avoid the blocky/pixelated look of a
    // single extreme downscale.
    val reduction = (blurStrength / 3).coerceIn(2, 10)
    val smallWidth = maxOf(1, width / reduction)
    val smallHeight = maxOf(1, height / reduction)
    val reduced = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
    val blurred = Bitmap.createScaledBitmap(reduced, width, height, true)
    if (reduced !== source && reduced !== blurred) reduced.recycle()

    val originalPixels = IntArray(count)
    val blurredPixels = IntArray(count)
    source.getPixels(originalPixels, 0, width, 0, 0, width, height)
    blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)

    // Smooth the binary edge with a 3x3 neighborhood. The person core remains
    // sharp while uncertain boundary pixels blend naturally into the blur.
    val output = IntArray(count)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            var covered = 0
            for (dy in -1..1) {
                val ny = (y + dy).coerceIn(0, height - 1)
                for (dx in -1..1) {
                    val nx = (x + dx).coerceIn(0, width - 1)
                    covered += foreground[ny * width + nx].toInt()
                }
            }
            val centerIsPerson = foreground[index].toInt() == 1
            val alpha = when {
                centerIsPerson && covered >= 7 -> 1f
                centerIsPerson -> 0.82f
                covered >= 7 -> 0.18f
                else -> 0f
            }

            val front = originalPixels[index]
            val back = blurredPixels[index]
            val inverse = 1f - alpha
            output[index] = Color.argb(
                Color.alpha(front),
                (Color.red(front) * alpha + Color.red(back) * inverse).toInt().coerceIn(0, 255),
                (Color.green(front) * alpha + Color.green(back) * inverse).toInt().coerceIn(0, 255),
                (Color.blue(front) * alpha + Color.blue(back) * inverse).toInt().coerceIn(0, 255)
            )
        }
    }

    blurred.recycle()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(output, 0, width, 0, 0, width, height)
    }
}
