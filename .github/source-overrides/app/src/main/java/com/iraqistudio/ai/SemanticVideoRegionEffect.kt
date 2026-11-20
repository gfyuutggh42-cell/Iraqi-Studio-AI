package com.iraqistudio.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.Effect
import androidx.media3.common.util.GlRect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ByteBufferGlEffect
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs semantic masks and region color grading on EVERY video frame delivered
 * by Media3 Transformer / ExoPlayer. The processor works on a bounded 512px
 * analysis image, then applies the masks to the full-resolution frame.
 */
@UnstableApi
fun buildVideoEffectsWithSemanticRegions(
    context: Context,
    preset: String,
    brightness: Float,
    saturation: Float,
    blurSigma: Float,
    outputHeight: Int = 0,
    regionSettings: Map<VideoColorRegion, RegionColorSettings>
): List<Effect> {
    val effects = buildVideoEffects(preset, brightness, saturation, blurSigma, outputHeight)
    if (regionSettings.values.any { !it.isNeutral() }) {
        effects += ByteBufferGlEffect(SemanticRegionFrameProcessor(context.applicationContext, regionSettings))
    }
    return effects
}

private fun RegionColorSettings.isNeutral(): Boolean =
    hueDegrees == 0f && saturation == 1f && brightness == 0f && contrast == 1f && warmth == 0f

private class SemanticRegionFrameProcessor(
    private val appContext: Context,
    settings: Map<VideoColorRegion, RegionColorSettings>
) : ByteBufferGlEffect.Processor<Bitmap> {
    private val settings = VideoColorRegion.values().associateWith { (settings[it] ?: RegionColorSettings()).sanitized() }
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "semantic-video-frame-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val released = AtomicBoolean(false)
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastTimestampMs = -1L
    private var personSegmenter: VideoPersonSegmenter? = null
    private var skySegmenter: VideoSkyFrameSegmenter? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth
        frameHeight = inputHeight
        // Keep the frame at its original resolution for color application.
        // The processor downsizes a copy only for the segmentation models.
        return Size(inputWidth, inputHeight)
    }

    override fun getScaledRegion(presentationTimeUs: Long): GlRect =
        GlRect(frameWidth, frameHeight)

    override fun processImage(
        image: ByteBufferGlEffect.Image,
        presentationTimeUs: Long
    ): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        // Copy before returning: Media3 may recycle the input buffer once this future completes.
        val frame = try {
            // ByteBufferGlEffect stores pixels in GL's bottom-up row order.
            // Convert explicitly and recycle the temporary bitmap each frame.
            val glPixels = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val pixelBuffer = image.pixelBuffer.duplicate()
            pixelBuffer.rewind()
            glPixels.copyPixelsFromBuffer(pixelBuffer)
            val flip = Matrix().apply { setScale(1f, -1f) }
            Bitmap.createBitmap(glPixels, 0, 0, glPixels.width, glPixels.height, flip, true).also {
                glPixels.recycle()
            }
        } catch (error: Throwable) {
            result.setException(error)
            return result
        }
        worker.execute {
            var analysis: Bitmap? = null
            try {
                check(!released.get()) { "معالج فلاتر الفيديو أُغلق" }
                val timestampMs = maxOf(presentationTimeUs / 1000L, lastTimestampMs + 1L)
                lastTimestampMs = timestampMs
                if (personSegmenter == null) personSegmenter = VideoPersonSegmenter(appContext)
                if (skySegmenter == null) skySegmenter = VideoSkyFrameSegmenter(appContext)

                analysis = Bitmap.createScaledBitmap(frame, 512, 512, true)
                val personMask = personSegmenter!!.segmentFrame(analysis!!, timestampMs)
                val skyMask = skySegmenter!!.segmentFrame(analysis!!)
                val processed = VideoRegionColorEngine.applyAll(frame, personMask, skyMask, settings)
                result.set(processed)
            } catch (error: Throwable) {
                result.setException(error)
            } finally {
                if (analysis != null && analysis !== frame && !analysis!!.isRecycled) analysis!!.recycle()
                if (!frame.isRecycled) frame.recycle()
            }
        }
        return result
    }

    override fun finishProcessingAndBlend(
        outputFrame: androidx.media3.common.GlTextureInfo,
        presentationTimeUs: Long,
        result: Bitmap
    ) {
        var upload = result
        try {
            if (result.width != outputFrame.width || result.height != outputFrame.height) {
                upload = Bitmap.createScaledBitmap(result, outputFrame.width, outputFrame.height, true)
            }
            val previous = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, previous, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputFrame.texId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            // Restore GL's bottom-up pixel order before updating the frame texture.
            val flip = Matrix().apply { setScale(1f, -1f) }
            val glUpload = Bitmap.createBitmap(upload, 0, 0, upload.width, upload.height, flip, true)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, glUpload)
            glUpload.recycle()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previous[0])
        } finally {
            if (upload !== result && !upload.isRecycled) upload.recycle()
            if (!result.isRecycled) result.recycle()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        worker.execute {
            try { personSegmenter?.close() } catch (_: Exception) {}
            try { skySegmenter?.close() } catch (_: Exception) {}
            personSegmenter = null
            skySegmenter = null
        }
        worker.shutdown()
    }
}

/** Reuses one ADE20K interpreter for the complete video instead of reloading it per frame. */
private class VideoSkyFrameSegmenter(context: Context) : AutoCloseable {
    private val interpreter: Interpreter

    init {
        val model = context.assets.openFd("deeplabv3_ade20k.tflite").use { afd ->
            java.io.FileInputStream(afd.fileDescriptor).channel.use { channel ->
                channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 512, 512, 3)))
        require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 512, 512)))
        require(interpreter.getOutputTensor(0).dataType() == DataType.INT32)
    }

    fun segmentFrame(frame: Bitmap): SegmentationMasks {
        val modelFrame = if (frame.width == 512 && frame.height == 512) frame
            else Bitmap.createScaledBitmap(frame, 512, 512, true)
        try {
            val pixels = IntArray(512 * 512)
            modelFrame.getPixels(pixels, 0, 512, 0, 0, 512, 512)
            val input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                input.putFloat(Color.red(pixel) / 255f)
                input.putFloat(Color.green(pixel) / 255f)
                input.putFloat(Color.blue(pixel) / 255f)
            }
            input.rewind()
            val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val labels = ByteArray(512 * 512) { index -> if (output.int == 2) 1.toByte() else 0.toByte() }
            return SegmentationMasks(512, 512, labels)
        } finally {
            if (modelFrame !== frame && !modelFrame.isRecycled) modelFrame.recycle()
        }
    }

    override fun close() = interpreter.close()
}
