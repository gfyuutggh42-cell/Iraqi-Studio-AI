package com.iraqistudio.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val Ink = Color(0xFF10131A)
private val Panel = Color(0xFF1B202B)
private val Mint = Color(0xFF70D6B2)
private val Violet = Color(0xFF9B8CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioScreen() }
    }
}

private fun timeLabel(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

/** Apply the selected semantic mask to a preview bitmap without changing other pixels. */
private fun makeSelectivePreview(
    source: Bitmap,
    masks: SegmentationMasks,
    skyMasks: SegmentationMasks?,
    selectedRegion: Int,
    hueShift: Float,
    saturationScale: Float,
    skyBrightness: Float = 0f,
    skyContrast: Float = 1f,
    skyTemperature: Float = 0f,
    skyHue: Float = 0f,
    skySaturation: Float = 1f
): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    if (hueShift != 0f || saturationScale != 1f || (selectedRegion == 6 && (skyBrightness != 0f || skyContrast != 1f || skyTemperature != 0f || skyHue != 0f || skySaturation != 1f))) {
        val hsv = FloatArray(3)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val i = y * source.width + x
            val personClass = masks.categoryAt(x, y, source.width, source.height)
            val selectedPixel = if (selectedRegion == 6) {
                skyMasks?.categoryAt(x, y, source.width, source.height) == 1 && personClass == 0
            } else personClass == selectedRegion
            if (selectedPixel) {
                if (selectedRegion == 6) {
                    val original = pixels[i]
                    val r = (((original shr 16) and 255).toFloat() - 128f) * skyContrast + 128f + skyBrightness * 255f + skyTemperature * 38f
                    val g = (((original shr 8) and 255).toFloat() - 128f) * skyContrast + 128f + skyBrightness * 255f
                    val b = ((original and 255).toFloat() - 128f) * skyContrast + 128f + skyBrightness * 255f - skyTemperature * 38f
                    pixels[i] = (original and -0x1000000) or (r.toInt().coerceIn(0,255) shl 16) or (g.toInt().coerceIn(0,255) shl 8) or b.toInt().coerceIn(0,255)
                }
                android.graphics.Color.colorToHSV(pixels[i], hsv)
                val activeHue = if (selectedRegion == 6) skyHue else hueShift
                val activeSaturation = if (selectedRegion == 6) skySaturation else saturationScale
                hsv[0] = (hsv[0] + activeHue + 360f) % 360f
                hsv[1] = (hsv[1] * activeSaturation).coerceIn(0f, 1f)
                pixels[i] = android.graphics.Color.HSVToColor((pixels[i] ushr 24) and 255, hsv)
            }
        }
    }
    return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    }
}

