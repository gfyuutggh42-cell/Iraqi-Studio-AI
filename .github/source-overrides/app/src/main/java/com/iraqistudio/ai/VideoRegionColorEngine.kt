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
                    VideoColorRegion.FACE -> personClass == 3
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
        val configs = VideoColorRegion.values().associateWith {
            (settings[it] ?: RegionColorSettings()).sanitized()
        }
        if (configs.values.all {
                it.hueDegrees == 0f && it.saturation == 1f &&
                    it.brightness == 0f && it.contrast == 1f && it.warmth == 0f
            }) return source.copy(Bitmap.Config.ARGB_8888, true)

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
                val region = when {
                    personClass == 3 -> VideoColorRegion.FACE
                    personClass == 4 -> VideoColorRegion.CLOTHING
                    isSky -> VideoColorRegion.SKY
                    personClass == 0 -> VideoColorRegion.BACKGROUND
                    else -> null
                } ?: continue
                val config = configs.getValue(region)
                if (config.hueDegrees == 0f && config.saturation == 1f &&
                    config.brightness == 0f && config.contrast == 1f && config.warmth == 0f
                ) continue

                val original = pixels[index]
                val alpha = Color.alpha(original)
                val brightness = config.brightness * 255f
                val red = (((Color.red(original) - 128f) * config.contrast) + 128f + brightness + config.warmth * 30f).toInt().coerceIn(0, 255)
                val green = (((Color.green(original) - 128f) * config.contrast) + 128f + brightness).toInt().coerceIn(0, 255)
                val blue = (((Color.blue(original) - 128f) * config.contrast) + 128f + brightness - config.warmth * 30f).toInt().coerceIn(0, 255)
                Color.colorToHSV(Color.argb(alpha, red, green, blue), hsv)
                hsv[0] = (hsv[0] + config.hueDegrees + 360f) % 360f
                hsv[1] = (hsv[1] * config.saturation).coerceIn(0f, 1f)
                pixels[index] = Color.HSVToColor(alpha, hsv)
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

}
