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
 *     sends it to the Mistral API which returns a single balance number;
 *  3) result is shown on the panel and in the app.
 */
class BankAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS = "payment_helper"
        private const val READ_AFTER_TAP_MS = 3_500L
        private const val TAP_DELAY_MS = 800L
        private const val MISTRAL_KEY = "mstrl_JygTEGiEiL26LVTw3MJEm9Te7DwLPIox_0Buh0j"
        private const val MISTRAL_MODEL = "mistral-small-latest"
        private const val MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions"

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

    // ================= Balance: tap refresh -> read screen -> Mistral =================

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
        askMistral(screenText)
    }

    private fun askMistral(screenText: String) {
        panelStatus?.text = "Спрашиваю у нейросети…"
        DebugLog.log("Отправляю текст в Mistral ($MISTRAL_MODEL)…")
        Thread {
            var answer: String? = null
            var error: String? = null
            try {
                val conn = URL(MISTRAL_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $MISTRAL_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                val prompt = "Это текст экрана банковского приложения. Найди баланс по карте. " +
                        "Ответь ТОЛЬКО одним числом с копейками, например: 0.57. Никаких слов, только число.\n\n" +
                        "Текст экрана:\n$screenText"
                val payload = JSONObject()
                    .put("model", MISTRAL_MODEL)
                    .put("temperature", 0)
                    .put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt)))
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    answer = JSONObject(body)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                } else {
                    error = "HTTP $code: ${body.take(200)}"
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            handler.post {
                if (answer != null) {
                    DebugLog.log("Mistral ответил: $answer")
                    finishBalance("Баланс: $answer", answer)
                } else {
                    finishBalance("Mistral не ответил: $error", null)
                }
            }
        }.start()
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
