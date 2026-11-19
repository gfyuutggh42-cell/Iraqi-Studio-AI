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
        // Preserve the original aspect ratio: fit the image into the model's
        // square input and leave the unused right/bottom area at normalized zero.
        val scale = minOf(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
        val resizedWidth = (bitmap.width * scale).toInt().coerceIn(1, inputWidth)
        val resizedHeight = (bitmap.height * scale).toInt().coerceIn(1, inputHeight)
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val pixels = IntArray(resizedWidth * resizedHeight)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
        if (resized !== bitmap) resized.recycle()

        val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())
        repeat(inputWidth * inputHeight * 3) { input.putFloat(0f) }
        for (y in 0 until resizedHeight) for (x in 0 until resizedWidth) {
            val pixel = pixels[y * resizedWidth + x]
            val offset = (y * inputWidth + x) * 3 * 4
            input.putFloat(offset, (((pixel shr 16) and 255) - 127.5f) / 127.5f)
            input.putFloat(offset + 4, (((pixel shr 8) and 255) - 127.5f) / 127.5f)
            input.putFloat(offset + 8, ((pixel and 255) - 127.5f) / 127.5f)
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
        val squareLabels = ByteArray(maskWidth * maskHeight)
        for (pixelIndex in squareLabels.indices) {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val score = output.float
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }
            squareLabels[pixelIndex] = if (bestClass == SKY_CLASS_ID) 1 else 0
        }
        // Discard the padded portion so the returned mask keeps the source aspect ratio.
        val labels = ByteArray(resizedWidth * resizedHeight)
        for (y in 0 until resizedHeight) {
            System.arraycopy(squareLabels, y * maskWidth, labels, y * resizedWidth, resizedWidth)
        }

        // Remove isolated single-pixel predictions without eroding broad sky areas.
        val cleaned = labels.copyOf()
        for (y in 1 until resizedHeight - 1) {
            for (x in 1 until resizedWidth - 1) {
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (labels[(y + dy) * resizedWidth + x + dx].toInt() == 1) count++
                }
                if (count <= 2) cleaned[y * resizedWidth + x] = 0
            }
        }
        return SegmentationMasks(resizedWidth, resizedHeight, cleaned)
    } finally {
        interpreter.close()
    }
}

private const val SKY_CLASS_ID = 2
