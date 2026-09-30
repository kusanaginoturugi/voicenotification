package biz.showway.voicenotification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect

data class AppEntry(val pkg: String, val label: String)

private enum class SettingsPage(val title: Int, val summary: Int) {
    READING(R.string.page_reading, R.string.page_reading_summary),
    AUTOMATION(R.string.page_automation, R.string.page_automation_summary),
    NOTIFICATIONS(R.string.page_notifications, R.string.page_notifications_summary),
    ENGINE(R.string.page_engine, R.string.page_engine_summary),
    SYSTEM(R.string.page_system, R.string.page_system_summary),
}

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
    var newsHours by remember { mutableStateOf(prefs.newsHours) }
    var newsCount by remember { mutableStateOf(prefs.newsCount.toString()) }
    var newsUrl by remember { mutableStateOf(prefs.newsUrl) }
    var newsFallbackUrl by remember { mutableStateOf(prefs.newsFallbackUrl) }
    var newsFallbackToken by remember { mutableStateOf(prefs.newsFallbackToken) }
    var newsOnlyMusic by remember { mutableStateOf(prefs.newsOnlyWhenMusic) }
    var pauseMusic by remember { mutableStateOf(prefs.pauseMusic) }
    var pauseMusicForLongSpeech by remember { mutableStateOf(prefs.pauseMusicForLongSpeech) }
    var muteInSilent by remember { mutableStateOf(prefs.muteInSilentMode) }
    var builtInSpeakerVolume by remember { mutableFloatStateOf(prefs.builtInSpeakerVolume) }
    var notificationMax by remember { mutableStateOf(prefs.notificationMaxChars.toString()) }
    var template by remember { mutableStateOf(prefs.notificationTemplate) }
    var templatePlain by remember { mutableStateOf(prefs.notificationTemplatePlain) }
    var newsChime by remember { mutableStateOf(Chime.of(prefs.newsChime)) }
    var newsChimeUri by remember { mutableStateOf(prefs.newsChimeUri) }
    var newsChimeVolume by remember { mutableStateOf(prefs.newsChimeVolume) }
    var ttsUrls by remember { mutableStateOf(prefs.ttsUrls) }
    var ttsSpeaker by remember { mutableStateOf(prefs.ttsSpeaker.toString()) }
    var ttsSpeakerName by remember { mutableStateOf(prefs.ttsSpeakerName) }
    var speakers by remember { mutableStateOf<List<RemoteTts.SpeakerStyle>>(emptyList()) }
    var speakerMenu by remember { mutableStateOf(false) }
    var speakerLoading by remember { mutableStateOf(false) }
    var ttsSpeed by remember { mutableStateOf(prefs.ttsSpeed.toString()) }
    var ttsVolume by remember { mutableStateOf(prefs.ttsVolume.toString()) }
    var probeResult by remember { mutableStateOf("") }

    var listenerOk by remember { mutableStateOf(NotificationReader.isEnabled(ctx)) }
    var calendarOk by remember { mutableStateOf(CalendarSource.hasPermission(ctx)) }
    var exactOk by remember { mutableStateOf(Scheduler.canScheduleExact(ctx)) }
    var postOk by remember { mutableStateOf(hasPostPermission(ctx)) }

    val apps = remember { installedApps(ctx) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    BackHandler(enabled = page != null) { page = null }

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

    if (page == null) {
        SettingsHome(onOpen = { page = it })
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page!!.title)) },
                navigationIcon = {
                    TextButton(onClick = { page = null }) { Text(stringResource(R.string.button_back)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (page == SettingsPage.READING) {
            item {
                SwitchRow(stringResource(R.string.service_enabled), serviceEnabled) {
                    serviceEnabled = it
                    applyService()
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { VoiceService.start(ctx, VoiceService.ACTION_SPEAK, ctx.getString(R.string.test_speech)) }) { Text(stringResource(R.string.button_test)) }
                    OutlinedButton(onClick = { VoiceService.start(ctx, Scheduler.ACTION_CHIME) }) { Text(stringResource(R.string.button_chime)) }
                    OutlinedButton(onClick = { VoiceService.start(ctx, VoiceService.ACTION_NEWS_NOW) }) { Text(stringResource(R.string.button_news)) }
                    OutlinedButton(onClick = { Speaker.stop(ctx) }) { Text(stringResource(R.string.button_stop)) }
                }
            }

            }

            if (page == SettingsPage.SYSTEM) {
            item { Section(R.string.section_permissions) }
            item {
                StatusRow(stringResource(R.string.permission_listener), listenerOk) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            item {
                StatusRow(stringResource(R.string.permission_calendar), calendarOk) {
                    permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                }
            }
            item {
                StatusRow(stringResource(R.string.permission_exact_alarm), exactOk) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 33) item {
                StatusRow(stringResource(R.string.permission_notifications), postOk) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            }

            if (page == SettingsPage.ENGINE) {
            item { Section(R.string.section_voicevox) }
            item {
                Text(stringResource(R.string.voicevox_help),
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(
                    value = ttsUrls,
                    onValueChange = { ttsUrls = it; prefs.ttsUrls = it },
                    label = { Text(stringResource(R.string.voicevox_url)) },
                    placeholder = { Text("http://13400f.tailb46b1.ts.net:50021") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    OutlinedButton(onClick = {
                        if (speakers.isEmpty() && !speakerLoading) {
                            speakerLoading = true
                            Thread {
                                val list = RemoteTts.speakers(prefs)
                                (ctx as? ComponentActivity)?.runOnUiThread {
                                    speakers = list
                                    speakerLoading = false
                                    speakerMenu = list.isNotEmpty()
                                }
                            }.start()
                        } else {
                            speakerMenu = true
                        }
                    }) {
                        Text(
                            when {
                                speakerLoading -> ctx.getString(R.string.voicevox_loading_speakers)
                                ttsSpeakerName.isNotEmpty() -> ctx.getString(R.string.voicevox_speaker_selected, ttsSpeakerName, ttsSpeaker)
                                else -> ctx.getString(R.string.voicevox_choose_speaker, ttsSpeaker)
                            }
                        )
                    }
                    DropdownMenu(expanded = speakerMenu, onDismissRequest = { speakerMenu = false }) {
                        speakers.forEach { sp ->
                            DropdownMenuItem(
                                text = { Text(ctx.getString(R.string.voicevox_speaker_selected, sp.label, sp.id)) },
                                onClick = {
                                    ttsSpeaker = sp.id.toString(); prefs.ttsSpeaker = sp.id
                                    ttsSpeakerName = sp.label; prefs.ttsSpeakerName = sp.label
                                    speakerMenu = false
                                },
                            )
                        }
                    }
                    if (!speakerLoading && speakers.isEmpty() && ttsSpeakerName.isEmpty()) {
                        Text(stringResource(R.string.voicevox_speaker_help), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(stringResource(R.string.voicevox_volume), ttsVolume) {
                        ttsVolume = it
                        it.toFloatOrNull()?.let { f -> prefs.ttsVolume = f }
                    }
                    OutlinedTextField(
                        value = ttsSpeed,
                        onValueChange = { v ->
                            if (v.matches(Regex("[0-9]*\\.?[0-9]*"))) {
                                ttsSpeed = v
                                v.toFloatOrNull()?.let { f -> prefs.ttsSpeed = f }
                            }
                        },
                        label = { Text(stringResource(R.string.voicevox_speed)) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        probeResult = ctx.getString(R.string.cache_cleared, RemoteTts.clearCache(ctx))
                    }) { Text(stringResource(R.string.button_clear_cache)) }
                    OutlinedButton(onClick = {
                        probeResult = ctx.getString(R.string.checking)
                        Thread {
                            val r = RemoteTts.probe(prefs).joinToString("\n") { (u, v) -> "$v  $u" }
                            (ctx as? ComponentActivity)?.runOnUiThread { probeResult = r.ifEmpty { ctx.getString(R.string.empty_url) } }
                        }.start()
                    }) { Text(stringResource(R.string.button_connection_test)) }
                    Text(probeResult, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                OutlinedButton(onClick = { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }) {
                    Text(stringResource(R.string.button_open_tts_settings))
                }
            }

            }

            if (page == SettingsPage.READING) {
            item { Section(R.string.section_common) }
            item {
                SwitchRow(stringResource(R.string.mute_in_silent), muteInSilent) {
                    muteInSilent = it; prefs.muteInSilentMode = it
                }
            }
            item {
                Text(stringResource(R.string.built_in_speaker_volume, (builtInSpeakerVolume * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.built_in_speaker_volume_help), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = builtInSpeakerVolume,
                    onValueChange = { builtInSpeakerVolume = it },
                    onValueChangeFinished = { prefs.builtInSpeakerVolume = builtInSpeakerVolume },
                    valueRange = 0.05f..1f,
                    steps = 18,
                )
            }
            item {
                SwitchRow(stringResource(R.string.pause_music), pauseMusic) {
                    pauseMusic = it; prefs.pauseMusic = it
                }
            }
            item {
                SwitchRow(stringResource(R.string.pause_music_long), pauseMusicForLongSpeech) {
                    pauseMusicForLongSpeech = it; prefs.pauseMusicForLongSpeech = it
                }
            }

            }

            if (page == SettingsPage.AUTOMATION) {
            item { Section(R.string.section_chime) }
            item {
                SwitchRow(stringResource(R.string.hourly_chime), chime) {
                    chime = it; prefs.chimeEnabled = it; Scheduler.reschedule(ctx)
                }
            }

            item { Section(R.string.section_calendar) }
            item {
                SwitchRow(stringResource(R.string.calendar_announce), calendar) {
                    calendar = it; prefs.calendarEnabled = it; Scheduler.reschedule(ctx)
                }
            }
            item {
                NumberField(stringResource(R.string.calendar_lead), calendarLead) {
                    calendarLead = it
                    it.toIntOrNull()?.let { n -> prefs.calendarLeadMinutes = n }
                }
            }

            item { Section(R.string.section_news) }
            item {
                SwitchRow(stringResource(R.string.scheduled_news), news) {
                    news = it; prefs.newsEnabled = it; Scheduler.reschedule(ctx)
                }
            }
            item {
                SwitchRow(stringResource(R.string.news_only_music), newsOnlyMusic) {
                    newsOnlyMusic = it; prefs.newsOnlyWhenMusic = it
                }
            }
            item {
                OutlinedTextField(
                    value = newsHours,
                    onValueChange = { v ->
                        if (v.all { it.isDigit() || it == ',' || it == ' ' }) {
                            newsHours = v
                            prefs.newsHours = v
                            Scheduler.reschedule(ctx)
                        }
                    },
                    label = { Text(stringResource(R.string.news_hours)) },
                    placeholder = { Text(Prefs.DEFAULT_NEWS_HOURS) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                NumberField(stringResource(R.string.count), newsCount) {
                    newsCount = it
                    it.toIntOrNull()?.let { n -> prefs.newsCount = n }
                }
            }
            item { Text(stringResource(R.string.news_chime), style = MaterialTheme.typography.labelLarge) }
            items(Chime.entries, key = { it.key }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = newsChime == c, onClick = {
                        if (c == Chime.CUSTOM) chimePicker.launch(arrayOf("audio/*"))
                        else { newsChime = c; prefs.newsChime = c.key }
                    })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(c.labelRes))
                        if (c == Chime.CUSTOM && newsChimeUri != null) {
                            Text(Uri.parse(newsChimeUri).lastPathSegment ?: newsChimeUri!!, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Column {
                    Text(stringResource(R.string.chime_volume, (newsChimeVolume * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = newsChimeVolume,
                        onValueChange = { newsChimeVolume = it },
                        onValueChangeFinished = { prefs.newsChimeVolume = newsChimeVolume },
                        valueRange = 0f..1f,
                        steps = 19,
                    )
                }
            }
            item {
                OutlinedButton(onClick = { VoiceService.start(ctx, VoiceService.ACTION_CHIME_PREVIEW) }) { Text(stringResource(R.string.button_preview_chime)) }
            }
            item {
                OutlinedTextField(
                    value = newsUrl,
                    onValueChange = { newsUrl = it; prefs.newsUrl = it.trim() },
                    label = { Text(stringResource(R.string.news_url)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            }

            if (page == SettingsPage.SYSTEM) {
            item { Section(R.string.section_fallback) }
            item {
                OutlinedTextField(
                    value = newsFallbackUrl,
                    onValueChange = { newsFallbackUrl = it; prefs.newsFallbackUrl = it.trim() },
                    label = { Text(stringResource(R.string.fallback_api)) },
                    placeholder = { Text("https://voicenews-fallback.<account>.workers.dev/v1/news") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = newsFallbackToken,
                    onValueChange = { newsFallbackToken = it; prefs.newsFallbackToken = it.trim() },
                    label = { Text(stringResource(R.string.fallback_token)) },
                    supportingText = { Text(stringResource(R.string.fallback_token_help)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            }

            if (page == SettingsPage.NOTIFICATIONS) {
            item { Section(R.string.section_notifications) }
            item {
                Text(stringResource(R.string.notification_help),
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it; prefs.notificationTemplate = it },
                    label = { Text(stringResource(R.string.template_sender)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = templatePlain,
                    onValueChange = { templatePlain = it; prefs.notificationTemplatePlain = it },
                    label = { Text(stringResource(R.string.template_plain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(stringResource(R.string.template_help),
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedButton(onClick = {
                    val sample = Speech.compose(ctx, prefs.notificationTemplate, ctx.getString(R.string.sample_app), ctx.getString(R.string.sample_sender), ctx.getString(R.string.sample_body))
                    VoiceService.start(ctx, VoiceService.ACTION_SPEAK, sample)
                }) { Text(stringResource(R.string.button_preview_template)) }
            }
            item {
                NumberField(stringResource(R.string.notification_max_chars), notificationMax) {
                    notificationMax = it
                    it.toIntOrNull()?.let { n -> prefs.notificationMaxChars = n }
                }
            }
            item { Section(R.string.section_apps) }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(onOpen: (SettingsPage) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_background),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            items(SettingsPage.entries) { page ->
                OutlinedButton(
                    onClick = { onOpen(page) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(page.title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(page.summary), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
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
        if (ok) Text(stringResource(R.string.ok)) else Button(onClick = onFix) { Text(stringResource(R.string.button_settings)) }
    }
}

/** 小数を受け付けるテキスト欄 */
@Composable
private fun DecimalField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> if (s.matches(Regex("[0-9]*\\.?[0-9]*"))) onChange(s) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(120.dp),
    )
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
