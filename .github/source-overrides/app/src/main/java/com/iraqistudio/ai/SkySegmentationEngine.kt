package com.iraqistudio.ai

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADE20K semantic scene segmentation. ADE20K's zero-based class id 2 is sky.
 * Output is a binary sky mask; person-part masks are used by the editor to
 * protect detected hair, skin, clothing and accessories from sky recoloring.
 */
fun segmentSky(context: Context, bitmap: Bitmap): SegmentationMasks {
    val modelBuffer = context.assets.openFd("deeplabv3_ade20k.tflite").use { afd ->
        java.io.FileInputStream(afd.fileDescriptor).channel.use { channel ->
            channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
    val options = Interpreter.Options().setNumThreads(2)
    val interpreter = Interpreter(modelBuffer, options)
    try {
        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
            "نموذج السماء لا يدعم أبعاد الإدخال المتوقعة"
        }
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "نوع إدخال نموذج السماء غير مدعوم"
        }
        val inputHeight = inputShape[1]
        val inputWidth = inputShape[2]
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (resized !== bitmap) resized.recycle()

        val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            input.putFloat((((pixel shr 16) and 255) - 127.5f) / 127.5f)
            input.putFloat((((pixel shr 8) and 255) - 127.5f) / 127.5f)
            input.putFloat(((pixel and 255) - 127.5f) / 127.5f)
        }
        input.rewind()

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        require(outputShape.size == 4 && outputShape[0] == 1) {
            "شكل ناتج نموذج السماء غير مدعوم"
        }
        val maskHeight = outputShape[1]
        val maskWidth = outputShape[2]
        val classCount = outputShape[3]
        require(classCount > 2) { "النموذج لا يحتوي على فئة السماء" }
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "نوع ناتج نموذج السماء غير مدعوم"
        }

        val output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        val labels = ByteArray(maskWidth * maskHeight)
        val scores = FloatArray(classCount)
        for (pixelIndex in labels.indices) {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            var skyScore = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val score = output.float
                scores[classIndex] = score
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
                if (classIndex == SKY_CLASS_ID) skyScore = score
            }
            // Keep only confident sky pixels; later compositing also protects
            // person-part classes identified by the separate MediaPipe model.
            labels[pixelIndex] = if (bestClass == SKY_CLASS_ID && skyScore >= SKY_MIN_SCORE) 1 else 0
        }

        // Remove isolated single-pixel predictions without eroding broad sky areas.
        val cleaned = labels.copyOf()
        for (y in 1 until maskHeight - 1) {
            for (x in 1 until maskWidth - 1) {
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (labels[(y + dy) * maskWidth + x + dx].toInt() == 1) count++
                }
                if (count <= 2) cleaned[y * maskWidth + x] = 0
            }
        }
        return SegmentationMasks(maskWidth, maskHeight, cleaned)
    } finally {
        interpreter.close()
    }
}

private const val SKY_CLASS_ID = 2
private const val SKY_MIN_SCORE = 0.35f
