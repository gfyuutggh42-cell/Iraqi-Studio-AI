package com.iraqistudio.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ColorStudioPanel = Color(0xFF171F2D)
private val ColorStudioAccent = Color(0xFF66E0C0)

@Composable
fun VideoColorStudioPanel(
    hasVideo: Boolean,
    analyzing: Boolean,
    selectedRegion: VideoColorRegion,
    settings: RegionColorSettings,
    onRegionSelected: (VideoColorRegion) -> Unit,
    onSettingsChanged: (RegionColorSettings) -> Unit,
    onAnalyzeFrame: () -> Unit
) {
    val regions = listOf(
        VideoColorRegion.FACE to ("الوجه والبشرة" to "ضبط لون البشرة ودفئها"),
        VideoColorRegion.SKY to ("السماء" to "تغيير لون السماء وإضاءتها"),
        VideoColorRegion.CLOTHING to ("الملابس" to "تعديل ألوان الملابس وحدها"),
        VideoColorRegion.BACKGROUND to ("الخلفية" to "ألوان الخلفية دون السماء")
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ColorStudioPanel),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("استوديو الألوان الذكي", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("تحكم مستقل بأربع مناطق من الفيديو", color = Color(0xFFB9C5D5), fontSize = 12.sp)
                }
                Text("AI", color = ColorStudioAccent, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                regions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (region, _) ->
                            val active = selectedRegion == region
                            FilterChip(
                                selected = active,
                                onClick = { onRegionSelected(region) },
                                label = { Text(regions.first { it.first == region }.second.first, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            val activeTitle = regions.first { it.first == selectedRegion }.second
            Text(activeTitle.second, color = ColorStudioAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            ColorSlider("تغيير درجة اللون", settings.hueDegrees, -180f..180f, "${settings.hueDegrees.toInt()}°") {
                onSettingsChanged(settings.copy(hueDegrees = it))
            }
            ColorSlider("تشبع اللون", settings.saturation, 0f..2f, "${(settings.saturation * 100).toInt()}%") {
                onSettingsChanged(settings.copy(saturation = it))
            }
            ColorSlider("الإضاءة", settings.brightness, -0.5f..0.5f, "${(settings.brightness * 100).toInt()}%") {
                onSettingsChanged(settings.copy(brightness = it))
            }
            ColorSlider("التباين", settings.contrast, 0.5f..1.8f, "${(settings.contrast * 100).toInt()}%") {
                onSettingsChanged(settings.copy(contrast = it))
            }
            ColorSlider("دفء اللون", settings.warmth, -1f..1f, "${(settings.warmth * 100).toInt()}%") {
                onSettingsChanged(settings.copy(warmth = it))
            }
            Button(
                onClick = onAnalyzeFrame,
                enabled = hasVideo && !analyzing,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorStudioAccent, contentColor = Color(0xFF071510)),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text(if (analyzing) "جارٍ تحليل الإطار..." else "تحليل الإطار الحالي ومعاينة اللون", fontWeight = FontWeight.Bold)
            }
            Text(
                "المعاينة الذكية هنا للإطار المحدد. تطبيق هذه الأقنعة على الفيديو الكامل أثناء التصدير يحتاج معالجة متتابعة لكل إطارات المقطع.",
                color = Color(0xFFFFD28A),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ColorSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = ColorStudioAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
