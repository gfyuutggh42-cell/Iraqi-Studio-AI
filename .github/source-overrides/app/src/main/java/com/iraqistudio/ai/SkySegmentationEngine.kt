package com.iraqistudio.ai

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADE20K DeepLabV3 MobileNetV2 semantic segmentation.
 * Verified model contract in CI:
 * input [1, 512, 512, 3] FLOAT32 RGB normalized to [0,1]
 * output [1, 512, 512] INT32 class ids (ArgMax).
 * ADE20K zero-based class id 2 is sky.
 */
fun segmentSky(context: Context, bitmap: Bitmap): SegmentationMasks {
    val modelBuffer = context.assets.openFd("deeplabv3_ade20k.tflite").use { afd ->
        java.io.FileInputStream(afd.fileDescriptor).channel.use { channel ->
            channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }
    val interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(2))
    try {
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputShape.contentEquals(intArrayOf(1, 512, 512, 3))) {
            "أبعاد إدخال نموذج السماء غير مطابقة للنموذج المعتمد"
        }
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "نوع إدخال نموذج السماء غير مدعوم"
        }

        // The verified MLCommons model expects a 512x512 RGB float tensor.
        // The published MLCommons test harness divides RGB pixel values by 255.
        // Match that exact preprocessing contract.
        val modelBitmap = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        val pixels = IntArray(512 * 512)
        modelBitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512)
        if (modelBitmap !== bitmap) modelBitmap.recycle()

        val input = ByteBuffer.allocateDirect(inputTensor.numBytes())
            .order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            input.putFloat(((pixel shr 16) and 255) / 255f)
            input.putFloat(((pixel shr 8) and 255) / 255f)
            input.putFloat((pixel and 255) / 255f)
        }
        input.rewind()

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        require(outputShape.contentEquals(intArrayOf(1, 512, 512))) {
            "أبعاد ناتج نموذج السماء غير مطابقة للنموذج المعتمد"
        }
        require(outputTensor.dataType() == DataType.INT32) {
            "نوع ناتج نموذج السماء غير مدعوم"
        }

        val output = ByteBuffer.allocateDirect(outputTensor.numBytes())
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()

        val labels = ByteArray(512 * 512)
        for (index in labels.indices) {
            labels[index] = if (output.int == SKY_CLASS_ID) 1 else 0
        }

        // Light 3x3 cleanup: remove isolated false positives and fill only
        // nearly-complete sky neighborhoods, preserving most thin boundaries.
        val cleaned = labels.copyOf()
        for (y in 1 until 511) {
            for (x in 1 until 511) {
                var skyNeighbors = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (labels[(y + dy) * 512 + x + dx].toInt() == 1) skyNeighbors++
                }
                val index = y * 512 + x
                if (skyNeighbors <= 1) cleaned[index] = 0
                else if (skyNeighbors >= 8) cleaned[index] = 1
            }
        }
        return SegmentationMasks(512, 512, cleaned)
    } finally {
        interpreter.close()
    }
}

private const val SKY_CLASS_ID = 2
