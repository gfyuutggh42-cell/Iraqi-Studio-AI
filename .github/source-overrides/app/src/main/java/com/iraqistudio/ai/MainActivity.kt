package com.iraqistudio.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val Ink = Color(0xFF10131A)
private val Panel = Color(0xFF1B202B)
private val Mint = Color(0xFF70D6B2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioScreen() }
    }
}

private fun timeLabel(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun StudioScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startSeconds by remember { mutableFloatStateOf(0f) }
    var endSeconds by remember { mutableFloatStateOf(0f) }
    var isExporting by remember { mutableStateOf(false) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
        durationMs = 0L
        startSeconds = 0f
        endSeconds = 0f
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Panel)) {
        Scaffold(containerColor = Ink, topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Iraqi Studio AI", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("استوديو المونتاج الذكي", fontSize = 11.sp, color = Mint)
                    }
                },
                actions = { Text("0.2", color = Color.LightGray, modifier = Modifier.padding(end = 16.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("مشروع جديد", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { picker.launch("video/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("اختيار فيديو")
                    }
                }

                Box(
                    Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF080A0F), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (player != null) {
                        AndroidView(
                            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = Mint, modifier = Modifier.size(42.dp))
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
                        if (videoUri == null) {
                            Text("اختر فيديو أولاً حتى تظهر أدوات القص.", color = Mint, fontSize = 13.sp)
                        } else if (durationMs <= 0L) {
                            Text("جارٍ قراءة مدة الفيديو...", color = Mint, fontSize = 13.sp)
                        } else {
                            Text("بداية المقطع: ${timeLabel((startSeconds * 1000).toLong())}", color = Color.White)
                            Slider(
                                value = startSeconds.coerceIn(0f, (endSeconds - 1f).coerceAtLeast(0f)),
                                onValueChange = { startSeconds = it },
                                valueRange = 0f..(endSeconds - 1f).coerceAtLeast(0f),
                                enabled = !isExporting && durationMs >= 2000L
                            )
                            Text("نهاية المقطع: ${timeLabel((endSeconds * 1000).toLong())}", color = Color.White)
                            Slider(
                                value = endSeconds.coerceIn((startSeconds + 1f).coerceAtMost(durationMs / 1000f), durationMs / 1000f),
                                onValueChange = { endSeconds = it },
                                valueRange = (startSeconds + 1f).coerceAtMost(durationMs / 1000f)..(durationMs / 1000f),
                                enabled = !isExporting && durationMs >= 2000L
                            )
                            Text(
                                "مدة الجزء المحدد: ${timeLabel(((endSeconds - startSeconds).coerceAtLeast(0f) * 1000).toLong())}",
                                color = Mint,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = {
                                    val startMs = (startSeconds * 1000f).toLong()
                                    val endMs = (endSeconds * 1000f).toLong()
                                    if (endMs <= startMs || videoUri == null) {
                                        Toast.makeText(context, "حدد بداية ونهاية صحيحتين", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isExporting = true
                                        player?.pause()
                                        val output = File(context.cacheDir, "iraqi_studio_trim_${System.currentTimeMillis()}.mp4")
                                        val clipping = MediaItem.ClippingConfiguration.Builder()
                                            .setStartPositionMs(startMs)
                                            .setEndPositionMs(endMs)
                                            .build()
                                        val input = MediaItem.Builder()
                                            .setUri(videoUri!!)
                                            .setClippingConfiguration(clipping)
                                            .build()
                                        val mainHandler = Handler(Looper.getMainLooper())
                                        try {
                                            val transformer = Transformer.Builder(context)
                                                .addListener(object : Transformer.Listener {
                                                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                                        mainHandler.post {
                                                            isExporting = false
                                                            pendingExportFile = output
                                                            saveVideoLauncher.launch("IraqiStudioAI-clip.mp4")
                                                        }
                                                    }

                                                    override fun onError(
                                                        composition: Composition,
                                                        exportResult: ExportResult,
                                                        exportException: ExportException
                                                    ) {
                                                        mainHandler.post {
                                                            isExporting = false
                                                            output.delete()
                                                            Toast.makeText(context, "فشل التصدير: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                })
                                                .build()
                                            transformer.start(input, output.absolutePath)
                                        } catch (error: Exception) {
                                            isExporting = false
                                            output.delete()
                                            Toast.makeText(context, "تعذر بدء التصدير: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isExporting && videoUri != null && durationMs >= 2000L,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)
                            ) {
                                Text(if (isExporting) "جارٍ تجهيز الفيديو..." else "تصدير المقطع المحدد", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Text("أدوات التحرير", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val sections = listOf(
                    "إضافة ودمج المقاطع" to "استيراد أكثر من فيديو وترتيب المقاطع",
                    "النصوص والموسيقى" to "إضافة كتابة وموسيقى والتحكم بمستوى الصوت",
                    "تحرير الجسم والعضلات" to "توسيع الكتفين • إبراز العضلات • تعديل الخصر",
                    "تحرير ملامح الوجه" to "العيون • الأنف • الفم • الفك والخدود",
                    "البشرة والعيون" to "تنعيم • توهج زجاجي • إزالة الهالات",
                    "حماية اللحية والشارب" to "حفظ اللون والوضوح والتفاصيل الأصلية",
                    "الألوان الانتقائية" to "الوجه • الملابس • الخلفية • السماء",
                    "عزل الخلفية والبورتريه" to "ضبابية • إزالة الخلفية • استبدالها",
                    "تحسين الفيديو" to "تقليل التشويش • الحدة • تثبيت الاهتزاز"
                )
                sections.forEach { (title, subtitle) ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("ضمن خطة التطوير — غير مفعّل بعد", color = Mint, fontSize = 10.sp)
                        }
                    }
                }
                Text(
                    "النسخة 0.2: اختيار الفيديو ومعاينته وقص جزء منه وتصديره كملف MP4. بقية الأدوات ستُفعّل وتُختبر على مراحل.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }
    }
}
