package com.iraqistudio.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.atomic.AtomicInteger

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
): List<Effect> = buildVideoEffectsWithSemanticRegionsInternal(
    context, preset, brightness, saturation, blurSigma, outputHeight, regionSettings,
    OnDeviceSemanticVideoSegmenterFactory
)

internal interface PersonVideoFrameSegmenter : AutoCloseable {
    fun segmentFrame(frame: Bitmap, timestampMs: Long): SegmentationMasks
}

internal interface SkyVideoFrameSegmenter : AutoCloseable {
    fun segmentFrame(frame: Bitmap): SegmentationMasks
}

internal interface SemanticVideoSegmenterFactory {
    fun createPerson(context: Context): PersonVideoFrameSegmenter
    fun createSky(context: Context): SkyVideoFrameSegmenter
}

private object OnDeviceSemanticVideoSegmenterFactory : SemanticVideoSegmenterFactory {
    override fun createPerson(context: Context): PersonVideoFrameSegmenter = VideoPersonSegmenter(context)
    override fun createSky(context: Context): SkyVideoFrameSegmenter = VideoSkyFrameSegmenter(context)
}

/** Test seam: keeps the production frame processor and Media3 export path intact. */
internal fun buildVideoEffectsWithSemanticRegionsForTest(
    context: Context,
    preset: String,
    brightness: Float,
    saturation: Float,
    blurSigma: Float,
    outputHeight: Int = 0,
    regionSettings: Map<VideoColorRegion, RegionColorSettings>,
    segmenterFactory: SemanticVideoSegmenterFactory
): List<Effect> = buildVideoEffectsWithSemanticRegionsInternal(
    context, preset, brightness, saturation, blurSigma, outputHeight, regionSettings, segmenterFactory
)

private fun buildVideoEffectsWithSemanticRegionsInternal(
    context: Context,
    preset: String,
    brightness: Float,
    saturation: Float,
    blurSigma: Float,
    outputHeight: Int,
    regionSettings: Map<VideoColorRegion, RegionColorSettings>,
    segmenterFactory: SemanticVideoSegmenterFactory
): List<Effect> {
    val effects = buildVideoEffects(preset, brightness, saturation, blurSigma, outputHeight).toMutableList()
    if (regionSettings.values.any { !it.isNeutral() }) {
        effects += ByteBufferGlEffect(
            SemanticRegionFrameProcessor(context.applicationContext, regionSettings, segmenterFactory)
        )
    }
    return effects
}

internal fun RegionColorSettings.isNeutral(): Boolean =
    hueDegrees == 0f && saturation == 1f && brightness == 0f && contrast == 1f && warmth == 0f

/** Sky inference is only needed when sky/background controls are actually active. */
internal fun requiresSkySegmentation(
    settings: Map<VideoColorRegion, RegionColorSettings>
): Boolean = listOf(VideoColorRegion.SKY, VideoColorRegion.BACKGROUND).any { region ->
    !(settings[region] ?: RegionColorSettings()).sanitized().isNeutral()
}

