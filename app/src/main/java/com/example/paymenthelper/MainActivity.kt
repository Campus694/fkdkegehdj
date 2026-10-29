package com.example.paymenthelper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS = "payment_helper"

/** Ring buffer of log lines, shared by activity and service. */
object DebugLog {
    private const val MAX_LINES = 250
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
        setContent { Screen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var status by remember { mutableStateOf("Нажмите «Открыть панель» и задайте кнопку обновления.") }
    var balance by remember { mutableStateOf(prefs.getString("last_balance", null)) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(true) }

    fun serviceEnabled(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == BankAccessibilityService::class.java.name
            }
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
            "Панель открыта. Управляйте с неё."
        else "Служба подключается, нажмите кнопку ещё раз через пару секунд."
    }

    LaunchedEffect(Unit) {
        while (true) {
            val t = prefs.getLong("balance_result_time", 0L)
            if (t > 0L && t > prefs.getLong("shown_balance_result_time", 0L)) {
                prefs.edit().putLong("shown_balance_result_time", t).apply()
                balance = prefs.getString("last_balance", null)
                status = prefs.getString("balance_result", "") ?: ""
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
                Button(onClick = { openPanel() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть панель")
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Баланс", style = MaterialTheme.typography.titleMedium)
                Text(balance ?: "Пока нет данных", style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
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
                        modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp)
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
    }
}
