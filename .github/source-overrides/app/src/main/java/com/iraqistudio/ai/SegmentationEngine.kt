package com.iraqistudio.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter

/**
 * On-device person-part segmentation. Class ids:
 * 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 accessories.
 */
data class SegmentationMasks(val width: Int, val height: Int, val labels: ByteArray) {
    fun categoryAt(x: Int, y: Int, targetWidth: Int, targetHeight: Int): Int {
        val mx = (x.toLong() * width / targetWidth).toInt().coerceIn(0, width - 1)
        val my = (y.toLong() * height / targetHeight).toInt().coerceIn(0, height - 1)
        return labels[my * width + mx].toInt() and 0xff
    }
}

fun segmentPhoto(context: Context, bitmap: Bitmap): SegmentationMasks {
    val base = BaseOptions.builder().setModelAssetPath("selfie_multiclass.tflite").build()
    val options = ImageSegmenter.ImageSegmenterOptions.builder()
        .setBaseOptions(base)
        .setRunningMode(RunningMode.IMAGE)
        .setOutputCategoryMask(true)
        .setOutputConfidenceMasks(false)
        .build()
    val segmenter = ImageSegmenter.createFromOptions(context, options)
    try {
        val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
        val maskImage = result.categoryMask().get()
        val buffer = ByteBufferExtractor.extract(maskImage).duplicate()
        val labels = ByteArray(buffer.remaining())
        buffer.get(labels)
        return SegmentationMasks(maskImage.width, maskImage.height, labels)
    } finally {
        segmenter.close()
    }
}