private class SemanticRegionFrameProcessor(
    private val appContext: Context,
    settings: Map<VideoColorRegion, RegionColorSettings>,
    private val segmenterFactory: SemanticVideoSegmenterFactory
) : ByteBufferGlEffect.Processor<Bitmap> {
    private val settings = VideoColorRegion.values().associateWith { (settings[it] ?: RegionColorSettings()).sanitized() }
    private val needsSkyMask = requiresSkySegmentation(this.settings)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "semantic-video-frame-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val released = AtomicBoolean(false)
    private val diagnosticFrameCount = AtomicInteger(0)
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastTimestampMs = -1L
    private var personSegmenter: PersonVideoFrameSegmenter? = null
    private var skySegmenter: SkyVideoFrameSegmenter? = null

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
        val frameNumber = diagnosticFrameCount.incrementAndGet()
        val traceFrame = frameNumber <= 3 || frameNumber % 15 == 0
        val frameStartMs = SystemClock.elapsedRealtime()
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
        if (traceFrame) Log.i("SemanticVideo", "frame=$frameNumber queued size=${image.width}x${image.height} sky=$needsSkyMask")
        worker.execute {
            var analysis: Bitmap? = null
            val workerStartMs = SystemClock.elapsedRealtime()
            try {
                check(!released.get()) { "معالج فلاتر الفيديو أُغلق" }
                val timestampMs = maxOf(presentationTimeUs / 1000L, lastTimestampMs + 1L)
                lastTimestampMs = timestampMs
                if (personSegmenter == null) personSegmenter = segmenterFactory.createPerson(appContext)
                if (needsSkyMask && skySegmenter == null) skySegmenter = segmenterFactory.createSky(appContext)

                // Keep the source aspect ratio. Stretching portrait/landscape clips
                // into a square makes the segmentation models misclassify faces,
                // clothing and sky, then paints those wrong masks onto the full frame.
                val prepareStartMs = SystemClock.elapsedRealtime()
                val prepared = prepareSemanticFrame(frame)
                analysis = prepared.bitmap
                val prepareMs = SystemClock.elapsedRealtime() - prepareStartMs
                val personStartMs = SystemClock.elapsedRealtime()
                val rawPersonMask = personSegmenter!!.segmentFrame(analysis!!, timestampMs)
                val personMs = SystemClock.elapsedRealtime() - personStartMs
                val personMask = cropSemanticMask(rawPersonMask, prepared)
                val skyStartMs = SystemClock.elapsedRealtime()
                val skyMask = if (needsSkyMask) {
                    cropSemanticMask(skySegmenter!!.segmentFrame(analysis!!), prepared)
                } else {
                    // Face/clothing-only edits do not need ADE20K inference.
                    SegmentationMasks(1, 1, byteArrayOf(0))
                }
                val skyMs = SystemClock.elapsedRealtime() - skyStartMs
                val colorStartMs = SystemClock.elapsedRealtime()
                val processed = VideoRegionColorEngine.applyAll(frame, personMask, skyMask, settings)
                val colorMs = SystemClock.elapsedRealtime() - colorStartMs
                if (traceFrame) Log.i("SemanticVideo", "frame=$frameNumber ms queue=${workerStartMs-frameStartMs} prepare=$prepareMs person=$personMs sky=$skyMs color=$colorMs worker=${SystemClock.elapsedRealtime()-workerStartMs}")
                result.set(processed)
            } catch (error: Throwable) {
                Log.e("SemanticVideo", "frame=$frameNumber failed after ${SystemClock.elapsedRealtime()-workerStartMs}ms", error)
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
        val blendStartMs = SystemClock.elapsedRealtime()
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
            val completedFrames = diagnosticFrameCount.get()
            if (completedFrames <= 3 || completedFrames % 15 == 0) Log.i("SemanticVideo", "frame=$completedFrames GL upload=${SystemClock.elapsedRealtime()-blendStartMs}ms")
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

/**
 * Square model input with the original frame fitted (not stretched) into it.
 * The mask is cropped back to the fitted image rectangle before being mapped
 * onto the original frame coordinates.
 */
internal data class PreparedSemanticFrame(
    val bitmap: Bitmap,
    val contentWidth: Int,
    val contentHeight: Int,
    val offsetX: Int,
    val offsetY: Int
)

internal fun prepareSemanticFrame(source: Bitmap): PreparedSemanticFrame {
    val scale = minOf(512f / source.width, 512f / source.height)
    val contentWidth = (source.width * scale).toInt().coerceIn(1, 512)
    val contentHeight = (source.height * scale).toInt().coerceIn(1, 512)
    val offsetX = (512 - contentWidth) / 2
    val offsetY = (512 - contentHeight) / 2
    val fitted = Bitmap.createScaledBitmap(source, contentWidth, contentHeight, true)
    val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(square)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(fitted, offsetX.toFloat(), offsetY.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
    } finally {
        if (fitted !== source && !fitted.isRecycled) fitted.recycle()
    }
    return PreparedSemanticFrame(square, contentWidth, contentHeight, offsetX, offsetY)
}

internal fun cropSemanticMask(mask: SegmentationMasks, frame: PreparedSemanticFrame): SegmentationMasks {
    require(mask.width > 0 && mask.height > 0 && mask.labels.size == mask.width * mask.height) {
        "أبعاد قناع الذكاء الاصطناعي غير متوافقة"
    }
    // MediaPipe's person model emits a 256x256 mask, while ADE20K emits
    // 512x512. Convert the fitted-image rectangle into each mask's own grid.
    val left = (frame.offsetX.toLong() * mask.width / 512L).toInt()
    val top = (frame.offsetY.toLong() * mask.height / 512L).toInt()
    val cropWidth = (frame.contentWidth.toLong() * mask.width / 512L).toInt().coerceAtLeast(1)
    val cropHeight = (frame.contentHeight.toLong() * mask.height / 512L).toInt().coerceAtLeast(1)
    val safeWidth = cropWidth.coerceAtMost(mask.width - left)
    val safeHeight = cropHeight.coerceAtMost(mask.height - top)
    val cropped = ByteArray(safeWidth * safeHeight)
    for (y in 0 until safeHeight) {
        val sourceOffset = (y + top) * mask.width + left
        val targetOffset = y * safeWidth
        mask.labels.copyInto(cropped, targetOffset, sourceOffset, sourceOffset + safeWidth)
    }
    return SegmentationMasks(safeWidth, safeHeight, cropped)
}

/** Reuses one ADE20K interpreter for the complete video instead of reloading it per frame. */
private class VideoSkyFrameSegmenter(context: Context) : SkyVideoFrameSegmenter {
    private val interpreter: Interpreter
    // Reuse frame-sized working memory instead of allocating tensor buffers per frame.
    private val pixels = IntArray(512 * 512)
    private val input: ByteBuffer
    private val output: ByteBuffer

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
        input = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
    }

    override fun segmentFrame(frame: Bitmap): SegmentationMasks {
        val modelFrame = if (frame.width == 512 && frame.height == 512) frame
            else Bitmap.createScaledBitmap(frame, 512, 512, true)
        try {
            modelFrame.getPixels(pixels, 0, 512, 0, 0, 512, 512)
            input.clear()
            for (pixel in pixels) {
                input.putFloat(Color.red(pixel) / 255f)
                input.putFloat(Color.green(pixel) / 255f)
                input.putFloat(Color.blue(pixel) / 255f)
            }
            input.rewind()
            output.clear()
            interpreter.run(input, output)
            output.rewind()
            val labels = ByteArray(512 * 512) { index -> if (output.int == SKY_CLASS_ID) 1.toByte() else 0.toByte() }
            return SegmentationMasks(512, 512, labels)
        } finally {
            if (modelFrame !== frame && !modelFrame.isRecycled) modelFrame.recycle()
        }
    }

    override fun close() = interpreter.close()
}
