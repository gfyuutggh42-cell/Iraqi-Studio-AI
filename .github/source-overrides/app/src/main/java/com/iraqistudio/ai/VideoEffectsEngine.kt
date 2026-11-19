package com.iraqistudio.ai

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbFilter

/** GPU video effects shared by ExoPlayer preview and Transformer export.
 * Blur intentionally affects the complete frame, not a person-only mask.
 */
@UnstableApi
fun buildVideoEffects(preset: String, brightness: Float, saturation: Float, blurSigma: Float): List<Effect> {
    val effects = mutableListOf<Effect>()
    when (preset) {
        "سينمائي" -> {
            effects += Contrast(1.12f)
            effects += HslAdjustment.Builder().adjustSaturation(12f).adjustLightness(-2f).build()
        }
        "أبيض وأسود" -> effects += RgbFilter.createGrayscaleFilter()
        "سيبيا" -> effects += RgbFilter.createSepiaFilter()
        "معكوس" -> effects += RgbFilter.createInvertedFilter()
    }
    if (brightness != 0f) effects += Brightness(brightness.coerceIn(-1f, 1f))
    if (saturation != 0f) effects += HslAdjustment.Builder().adjustSaturation(saturation.coerceIn(-100f, 100f)).build()
    if (blurSigma > 0f) effects += GaussianBlur(blurSigma.coerceIn(0.1f, 12f))
    return effects
}
