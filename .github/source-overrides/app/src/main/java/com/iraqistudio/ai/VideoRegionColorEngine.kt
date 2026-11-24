package com.iraqistudio.ai

import android.graphics.Bitmap
import android.graphics.Color

/** Four independent semantic regions exposed by the video color studio. */
enum class VideoColorRegion { FACE, SKY, CLOTHING, BACKGROUND }

data class RegionColorSettings(
    val hueDegrees: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val warmth: Float = 0f
) {
    fun sanitized() = copy(
        hueDegrees = hueDegrees.coerceIn(-180f, 180f),
        saturation = saturation.coerceIn(0f, 2f),
        brightness = brightness.coerceIn(-0.5f, 0.5f),
        contrast = contrast.coerceIn(0.5f, 1.8f),
        warmth = warmth.coerceIn(-1f, 1f)
    )
}

/**
 * Applies color only to the requested semantic region of one decoded video frame.
 * Call once per decoded frame, supplying masks produced for that same timestamp.
 *
 * Media3's standard color effects are frame-global; this processor is deliberately
 * separate so callers cannot accidentally apply region controls to the whole clip.
 */
object VideoRegionColorEngine {
    fun apply(
        source: Bitmap,
        personMask: SegmentationMasks,
        skyMask: SegmentationMasks,
        region: VideoColorRegion,
        settings: RegionColorSettings
    ): Bitmap {
        require(!source.isRecycled) { "إطار الفيديو غير صالح" }
        require(personMask.width > 0 && personMask.height > 0)
        require(skyMask.width > 0 && skyMask.height > 0)

        val config = settings.sanitized()
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val hsv = FloatArray(3)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val personClass = personMask.categoryAt(x, y, width, height)
                val isSky = skyMask.categoryAt(x, y, width, height) == 1 && personClass == 0
                val selected = when (region) {
                    VideoColorRegion.FACE -> personClass == 2 || personClass == 3
                    VideoColorRegion.SKY -> isSky
                    VideoColorRegion.CLOTHING -> personClass == 4
                    // Exclude the sky from background so the two controls stay independent.
                    VideoColorRegion.BACKGROUND -> personClass == 0 && !isSky
                }
                if (!selected) continue

                val original = pixels[index]
                val alpha = Color.alpha(original)
                val contrast = config.contrast
                val brightness = config.brightness * 255f
                val red = (((Color.red(original) - 128f) * contrast) + 128f + brightness + config.warmth * 30f).toInt().coerceIn(0, 255)
                val green = (((Color.green(original) - 128f) * contrast) + 128f + brightness).toInt().coerceIn(0, 255)
                val blue = (((Color.blue(original) - 128f) * contrast) + 128f + brightness - config.warmth * 30f).toInt().coerceIn(0, 255)
                val toned = Color.argb(alpha, red, green, blue)
                Color.colorToHSV(toned, hsv)
                hsv[0] = (hsv[0] + config.hueDegrees + 360f) % 360f
                hsv[1] = (hsv[1] * config.saturation).coerceIn(0f, 1f)
                pixels[index] = Color.HSVToColor(alpha, hsv)
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Applies all four independently configured regions in one full-resolution
     * pass. This is used by the video export processor for every decoded frame.
     */
    fun applyAll(
        source: Bitmap,
        personMask: SegmentationMasks,
        skyMask: SegmentationMasks,
        settings: Map<VideoColorRegion, RegionColorSettings>
    ): Bitmap {
        require(!source.isRecycled) { "إطار الفيديو غير صالح" }
        require(personMask.width > 0 && personMask.height > 0)
        require(personMask.labels.size == personMask.width * personMask.height)
        require(skyMask.width > 0 && skyMask.height > 0)
        require(skyMask.labels.size == skyMask.width * skyMask.height)

        val configs = VideoColorRegion.values().associateWith {
            (settings[it] ?: RegionColorSettings()).sanitized()
        }
        if (configs.values.all { it.isNeutral() }) {
            return source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Map each destination coordinate once. The previous implementation
        // performed two 64-bit divisions for every pixel in every video frame.
        val maskX = IntArray(width) { x ->
            (x.toLong() * personMask.width / width).toInt().coerceIn(0, personMask.width - 1)
        }
        val personY = IntArray(height) { y ->
            (y.toLong() * personMask.height / height).toInt().coerceIn(0, personMask.height - 1)
        }
        val skyX = IntArray(width) { x ->
            (x.toLong() * skyMask.width / width).toInt().coerceIn(0, skyMask.width - 1)
        }
        val skyY = IntArray(height) { y ->
            (y.toLong() * skyMask.height / height).toInt().coerceIn(0, skyMask.height - 1)
        }

        val face = configs.getValue(VideoColorRegion.FACE)
        val sky = configs.getValue(VideoColorRegion.SKY)
        val clothing = configs.getValue(VideoColorRegion.CLOTHING)
        val background = configs.getValue(VideoColorRegion.BACKGROUND)
        val hsv = FloatArray(3)

        for (y in 0 until height) {
            val personRow = personY[y] * personMask.width
            val skyRow = skyY[y] * skyMask.width
            val pixelRow = y * width
            for (x in 0 until width) {
                val personClass = personMask.labels[personRow + maskX[x]].toInt() and 0xff
                val isSky = (skyMask.labels[skyRow + skyX[x]].toInt() and 0xff) == 1 && personClass == 0
                val config = when {
                    personClass == 2 || personClass == 3 -> face
                    personClass == 4 -> clothing
                    isSky -> sky
                    personClass == 0 -> background
                    else -> continue
                }
                if (config.isNeutral()) continue

                val index = pixelRow + x
                val original = pixels[index]
                val alpha = Color.alpha(original)
                val brightness = config.brightness * 255f
                val red = (((Color.red(original) - 128f) * config.contrast) + 128f + brightness + config.warmth * 30f).toInt().coerceIn(0, 255)
                val green = (((Color.green(original) - 128f) * config.contrast) + 128f + brightness).toInt().coerceIn(0, 255)
                val blue = (((Color.blue(original) - 128f) * config.contrast) + 128f + brightness - config.warmth * 30f).toInt().coerceIn(0, 255)
                val toned = Color.argb(alpha, red, green, blue)

                // Brightness/contrast/warmth-only edits do not need an HSV
                // round trip. Keep HSV conversion exclusively for hue/saturation.
                if (config.hueDegrees != 0f || config.saturation != 1f) {
                    Color.colorToHSV(toned, hsv)
                    hsv[0] = (hsv[0] + config.hueDegrees + 360f) % 360f
                    hsv[1] = (hsv[1] * config.saturation).coerceIn(0f, 1f)
                    pixels[index] = Color.HSVToColor(alpha, hsv)
                } else {
                    pixels[index] = toned
                }
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

}
