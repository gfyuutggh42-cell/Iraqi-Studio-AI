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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
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

/** Resize the longest side to 1920px, then apply tone and a modest clarity filter. */
private fun enhanceForExport(
    source: Bitmap,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    clarity: Float
): Bitmap {
    val scale = min(1f, 1920f / max(source.width, source.height).toFloat())
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
    var durationMs by remember { mutableLongStateOf(0L) }
    var startSeconds by remember { mutableFloatStateOf(0f) }
    var endSeconds by remember { mutableFloatStateOf(0f) }
    var isExporting by remember { mutableStateOf(false) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var clarity by remember { mutableFloatStateOf(35f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
        durationMs = 0L
        startSeconds = 0f
        endSeconds = 0f
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    photoBitmap = BitmapFactory.decodeStream(stream)
                }
                brightness = 0f
                contrast = 1f
                saturation = 1f
                clarity = 35f
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
                val enhanced = enhanceForExport(bitmap, brightness, contrast, saturation, clarity)
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    if (!enhanced.compress(Bitmap.CompressFormat.JPEG, 95, output)) error("تعذر إنشاء ملف الصورة")
                } ?: error("تعذر فتح مكان الحفظ")
                enhanced.recycle()
                Toast.makeText(context, "تم حفظ الصورة المحسّنة بجودة عالية (حتى 1080p)", Toast.LENGTH_LONG).show()
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
                actions = { Text("0.3", color = Color.LightGray, modifier = Modifier.padding(end = 16.dp)) },
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
                                                transformer.start(input, output.absolutePath)
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
                    Text("أدوات الفيديو القادمة", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    listOf("دمج المقاطع" to "جمع عدة فيديوهات وترتيبها", "النصوص والموسيقى" to "إضافة عناوين وموسيقى وتحكم بالصوت", "الفلاتر والتأثيرات" to "ألوان سينمائية وانتقالات وتثبيت").forEach { (title, subtitle) ->
                        ToolCard(title, subtitle, Icons.Default.Movie)
                    }
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
                                Image(bitmap = photoBitmap!!.asImageBitmap(), contentDescription = "معاينة الصورة", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
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
                            Text("يُطبّق تحسين الوضوح والألوان عند التصدير. تُحفظ الصورة بأبعاد لا يتجاوز ضلعها الأطول 1920 بكسل.", color = Color.LightGray, fontSize = 12.sp)
                            Button(onClick = { savePhotoLauncher.launch("IraqiStudioAI-photo.jpg") }, enabled = photoBitmap != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Violet), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.Brush, null); Spacer(Modifier.width(8.dp)); Text("تحسين وحفظ الصورة بجودة 1080p", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    ToolCard("تحسين الصور الاحترافي", "ضبط الإضاءة والتباين والألوان ووضوح التفاصيل", Icons.Default.AutoFixHigh)
                    ToolCard("أدوات متقدمة قادمة", "تنعيم البشرة، تحرير الوجه، إزالة الخلفية والتمويه ستضاف وتختبر لاحقاً", Icons.Default.Contrast)
                }
                Text("Iraqi Studio AI • النسخة 0.3 التجريبية", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
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
