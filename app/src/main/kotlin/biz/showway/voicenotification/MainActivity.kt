package biz.showway.voicenotification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect

data class AppEntry(val pkg: String, val label: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var serviceEnabled by remember { mutableStateOf(prefs.serviceEnabled) }
    var packages by remember { mutableStateOf(prefs.packages) }
    var chime by remember { mutableStateOf(prefs.chimeEnabled) }
    var calendar by remember { mutableStateOf(prefs.calendarEnabled) }
    var calendarLead by remember { mutableStateOf(prefs.calendarLeadMinutes.toString()) }
    var news by remember { mutableStateOf(prefs.newsEnabled) }
    var newsInterval by remember { mutableStateOf(prefs.newsIntervalMinutes.toString()) }
    var newsCount by remember { mutableStateOf(prefs.newsCount.toString()) }
    var newsUrl by remember { mutableStateOf(prefs.newsUrl) }
    var newsOnlyMusic by remember { mutableStateOf(prefs.newsOnlyWhenMusic) }
    var pauseMusic by remember { mutableStateOf(prefs.pauseMusic) }
    var newsChime by remember { mutableStateOf(Chime.of(prefs.newsChime)) }
    var newsChimeUri by remember { mutableStateOf(prefs.newsChimeUri) }

    var listenerOk by remember { mutableStateOf(NotificationReader.isEnabled(ctx)) }
    var calendarOk by remember { mutableStateOf(CalendarSource.hasPermission(ctx)) }
    var exactOk by remember { mutableStateOf(Scheduler.canScheduleExact(ctx)) }
    var postOk by remember { mutableStateOf(hasPostPermission(ctx)) }

    val apps = remember { installedApps(ctx) }

    // 設定画面から戻ったときに権限状態を取り直す
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                listenerOk = NotificationReader.isEnabled(ctx)
                calendarOk = CalendarSource.hasPermission(ctx)
                exactOk = Scheduler.canScheduleExact(ctx)
                postOk = hasPostPermission(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendarOk = CalendarSource.hasPermission(ctx)
        postOk = hasPostPermission(ctx)
    }

    val chimePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        newsChimeUri = uri.toString(); prefs.newsChimeUri = uri.toString()
        newsChime = Chime.CUSTOM; prefs.newsChime = Chime.CUSTOM.key
    }

    fun applyService() {
        prefs.serviceEnabled = serviceEnabled
        Scheduler.reschedule(ctx)
        if (serviceEnabled) VoiceService.start(ctx, null) else VoiceService.stop(ctx)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("VoiceNotification") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SwitchRow("読み上げサービス", serviceEnabled) {
                    serviceEnabled = it
                    applyService()
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { VoiceService.start(ctx, VoiceService.ACTION_SPEAK, "テストです。読み上げは正常に動いています。") }) { Text("テスト") }
                    OutlinedButton(onClick = { VoiceService.start(ctx, Scheduler.ACTION_CHIME) }) { Text("時報") }
                    OutlinedButton(onClick = { VoiceService.start(ctx, VoiceService.ACTION_NEWS_NOW) }) { Text("ニュース") }
                    OutlinedButton(onClick = { Speaker.stop(ctx) }) { Text("停止") }
                }
            }

            item { Section("権限") }
            item {
                StatusRow("通知へのアクセス", listenerOk) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            item {
                StatusRow("カレンダー読み取り", calendarOk) {
                    permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                }
            }
            item {
                StatusRow("正確なアラーム", exactOk) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 33) item {
                StatusRow("通知の表示", postOk) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            item {
                OutlinedButton(onClick = { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }) {
                    Text("音声（TTS）の設定を開く")
                }
            }

            item { Section("共通") }
            item {
                SwitchRow("読み上げ中は音楽を一時停止（オフなら音量を下げる）", pauseMusic) {
                    pauseMusic = it; prefs.pauseMusic = it
                }
            }

            item { Section("時報") }
            item {
                SwitchRow("毎時 0 分に時刻と 1 時間以内の予定を読む", chime) {
                    chime = it; prefs.chimeEnabled = it; Scheduler.reschedule(ctx)
                }
            }

            item { Section("予定") }
            item {
                SwitchRow("予定の直前に読み上げる", calendar) {
                    calendar = it; prefs.calendarEnabled = it; Scheduler.reschedule(ctx)
                }
            }
            item {
                NumberField("何分前に読むか", calendarLead) {
                    calendarLead = it
                    it.toIntOrNull()?.let { n -> prefs.calendarLeadMinutes = n }
                }
            }

            item { Section("ニュース") }
            item {
                SwitchRow("定期的にニュースを読む", news) {
                    news = it; prefs.newsEnabled = it; Scheduler.reschedule(ctx)
                }
            }
            item {
                SwitchRow("音楽再生中のみ", newsOnlyMusic) {
                    newsOnlyMusic = it; prefs.newsOnlyWhenMusic = it
                }
            }
            item {
                NumberField("間隔（分）", newsInterval) {
                    newsInterval = it
                    it.toIntOrNull()?.let { n -> prefs.newsIntervalMinutes = n; Scheduler.reschedule(ctx) }
                }
            }
            item {
                NumberField("件数", newsCount) {
                    newsCount = it
                    it.toIntOrNull()?.let { n -> prefs.newsCount = n }
                }
            }
            item { Text("ニュース前のチャイム", style = MaterialTheme.typography.labelLarge) }
            items(Chime.entries, key = { it.key }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = newsChime == c, onClick = {
                        if (c == Chime.CUSTOM) chimePicker.launch(arrayOf("audio/*"))
                        else { newsChime = c; prefs.newsChime = c.key }
                    })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.label)
                        if (c == Chime.CUSTOM && newsChimeUri != null) {
                            Text(Uri.parse(newsChimeUri).lastPathSegment ?: newsChimeUri!!, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { VoiceService.start(ctx, VoiceService.ACTION_CHIME_PREVIEW) }) { Text("チャイムを試聴") }
            }
            item {
                OutlinedTextField(
                    value = newsUrl,
                    onValueChange = { newsUrl = it; prefs.newsUrl = it.trim() },
                    label = { Text("RSS の URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Section("通知を読み上げるアプリ") }
            items(apps, key = { it.pkg }) { app ->
                val checked = app.pkg in packages
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(checked = checked, onCheckedChange = {
                        packages = if (it) packages + app.pkg else packages - app.pkg
                        prefs.packages = packages
                    })
                    Column {
                        Text(app.label)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        if (ok) Text("OK") else Button(onClick = onFix) { Text("設定") }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> if (s.all { it.isDigit() }) onChange(s) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(200.dp),
    )
}

private fun hasPostPermission(ctx: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun installedApps(ctx: android.content.Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != ctx.packageName }
        .map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)
            AppEntry(pkg, label)
        }
        .sortedBy { it.label.lowercase() }
}
