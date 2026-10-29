package com.example.paymenthelper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledApp(val label: String, val packageName: String)

private const val WAIT_WINDOW_MS = 120_000L

/**
 * In-memory ring buffer of debug messages. Both the activity and the service
 * write here; the UI shows the last lines so every step is visible on screen.
 * Everything is also mirrored to Logcat under the tag "PaymentHelper".
 */
object DebugLog {
    private const val MAX_LINES = 250
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(message: String) {
        Log.i("PaymentHelper", message)
        val line = "[${fmt.format(Date())}] $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PaymentHelperScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentHelperScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("payment_helper", Context.MODE_PRIVATE) }
    var selectedPackage by remember { mutableStateOf(prefs.getString("selected_package", null)) }
    var selectedLabel by remember { mutableStateOf(prefs.getString("selected_label", null)) }
    var showPicker by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Выберите банковское приложение.") }
    var balance by remember { mutableStateOf(prefs.getString("last_balance", null)) }
    var busy by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableStateOf(0L) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(true) }
    var shot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showShot by remember { mutableStateOf(false) }

    val installedApps = remember {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                InstalledApp(info.loadLabel(pm).toString(), packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun serviceEnabled(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                   it.resolveInfo.serviceInfo.name == BankAccessibilityService::class.java.name }
        DebugLog.log("UI: служба доступности включена = $enabled")
        return enabled
    }

    fun openPanel() {
        if (!serviceEnabled()) {
            status = "Разрешите службу доступности Payment Helper в настройках Android."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            status = "Разрешите отображение поверх других окон — сейчас открою настройки."
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        BankAccessibilityService.instance?.showPanel()
        status = if (BankAccessibilityService.instance != null)
            "Панель открыта поверх экрана. Управляйте с неё."
        else "Служба ещё подключается — подождите пару секунд и нажмите снова."
    }

    fun startCheck() {
        val packageName = selectedPackage
        if (packageName == null) {
            status = "Сначала выберите приложение."
            showPicker = true
            return
        }
        if (!serviceEnabled()) {
            status = "Разрешите службу доступности Payment Helper в настройках Android."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            status = "Не удалось открыть выбранное приложение. Выберите его ещё раз."
            DebugLog.log("UI: getLaunchIntentForPackage($packageName) вернул null")
            return
        }
        DebugLog.log("UI: запуск входа, пакет=$packageName")
        BankAccessibilityService.beginLoginCheck(context, packageName)
        busy = true
        waitUntil = System.currentTimeMillis() + WAIT_WINDOW_MS
        status = "Открылось приложение. Следуйте подсказкам в логах…"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }

    fun showBalance() {
        val packageName = selectedPackage
        if (packageName == null) {
            status = "Сначала выберите приложение."
            showPicker = true
            return
        }
        if (!BankAccessibilityService.isRefreshLearned(context)) {
            status = "Сначала добавьте кнопку обновления через панель."
            DebugLog.log("UI: кнопка обновления ещё не настроена")
            return
        }
        if (!serviceEnabled()) {
            status = "Разрешите службу доступности Payment Helper в настройках Android."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            status = "Не удалось открыть банк."
            return
        }
        BankAccessibilityService.beginBalanceCheck(context, packageName)
        busy = true
        waitUntil = System.currentTimeMillis() + 60_000L
        status = "Открываю банк и обновляю баланс…"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }

    LaunchedEffect(Unit) {
        while (true) {
            val loginTimestamp = prefs.getLong("login_result_time", 0L)
            if (loginTimestamp > 0L && loginTimestamp > prefs.getLong("shown_login_result_time", 0L)) {
                prefs.edit().putLong("shown_login_result_time", loginTimestamp).apply()
                busy = false
                waitUntil = 0L
                status = prefs.getString("login_result", "") ?: ""
                DebugLog.log("UI: получен результат — $status")
            } else if (busy && System.currentTimeMillis() > waitUntil) {
                busy = false
                waitUntil = 0L
                status = "Не получилось. Смотрите логи."
            }
            val balanceTimestamp = prefs.getLong("balance_result_time", 0L)
            if (balanceTimestamp > 0L && balanceTimestamp > prefs.getLong("shown_balance_result_time", 0L)) {
                prefs.edit().putLong("shown_balance_result_time", balanceTimestamp).apply()
                busy = false
                waitUntil = 0L
                balance = prefs.getString("last_balance", null)
                status = prefs.getString("balance_result", "") ?: ""
            }
            val shotTime = prefs.getLong("shot_time", 0L)
            if (shotTime > 0L && shotTime > prefs.getLong("shown_shot_time", 0L)) {
                prefs.edit().putLong("shown_shot_time", shotTime).apply()
                val path = prefs.getString("shot_path", null)
                if (path != null) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(path)
                    if (bmp != null) {
                        shot = bmp
                        showShot = true
                        busy = false
                        waitUntil = 0L
                        status = "Скриншот баланса:"
                        DebugLog.log("UI: показываю скриншот баланса")
                    }
                }
            }
            logs = DebugLog.snapshot()
            delay(500)
        }
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Payment Helper") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Банковское приложение", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedLabel ?: "Выбрать приложение")
                }
                Button(
                    onClick = { openPanel() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть панель")
                }
                Button(
                    onClick = { startCheck() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Выполняется вход…" else "Перейти")
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Баланс", style = MaterialTheme.typography.titleMedium)
                Text(balance ?: "Пока нет данных", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showBalance() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Показать баланс")
                    }
                }
                HorizontalDivider()
                OutlinedButton(
                    onClick = {
                        BankAccessibilityService.clearLearned(context)
                        status = "Пин-код сброшен. Следующий вход — обучение."
                        busy = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сбросить запомненный пин-код") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Логи", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "Скрыть" else "Показать")
                    }
                    TextButton(onClick = { DebugLog.clear(); logs = emptyList() }) {
                        Text("Очистить", color = Color.Gray)
                    }
                }
                if (showLogs) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .heightIn(min = 120.dp)
                    ) {
                        items(logs) { line ->
                            Text(
                                line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Настройки службы доступности") }
            }
        }

        if (showShot && shot != null) {
            AlertDialog(
                onDismissRequest = { showShot = false },
                title = { Text("Скриншот баланса") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Image(
                            bitmap = shot!!.asImageBitmap(),
                            contentDescription = "Скриншот банка",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showShot = false }) { Text("Закрыть") }
                }
            )
        }

        if (showPicker) {
            AlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text("Выберите приложение") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(installedApps, key = { it.packageName }) { app ->
                            TextButton(
                                onClick = {
                                    selectedPackage = app.packageName
                                    selectedLabel = app.label
                                    prefs.edit()
                                        .putString("selected_package", app.packageName)
                                        .putString("selected_label", app.label)
                                        .apply()
                                    showPicker = false
                                    status = "Выбрано: ${app.label}"
                                    DebugLog.log("UI: выбрано приложение ${app.label} (${app.packageName})")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(app.label)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPicker = false }) { Text("Закрыть") }
                }
            )
        }
    }
}
