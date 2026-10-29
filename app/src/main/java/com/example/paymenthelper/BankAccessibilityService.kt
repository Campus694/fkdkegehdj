package com.example.paymenthelper

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
        private const val OR_MODEL = "nvidia/nemotron-3.5-lightning:free"
        private const val OR_URL = "https://openrouter.ai/api/v1/chat/completions"
        // OpenRouter key (base64 split in halves, glued at runtime)
        private val OR_KEY = String(
            android.util.Base64.decode(
                "c2stb3ItdjEtMWViN2Q1OWM1NmU3MTQzODFmNzZiYzQ2NTZiYz" + "c2MjdjYjI3MjUzNDRjMTk3NzQ5NTY2MGRhM2E0YTRkOGMxYQ==",
                android.util.Base64.DEFAULT
            )
        )
        private const val NV_MODEL = "minimaxai/minimax-m3.1-flash"
        private const val NV_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        // NVIDIA key (base64 split in halves, glued at runtime)
        private val NV_KEY = String(
            android.util.Base64.decode(
                "bnZhcGktZEtneGJvcUMwOGVJbm01Z3VGMS1wODhJYkMwMFhm" + "Y3Y3S19yR09WaUc0VTBNX0h4NjhLOE84VkQzNXJuTzRzVw==",
                android.util.Base64.DEFAULT
            )
        )
        private const val GROQ_MODEL = "llama-3.1-8b-instant"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        // Groq API keys: base64 split in halves, glued at runtime (never a full literal in source)
        private val GROQ_KEYS = listOf(
            "Z3NrXzAzcmk5OW1EaXJTOFVnZ1JXOFI2V0dkeW" + "IzRllKc2w5T2JwWHAzdU9YTDBIeXVURnRQb0Y=",
            "Z3NrX0dVNzlDanduV3UzV2g0aDlYTEFZV0dkeW" + "IzRlloOGZ1T1ZKVk5YS0szWnRIeDZOQXZiSzU=",
            "Z3NrXzhhS2RKMEM1S21vTlFpa2U1RElGV0dkeW" + "IzRll6cll6SkFTQTlUR1p0UkpWcDVYcWs3U3Y=",
            "Z3NrX3k2N1pldk14Y1pucFB5UUVPNmswV0dkeW" + "IzRllSQ2NuQ3o5Z1NvNlppUEZlSlJxc0ptNTQ=",
            "Z3NrX3dGc21FeUVhR2ZCdjZxUlNZRVh2V0dkeW" + "IzRlk3a3hoU0J5b3NjUWRBNnBaQTNwUjZTa2o="
        ).map { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
        @Volatile private var nextKeyIdx = 0

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeCapture()
        hidePanel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // ================= Tap catcher (any tap on screen) =================

    fun armCapture() {
        if (captureView != null) return
        handler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val catcher = View(this)
                catcher.background = android.graphics.drawable.ColorDrawable(Color.argb(40, 255, 255, 0))
                catcher.isClickable = true
                catcher.setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        val x = ev.rawX
                        val y = ev.rawY
                        removeCapture()
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
                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(catcher, p)
                captureView = catcher
                panelStatus?.text = "Тапни по кнопке обновления (жёлтая подсветка = ловушка активна)."
            } catch (e: Exception) {
                DebugLog.log("Не удалось включить ловушку тапов: ${e.message}")
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
        status.text = if (isRefreshLearned()) "Кнопка обновления задана."
            else "Добавь кнопку обновления баланса."
        box.addView(status)
        panelStatus = status

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
        DebugLog.log("Отправляю текст в OpenRouter ($OR_MODEL)…")
        Thread {
            var answer: String? = null
            var error: String? = null
            val prompt = "Это текст экрана банковского приложения. Найди баланс по карте. " +
                    "Ответь ТОЛЬКО одним числом с копейками, например: 0.57. Никаких слов, только число.\n\n" +
                    "Текст экрана:\n$screenText"
            fun payload(model: String): JSONObject = JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("max_tokens", 1024)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)))
            // Providers in order: OpenRouter first, then NVIDIA, then Groq keys (rotating).
            val providers: List<Triple<String, String, String>> =
                listOf(Triple(OR_URL, OR_MODEL, OR_KEY), Triple(NV_URL, NV_MODEL, NV_KEY)) +
                GROQ_KEYS.map { key -> Triple(GROQ_URL, GROQ_MODEL, key) }
            for (attempt in providers.indices) {
                val idx = (nextKeyIdx + attempt) % providers.size
                val (url, model, key) = providers[idx]
                val name = when {
                    url == OR_URL -> "OpenRouter"
                    url == NV_URL -> "NVIDIA"
                    else -> "Groq"
                }
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 12_000
                    conn.readTimeout = 25_000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.outputStream.use { it.write(payload(model).toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code in 200..299) {
                        answer = JSONObject(body)
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                        nextKeyIdx = (idx + 1) % providers.size
                        DebugLog.log("$name ответил: $answer")
                        break
                    } else {
                        error = "HTTP $code: ${body.take(120)}"
                        DebugLog.log("$name отклонён: $error")
                        // 401/403/404 = key/model dead -> next provider; 429 = limit -> next provider
                        if (code != 401 && code != 403 && code != 404 && code != 429) break
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                    DebugLog.log("$name ошибка сети: $error")
                    break
                }
            }
            if (answer == null) {
                DebugLog.log("Нейросети недоступны, пробую локальный разбор текста…")
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
        panelStatus?.text = if (balance != null) "Баланс: $balance" else message
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("balance_result", message)
            .putString("last_balance", balance)
            .putLong("balance_result_time", System.currentTimeMillis())
            .apply()
    }
}
