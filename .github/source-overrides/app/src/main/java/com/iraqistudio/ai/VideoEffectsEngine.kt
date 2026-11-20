package com.iraqistudio.ai

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.Presentation

/** GPU video effects shared by ExoPlayer preview and Transformer export.
 * Blur intentionally affects the complete frame, not a person-only mask.
 */
@UnstableApi
fun buildVideoEffects(preset: String, brightness: Float, saturation: Float, blurSigma: Float, outputHeight: Int = 0): List<Effect> {
    val effects = mutableListOf<Effect>()
    when (preset) {
        "سينمائي" -> {
            effects += Contrast(1.12f)
            effects += HslAdjustment.Builder().adjustSaturation(12f).adjustLightness(-2f).build()
        }
        "أبيض وأسود" -> effects += RgbFilter.createGrayscaleFilter()
        "سيبيا" -> {
            // Media3 1.5.1 has no built-in sepia helper; use its supported RGB matrix effect.
            val sepiaMatrix = floatArrayOf(
                0.393f, 0.349f, 0.272f, 0f,
                0.769f, 0.686f, 0.534f, 0f,
                0.189f, 0.168f, 0.131f, 0f,
                0f, 0f, 0f, 1f
            )
            effects += RgbMatrix { _, _ -> sepiaMatrix }
        }
        "معكوس" -> effects += RgbFilter.createInvertedFilter()
    }
    if (brightness != 0f) effects += Brightness(brightness.coerceIn(-1f, 1f))
    if (saturation != 0f) effects += HslAdjustment.Builder().adjustSaturation(saturation.coerceIn(-100f, 100f)).build()
    if (blurSigma > 0f) effects += GaussianBlur(blurSigma.coerceIn(0.1f, 12f))
    if (outputHeight > 0) effects += Presentation.createForShortSide(outputHeight)
    return effects
}