/** Resize the longest side to 1920px, then apply tone and a modest clarity filter. */
private fun enhanceForExport(
    source: Bitmap,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    clarity: Float,
    maxDimension: Int,
    regionMasks: SegmentationMasks? = null,
    skyMasks: SegmentationMasks? = null,
    selectedRegion: Int = -1,
    regionHue: Float = 0f,
    regionSaturation: Float = 1f,
    skyBrightness: Float = 0f,
    skyContrast: Float = 1f,
    skyTemperature: Float = 0f,
    skyHue: Float = 0f,
    skySaturation: Float = 1f
): Bitmap {
    val scale = if (maxDimension <= 0) 1f else min(1f, maxDimension.toFloat() / max(source.width, source.height).toFloat())
    val width = max(1, (source.width * scale).toInt())
    val height = max(1, (source.height * scale).toInt())
    val scaled = if (width != source.width || height != source.height)
        Bitmap.createScaledBitmap(source, width, height, true) else source
    val input = IntArray(width * height)
    scaled.getPixels(input, 0, width, 0, 0, width, height)
    val toned = IntArray(input.size)
    val c = contrast.coerceIn(0.5f, 1.8f)
    val s = saturation.coerceIn(0f, 2f)
    val b = brightness.coerceIn(-0.35f, 0.35f) * 255f
    for (i in input.indices) {
        val color = input[i]
        val r0 = ((color shr 16) and 255).toFloat()
        val g0 = ((color shr 8) and 255).toFloat()
        val b0 = (color and 255).toFloat()
        val gray = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
        val r = (((gray + (r0 - gray) * s - 128f) * c) + 128f + b).toInt().coerceIn(0, 255)
        val g = (((gray + (g0 - gray) * s - 128f) * c) + 128f + b).toInt().coerceIn(0, 255)
        val bl = (((gray + (b0 - gray) * s - 128f) * c) + 128f + b).toInt().coerceIn(0, 255)
        toned[i] = (color and -0x1000000) or (r shl 16) or (g shl 8) or bl
    }
    if (regionMasks != null && selectedRegion >= 0 && (regionHue != 0f || regionSaturation != 1f || (selectedRegion == 6 && (skyBrightness != 0f || skyContrast != 1f || skyTemperature != 0f || skyHue != 0f || skySaturation != 1f)))) {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val personClass = regionMasks.categoryAt(x, y, width, height)
            val selectedPixel = if (selectedRegion == 6) {
                skyMasks?.categoryAt(x, y, width, height) == 1 && personClass == 0
            } else personClass == selectedRegion
            if (selectedPixel) {
                if (selectedRegion == 6) {
                    val original = toned[i]
                    val r = (((original shr 16) and 255).toFloat() - 128f) * skyContrast + 128f + skyBrightness * 255f + skyTemperature * 38f
                    val g = (((original shr 8) and 255).toFloat() - 128f) * skyContrast + 128f + skyBrightness * 255f
                    val bl = ((original and 255).toFloat() - 128f) * skyContrast + 128f + skyBrightness * 255f - skyTemperature * 38f
                    toned[i] = (original and -0x1000000) or (r.toInt().coerceIn(0,255) shl 16) or (g.toInt().coerceIn(0,255) shl 8) or bl.toInt().coerceIn(0,255)
                }
                val pixel = toned[i]
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val activeHue = if (selectedRegion == 6) skyHue else regionHue
                val activeSaturation = if (selectedRegion == 6) skySaturation else regionSaturation
                hsv[0] = (hsv[0] + activeHue + 360f) % 360f
                hsv[1] = (hsv[1] * activeSaturation).coerceIn(0f, 1f)
                toned[i] = android.graphics.Color.HSVToColor((pixel ushr 24) and 255, hsv)
            }
        }
    }
    val amount = (clarity.coerceIn(0f, 100f) / 100f) * 0.65f
    if (amount > 0f && width > 2 && height > 2) {
        val sharpened = toned.copyOf()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            fun channel(pixel: Int, shift: Int) = (pixel shr shift) and 255
            for (shift in intArrayOf(16, 8, 0)) {
                val center = channel(toned[i], shift)
                val neighbors = channel(toned[i - width], shift) +
                    channel(toned[i + width], shift) +
                    channel(toned[i - 1], shift) +
                    channel(toned[i + 1], shift)
                val value = (center * (1f + 4f * amount) - neighbors * amount).toInt().coerceIn(0, 255)
                val mask = 255 shl shift
                sharpened[i] = (sharpened[i] and mask.inv()) or (value shl shift)
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(sharpened, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        return result
    }
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(toned, 0, width, 0, 0, width, height)
    if (scaled !== source) scaled.recycle()
    return result
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun StudioScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var activeTab by remember { mutableStateOf("video") }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startSeconds by remember { mutableFloatStateOf(0f) }
    var endSeconds by remember { mutableFloatStateOf(0f) }
    var isExporting by remember { mutableStateOf(false) }
    var videoFilter by remember { mutableStateOf("طبيعي") }
    var videoBrightness by remember { mutableFloatStateOf(0f) }
    var videoSaturation by remember { mutableFloatStateOf(0f) }
    var videoBlur by remember { mutableFloatStateOf(0f) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectivePreview by remember { mutableStateOf<Bitmap?>(null) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var clarity by remember { mutableFloatStateOf(35f) }
    var photoResolution by remember { mutableStateOf("1080p") }
    var segmentationMasks by remember { mutableStateOf<SegmentationMasks?>(null) }
    var skySegmentationMasks by remember { mutableStateOf<SegmentationMasks?>(null) }
    var selectedRegionName by remember { mutableStateOf("الملابس") }
    var regionHue by remember { mutableFloatStateOf(0f) }
    var regionSaturation by remember { mutableFloatStateOf(1f) }
    var skyPanelOpen by remember { mutableStateOf(true) }
    var skyBrightness by remember { mutableFloatStateOf(0f) }
    var skyContrast by remember { mutableFloatStateOf(1f) }
    var skyTemperature by remember { mutableFloatStateOf(0f) }
    var skyHue by remember { mutableFloatStateOf(0f) }
    var skySaturation by remember { mutableFloatStateOf(1f) }
    var isAnalyzing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val regionOptions = listOf("الخلفية" to 0, "الشعر" to 1, "بشرة الجسم" to 2, "الوجه" to 3, "الملابس" to 4, "الإكسسوارات" to 5, "السماء" to 6)
    LaunchedEffect(photoBitmap, segmentationMasks, skySegmentationMasks, selectedRegionName, regionHue, regionSaturation, skyBrightness, skyContrast, skyTemperature, skyHue, skySaturation) {
        val source = photoBitmap
        val masks = segmentationMasks
        if (source != null && masks != null) {
            val regionId = regionOptions.firstOrNull { it.first == selectedRegionName }?.second ?: 4
            val generated = withContext(Dispatchers.Default) {
                makeSelectivePreview(source, masks, skySegmentationMasks, regionId, regionHue, regionSaturation, skyBrightness, skyContrast, skyTemperature, skyHue, skySaturation)
            }
            selectivePreview = generated
        } else {
            selectivePreview = null
        }
    }


    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
        videoUris = if (uri != null) listOf(uri) else emptyList()
        durationMs = 0L
        startSeconds = 0f
        endSeconds = 0f
    }
    val multiVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            videoUris = (videoUris + uris).distinct()
            if (videoUri == null) videoUri = videoUris.firstOrNull()
            durationMs = 0L
            startSeconds = 0f
            endSeconds = 0f
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    photoBitmap = BitmapFactory.decodeStream(stream)
                selectivePreview = null
                }
                brightness = 0f
                contrast = 1f
                saturation = 1f
                clarity = 35f
                photoResolution = "1080p"
                segmentationMasks = null
                skySegmentationMasks = null
                selectedRegionName = "الملابس"
                regionHue = 0f
                regionSaturation = 1f
                skyBrightness = 0f
                skyContrast = 1f
                skyTemperature = 0f
                skyHue = 0f
                skySaturation = 1f
                if (photoBitmap == null) Toast.makeText(context, "تعذر فتح الصورة", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "تعذر فتح الصورة: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val saveVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val source = pendingExportFile
        if (destination != null && source != null) {
            try {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("تعذر فتح مكان الحفظ")
                Toast.makeText(context, "تم حفظ الفيديو بنجاح", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "تعذر حفظ الفيديو: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                source.delete()
                pendingExportFile = null
                isExporting = false
            }
        } else {
            source?.delete()
            pendingExportFile = null
            isExporting = false
        }
    }
    val savePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { destination ->
        val bitmap = photoBitmap
        if (destination != null && bitmap != null) {
            try {
                val maxDimension = when (photoResolution) { "720p" -> 1280; "1080p" -> 1920; "2K" -> 2560; else -> 0 }
                val selectedRegionId = regionOptions.firstOrNull { it.first == selectedRegionName }?.second ?: 4
                val enhanced = enhanceForExport(bitmap, brightness, contrast, saturation, clarity, maxDimension, segmentationMasks, skySegmentationMasks, selectedRegionId, regionHue, regionSaturation, skyBrightness, skyContrast, skyTemperature, skyHue, skySaturation)
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    if (!enhanced.compress(Bitmap.CompressFormat.JPEG, 95, output)) error("تعذر إنشاء ملف الصورة")
                } ?: error("تعذر فتح مكان الحفظ")
                enhanced.recycle()
                Toast.makeText(context, "تم حفظ الصورة بنجاح • ${photoResolution}", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "تعذر حفظ الصورة: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val player = remember(videoUri) {
        videoUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
        }
    }
    LaunchedEffect(player, videoFilter, videoBrightness, videoSaturation, videoBlur) {
        player?.setVideoEffects(buildVideoEffects(videoFilter, videoBrightness, videoSaturation, videoBlur))
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player != null) {
                    val foundDuration = player.duration
                    if (foundDuration > 0L && foundDuration != androidx.media3.common.C.TIME_UNSET) {
                        durationMs = foundDuration
                        endSeconds = foundDuration / 1000f
                    }
                }
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, secondary = Violet, background = Ink, surface = Panel)) {
        Scaffold(containerColor = Ink, topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "شعار Iraqi Studio AI", tint = Ink)
                        }
                        Column {
                            Text("Iraqi Studio AI", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("استوديو الفيديو والصور الذكي", fontSize = 11.sp, color = Mint)
                        }
                    }
                },
                actions = { Text("0.5", color = Color.LightGray, modifier = Modifier.padding(end = 16.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { activeTab = "video" }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == "video") Mint else Panel, contentColor = if (activeTab == "video") Ink else Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(6.dp)); Text("محرر الفيديو") }
                    Button(
                        onClick = { activeTab = "photo" }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == "photo") Violet else Panel, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) { Icon(Icons.Default.Photo, null); Spacer(Modifier.width(6.dp)); Text("محرر الصور") }
                }

                if (activeTab == "video") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("مشروع فيديو جديد", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { picker.launch("video/*") }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("اختيار فيديو")
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF080A0F), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        if (player != null) {
                            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Movie, null, tint = Mint, modifier = Modifier.size(42.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("اختر فيديو للبدء", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("ستظهر المعاينة هنا", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("ترتيب ودمج المقاطع", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("أضف أكثر من فيديو، غيّر ترتيبها، ثم صدّرها في ملف واحد متتابع.", color = Color.LightGray, fontSize = 12.sp)
                            OutlinedButton(onClick = { multiVideoPicker.launch("video/*") }, modifier = Modifier.fillMaxWidth(), enabled = !isExporting) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("إضافة مقاطع فيديو")
                            }
                            if (videoUris.isEmpty()) Text("لم تضف مقاطع إضافية بعد.", color = Color.Gray, fontSize = 12.sp)
                            videoUris.forEachIndexed { index, uri ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${index + 1}. ${uri.lastPathSegment?.takeLast(24) ?: "مقطع فيديو"}", color = Color.White, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                    TextButton(onClick = {
                                        if (index > 0) {
                                            val updated = videoUris.toMutableList()
                                            val item = updated.removeAt(index)
                                            updated.add(index - 1, item)
                                            videoUris = updated
                                            videoUri = updated.firstOrNull()
                                        }
                                    }, enabled = index > 0 && !isExporting) { Text("↑") }
                                    TextButton(onClick = {
                                        if (index < videoUris.lastIndex) {
                                            val updated = videoUris.toMutableList()
                                            val item = updated.removeAt(index)
                                            updated.add(index + 1, item)
                                            videoUris = updated
                                            videoUri = updated.firstOrNull()
                                        }
                                    }, enabled = index < videoUris.lastIndex && !isExporting) { Text("↓") }
                                    TextButton(onClick = {
                                        val updated = videoUris.toMutableList().also { it.removeAt(index) }
                                        videoUris = updated
                                        videoUri = updated.firstOrNull()
                                    }, enabled = !isExporting) { Text("حذف", color = Color(0xFFFF8A8A)) }
                                }
                            }
                            Button(
                                onClick = {
                                    if (videoUris.size < 2) {
                                        Toast.makeText(context, "أضف مقطعين على الأقل للدمج", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isExporting = true
                                        player?.pause()
                                        val output = File(context.cacheDir, "iraqi_studio_join_${System.currentTimeMillis()}.mp4")
                                        val items = videoUris.map { uri ->
                                            EditedMediaItem.Builder(MediaItem.fromUri(uri))
                                                .setEffects(Effects(emptyList(), buildVideoEffects(videoFilter, videoBrightness, videoSaturation, videoBlur)))
                                                .build()
                                        }
                                        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items)
                                        val composition = Composition.Builder(sequence).build()
                                        val mainHandler = Handler(Looper.getMainLooper())
                                        try {
                                            val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                                    mainHandler.post { isExporting = false; pendingExportFile = output; saveVideoLauncher.launch("IraqiStudioAI-merged.mp4") }
                                                }
                                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                                    mainHandler.post { isExporting = false; output.delete(); Toast.makeText(context, "فشل دمج المقاطع: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show() }
                                                }
                                            }).build()
                                            transformer.start(composition, output.absolutePath)
                                        } catch (error: Exception) {
                                            isExporting = false
                                            output.delete()
                                            Toast.makeText(context, "تعذر بدء الدمج: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = videoUris.size >= 2 && !isExporting,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                            ) { Text(if (isExporting) "جارٍ دمج المقاطع..." else "دمج وتصدير ${videoUris.size} مقاطع") }
                            Text("يُحافظ الدمج على مسارات الصوت الأصلية للمقاطع. الانتقالات المتداخلة غير مدعومة في محرك الدمج الحالي.", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("تأثيرات الفيديو", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("تظهر في المعاينة وتُطبّق عند التصدير.", color = Color.LightGray, fontSize = 12.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("طبيعي", "سينمائي", "أبيض وأسود", "سيبيا", "معكوس").forEach { preset ->
                                    FilterChip(selected = videoFilter == preset, onClick = { videoFilter = preset }, label = { Text(preset, fontSize = 11.sp) })
                                }
                            }
                            Text("السطوع: ${"%.0f".format(videoBrightness * 100)}%", color = Color.White)
                            Slider(value = videoBrightness, onValueChange = { videoBrightness = it }, valueRange = -0.5f..0.5f)
                            Text("تشبع الألوان: ${"%.0f".format(videoSaturation)}", color = Color.White)
                            Slider(value = videoSaturation, onValueChange = { videoSaturation = it }, valueRange = -100f..100f)
                            Text("تمويه كامل الصورة: ${"%.0f".format(videoBlur)}", color = Color.White)
                            Slider(value = videoBlur, onValueChange = { videoBlur = it }, valueRange = 0f..12f)
                            Text("هذا تمويه للإطار كله وليس عزل الشخص. البورتريه للفيديو ما زال قيد التطوير.", color = Mint, fontSize = 11.sp)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("قص الفيديو", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("حدد بداية المقطع ونهايته، ثم صدّر الجزء الذي اخترته.", color = Color.LightGray, fontSize = 12.sp)
                            if (videoUri == null) Text("اختر فيديو أولاً حتى تظهر أدوات القص.", color = Mint, fontSize = 13.sp)
                            else if (durationMs <= 0L) Text("جارٍ قراءة مدة الفيديو...", color = Mint, fontSize = 13.sp)
                            else {
                                Text("بداية المقطع: ${timeLabel((startSeconds * 1000).toLong())}", color = Color.White)
                                Slider(value = startSeconds.coerceIn(0f, (endSeconds - 1f).coerceAtLeast(0f)), onValueChange = { startSeconds = it }, valueRange = 0f..(endSeconds - 1f).coerceAtLeast(0f), enabled = !isExporting && durationMs >= 2000L)
                                Text("نهاية المقطع: ${timeLabel((endSeconds * 1000).toLong())}", color = Color.White)
                                Slider(value = endSeconds.coerceIn((startSeconds + 1f).coerceAtMost(durationMs / 1000f), durationMs / 1000f), onValueChange = { endSeconds = it }, valueRange = (startSeconds + 1f).coerceAtMost(durationMs / 1000f)..(durationMs / 1000f), enabled = !isExporting && durationMs >= 2000L)
                                Text("مدة الجزء المحدد: ${timeLabel(((endSeconds - startSeconds).coerceAtLeast(0f) * 1000).toLong())}", color = Mint, fontWeight = FontWeight.SemiBold)
                                Button(
                                    onClick = {
                                        val startMs = (startSeconds * 1000f).toLong()
                                        val endMs = (endSeconds * 1000f).toLong()
                                        if (endMs <= startMs || videoUri == null) Toast.makeText(context, "حدد بداية ونهاية صحيحتين", Toast.LENGTH_SHORT).show()
                                        else {
                                            isExporting = true
                                            player?.pause()
                                            val output = File(context.cacheDir, "iraqi_studio_trim_${System.currentTimeMillis()}.mp4")
                                            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
                                            val input = MediaItem.Builder().setUri(videoUri!!).setClippingConfiguration(clipping).build()
                                            val mainHandler = Handler(Looper.getMainLooper())
                                            try {
                                                val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                                                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                                        mainHandler.post { isExporting = false; pendingExportFile = output; saveVideoLauncher.launch("IraqiStudioAI-clip.mp4") }
                                                    }
                                                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                                        mainHandler.post { isExporting = false; output.delete(); Toast.makeText(context, "فشل التصدير: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show() }
                                                    }
                                                }).build()
                                                val editedItem = EditedMediaItem.Builder(input).setEffects(Effects(emptyList(), buildVideoEffects(videoFilter, videoBrightness, videoSaturation, videoBlur))).build()
                                                transformer.start(editedItem, output.absolutePath)
                                            } catch (error: Exception) {
                                                isExporting = false; output.delete()
                                                Toast.makeText(context, "تعذر بدء التصدير: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = !isExporting && videoUri != null && durationMs >= 2000L,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                                ) { Text(if (isExporting) "جارٍ تجهيز الفيديو..." else "تصدير المقطع المحدد", fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                    Text("المرحلة التالية: دمج عدة مقاطع وترتيبها وإضافة النصوص والموسيقى والتأثيرات. هذه الأدوات لم تُفعّل بعد، ولن نظهرها كأنها تعمل قبل اكتمالها.", color = Color.LightGray, fontSize = 12.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("استوديو تعديل الصور", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { photoPicker.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White)) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("اختيار صورة")
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp).padding(10.dp), contentAlignment = Alignment.Center) {
                            if (photoBitmap != null) {
                                val c = contrast.coerceIn(0.5f, 2f)
                                val sat = saturation.coerceIn(0f, 2f)
                                val offset = 128f * (1f - c) + brightness.coerceIn(-0.35f, 0.35f) * 255f
                                val sr = 0.213f * (1f - sat); val sg = 0.715f * (1f - sat); val sb = 0.072f * (1f - sat)
                                val previewMatrix = ColorMatrix(floatArrayOf(
                                    (sr + sat) * c, sg * c, sb * c, 0f, offset,
                                    sr * c, (sg + sat) * c, sb * c, 0f, offset,
                                    sr * c, sg * c, (sb + sat) * c, 0f, offset,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                Image(bitmap = (selectivePreview ?: photoBitmap!!).asImageBitmap(), contentDescription = "معاينة الصورة المعدلة مباشرة", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit, colorFilter = ColorFilter.colorMatrix(previewMatrix))
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CameraAlt, null, tint = Violet, modifier = Modifier.size(52.dp))
                                    Text("اختر صورة لتبدأ التعديل", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("ستظهر صورتك هنا", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, null, tint = Violet)
                                Spacer(Modifier.width(8.dp))
                                Text("ضبط الصورة", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("الإضاءة  ${(brightness * 100).toInt()}", color = Color.White)
                            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.3f..0.3f, enabled = photoBitmap != null)
                            Text("التباين  ${(contrast * 100).toInt()}%", color = Color.White)
                            Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.6f..1.6f, enabled = photoBitmap != null)
                            Text("تشبع الألوان  ${(saturation * 100).toInt()}%", color = Color.White)
                            Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1.8f, enabled = photoBitmap != null)
                            Text("وضوح التفاصيل  ${clarity.toInt()}%", color = Color.White)
                            Slider(value = clarity, onValueChange = { clarity = it }, valueRange = 0f..100f, enabled = photoBitmap != null)
                            Divider(color = Color.DarkGray)
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202B3B)), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Tune, null, tint = Color(0xFF78CFFF), modifier = Modifier.size(26.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text("استوديو السماء", color = Color.White, fontWeight = FontWeight.Bold)
                                                Text("لوحة مستقلة للضوء واللون", color = Color.LightGray, fontSize = 11.sp)
                                            }
                                        }
                                        IconButton(onClick = { skyPanelOpen = !skyPanelOpen }) {
                                            Icon(if (skyPanelOpen) Icons.Default.Contrast else Icons.Default.Tune, contentDescription = "فتح أو إغلاق لوحة السماء", tint = Color(0xFF78CFFF))
                                        }
                                    }
                                    if (skyPanelOpen) {
                                        Text(if (skySegmentationMasks != null) "تم تحليل قناع السماء. هذه الإعدادات تؤثر على السماء وحدها." else "قناع السماء لم يكتمل بعد؛ الأدوات ستبقى متوقفة لحماية بقية الصورة.", color = if (skySegmentationMasks != null) Mint else Color(0xFFFFC66D), fontSize = 11.sp)
                                        Button(onClick = { selectedRegionName = "السماء" }, enabled = skySegmentationMasks != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF285B79), contentColor = Color.White)) {
                                            Text(if (selectedRegionName == "السماء") "السماء محددة للتعديل" else "اختيار السماء للتعديل")
                                        }
                                        Text("تغيير لون السماء: ${skyHue.toInt()}°", color = Color.White)
                                        Slider(value = skyHue, onValueChange = { skyHue = it }, valueRange = -180f..180f, enabled = skySegmentationMasks != null)
                                        Text("تشبع لون السماء: ${(skySaturation * 100).toInt()}%", color = Color.White)
                                        Slider(value = skySaturation, onValueChange = { skySaturation = it }, valueRange = 0f..2f, enabled = skySegmentationMasks != null)
                                        Text("إضاءة السماء: ${(skyBrightness * 100).toInt()}", color = Color.White)
                                        Slider(value = skyBrightness, onValueChange = { skyBrightness = it }, valueRange = -0.5f..0.5f, enabled = skySegmentationMasks != null)
                                        Text("تباين السماء: ${(skyContrast * 100).toInt()}%", color = Color.White)
                                        Slider(value = skyContrast, onValueChange = { skyContrast = it }, valueRange = 0.5f..1.8f, enabled = skySegmentationMasks != null)
                                        Text("دفء اللون / برودته: ${(skyTemperature * 100).toInt()}", color = Color.White)
                                        Slider(value = skyTemperature, onValueChange = { skyTemperature = it }, valueRange = -1f..1f, enabled = skySegmentationMasks != null)
                                        Text("يمكنك تغيير اللون والتشبع والسطوع والتباين والدفء دون المساس ببقية الصورة.", color = Color.LightGray, fontSize = 11.sp)
                                    }
                                }
                            }
                            Divider(color = Color.DarkGray)
                            Text("العزل الذكي للمناطق", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("حلّل الصورة لتحديد السماء وأجزاء الشخص والملابس والخلفية، ثم غيّر لون المنطقة المختارة وحدها. يتم استبعاد أجزاء الشخص من قناع السماء.", color = Color.LightGray, fontSize = 12.sp)
                            Button(
                                onClick = {
                                    val source = photoBitmap
                                    if (source != null) scope.launch {
                                        isAnalyzing = true
                                        try {
                                            segmentationMasks = withContext(Dispatchers.Default) { segmentPhoto(context, source) }
                                            skySegmentationMasks = try {
                                                withContext(Dispatchers.Default) { segmentSky(context, source) }
                                            } catch (skyError: Exception) {
                                                null
                                            }
                                            if (skySegmentationMasks != null) {
                                                Toast.makeText(context, "اكتمل عزل الوجه والملابس والسماء بالذكاء الاصطناعي", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "اكتمل عزل أجزاء الشخص، لكن تعذر تحميل نموذج السماء", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (error: Exception) {
                                            segmentationMasks = null
                                            skySegmentationMasks = null
                                            Toast.makeText(context, "تعذر تحليل الصورة: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                                        } finally { isAnalyzing = false }
                                    }
                                },
                                enabled = photoBitmap != null && !isAnalyzing,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                            ) { Text(if (isAnalyzing) "جارٍ تحليل الصورة..." else if (segmentationMasks == null) "تحليل الصورة وعزل المناطق" else "إعادة تحليل الصورة") }
                            if (segmentationMasks != null) {
                                Text("اختر المنطقة التي تريد تعديل لونها:", color = Color.White)
                                if (skySegmentationMasks == null) Text("تنبيه: لم يتوفر قناع السماء لهذه الصورة؛ بقية المناطق تبقى متاحة.", color = Color(0xFFFFC66D), fontSize = 11.sp)
                                regionOptions.chunked(3).forEach { group ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        group.forEach { (label, _) ->
                                            FilterChip(
                                                selected = selectedRegionName == label,
                                                onClick = { selectedRegionName = label },
                                                label = { Text(label, fontSize = 10.sp) }
                                            )
                                        }
                                    }
                                }
                                if (selectedRegionName == "السماء") {
                                    Text("إعدادات لون السماء مستقلة في لوحة استوديو السماء أعلاه.", color = Color.LightGray, fontSize = 11.sp)
                                } else {
                                    Text("تغيير درجة اللون: ${regionHue.toInt()}°", color = Color.White)
                                    Slider(value = regionHue, onValueChange = { regionHue = it }, valueRange = -180f..180f)
                                    Text("قوة التشبع للمنطقة: ${(regionSaturation * 100).toInt()}%", color = Color.White)
                                    Slider(value = regionSaturation, onValueChange = { regionSaturation = it }, valueRange = 0f..2f)
                                }
                                Text("تظهر معاينة التعديل مباشرة. قناع الشخص 256×256، وقناع السماء يعتمد على نموذج ADE20K؛ قد تحتاج الحواف الدقيقة إلى تصحيح يدوي لاحقًا.", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Text("جودة حفظ الصورة", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("720p", "1080p", "2K", "الأصلية").forEach { option ->
                                    FilterChip(selected = photoResolution == option, onClick = { photoResolution = option }, label = { Text(option, fontSize = 11.sp) }, enabled = photoBitmap != null)
                                }
                            }
                            Text("المعاينة تتغير مباشرة مع الإضاءة والتباين والتشبع؛ الوضوح يُطبّق عند الحفظ. خيار الأصلية يحافظ على أبعاد الملف الأصلي.", color = Color.LightGray, fontSize = 12.sp)
                            Button(onClick = { savePhotoLauncher.launch("IraqiStudioAI-photo.jpg") }, enabled = photoBitmap != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Violet), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.Brush, null); Spacer(Modifier.width(8.dp)); Text("حفظ الصورة المعدلة • $photoResolution", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text("أدوات إضافية مثل القصّ الدقيق والفلاتر وتنعيم البشرة وإزالة الخلفية ستُضاف بعد تنفيذها واختبارها.", color = Color.LightGray, fontSize = 12.sp)
                }
                Text("Iraqi Studio AI • النسخة 0.6", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Color(0xFF29243F), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Violet)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
            }
        }
    }
}
