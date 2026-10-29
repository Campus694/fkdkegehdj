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
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1) PIN login (learn + replay) — unchanged.
 * 2) Small floating panel with ONE action: memorize the refresh button tap.
 * 3) "Показать баланс" in the app: open bank -> tap refresh -> read balance
 *    (a11y text first, then screenshot + ML Kit OCR; Android 11+).
 */
class BankAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS = "payment_helper"
        private const val LOGIN_WINDOW_MS = 150_000L
        private const val TAP_INTERVAL_MS = 450L
        private const val PIN_LENGTH = 5
        private const val SETTLE_AFTER_LAST_TAP_MS = 1_000L
        private const val BALANCE_SETTLE_BEFORE_TAP_MS = 3_000L
        private const val BALANCE_READ_AFTER_TAP_MS = 3_500L
        private const val BALANCE_TIMEOUT_MS = 45_000L

        @Volatile var instance: BankAccessibilityService? = null
            private set

        // --- PIN login state ---
        @Volatile private var loginStartedAt: Long = 0L
        @Volatile private var loginPackage: String? = null
        @Volatile private var awaitingResult: Boolean = false
        @Volatile private var replayInFlight: Boolean = false
        private var learnMode: Boolean = true
        private var manualHintShown = false
        private var savedSteps: List<Pair<Float, Float>> = emptyList()

        // --- Balance state ---
        @Volatile private var balanceStartedAt: Long = 0L
        @Volatile private var balancePackage: String? = null
        @Volatile private var balanceTapped = false

        fun beginLoginCheck(context: Context, packageName: String) {
            loginStartedAt = SystemClock.elapsedRealtime()
            loginPackage = packageName
            awaitingResult = false
            replayInFlight = false
            manualHintShown = false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("login_package", packageName)
                .putLong("login_started_at", System.currentTimeMillis())
                .putLong("login_result_time", 0L)
            savedSteps = loadSteps(context)
            learnMode = savedSteps.isEmpty()
            prefs.apply()
            DebugLog.log(
                if (learnMode) "Первый запуск: введите пин-код вручную — запомню каждое нажатие."
                else "Ввожу пин-код автоматически (${savedSteps.size} нажатий)."
            )
        }

        private fun loadSteps(context: Context): List<Pair<Float, Float>> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("pin_steps", null) ?: return emptyList()
            return raw.split(";").mapNotNull { pair ->
                val parts = pair.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    if (x != null && y != null) x to y else null
                } else null
            }
        }

        fun clearLearned(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("pin_steps").apply()
            savedSteps = emptyList()
            learnMode = true
            DebugLog.log("Запомненный пин-код сброшен. Следующий вход — обучение.")
        }

        fun isRefreshLearned(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("refresh_x")

        fun clearRefresh(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("refresh_x").remove("refresh_y").apply()
            DebugLog.log("Кнопка обновления сброшена.")
        }

        /** Arms a full-screen catcher: the next tap anywhere is recorded. */
        fun beginRefreshLearning() {
            DebugLog.log("Тапни по кнопке обновления — запомню это место.")
            instance?.armCapture()
        }

        /** Starts a balance check: the bank is brought to front by the caller. */
        fun beginBalanceCheck(context: Context, packageName: String) {
            if (!isRefreshLearned(context)) {
                DebugLog.log("Сначала добавьте кнопку обновления через панель.")
                return
            }
            balanceStartedAt = SystemClock.elapsedRealtime()
            balancePackage = packageName
            balanceTapped = false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("balance_result_time", 0L)
                .apply()
            DebugLog.log("Открываю банк и нажимаю обновление…")
        }

        private fun publishBalance(context: Context, message: String, balance: String?) {
            balanceStartedAt = 0L
            balanceTapped = false
            balancePackage = null
            DebugLog.log(message)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("balance_result", message)
                .putString("last_balance", balance)
                .putLong("balance_result_time", System.currentTimeMillis())
                .apply()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            try { onTick() } catch (e: Exception) {
                DebugLog.log("Ошибка сервиса: ${e.message}")
            } finally {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private val digitRegex = Regex("^\\d$")
    private val loginScreenRegex = Regex("(?i)пин|введите|ключ|войти|passcode|password")
    private val errorRegex = Regex(
        "(?i)неверн|неправильн|ошибк|не удалось войти|повторите попытку|заблокирован|incorrect|wrong|invalid|try again"
    )
    private val homeWords = listOf(
        "карты", "переводы", "платежи", "история", "баланс", "главная",
        "последние", "favorites", "payments", "history", "main"
    )
    private val amountRegex = Regex("\\d+(?:[\\d\\s\\u00A0'.,]*\\d)?")
    private val currencyRegex = Regex("(?i)BYN|руб\\.?|₽|RUB|USD|EUR|€|\\$")
    private val amountWithCurrencyRegex = Regex("(${amountRegex.pattern})\\s*($currencyRegex.pattern)")
    private val labelAmountRegex = Regex(
        "(?i)(?:баланс|доступно|остаток)\\D{0,20}?(${amountRegex.pattern})\\s*($currencyRegex.pattern)"
    )
    private val currencyAmountRegex = Regex(
        "(?i)($currencyRegex.pattern)\\s*(${amountRegex.pattern})"
    )
    private val plainAmountRegex = Regex(
        "\\d{1,3}(?:[\\s\\u00A0']\\d{3})+[.,]\\d{2}|\\d+[.,]\\d{2}"
    )

    // --- Tap capture + panel ---
    private var captureView: android.view.View? = null
    private var panelView: android.view.View? = null
    private var panelStatus: TextView? = null
    private var panelAddBtn: Button? = null
    private var panelGetBtn: Button? = null
    @Volatile private var balanceForPanel = false
    @Volatile private var panelBalanceInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeCapture()
        hidePanel()
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // ================= Tap capture (any tap on screen) =================

    fun armCapture() {
        if (captureView != null) return
        handler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val catcher = android.view.View(this)
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
                        panelGetBtn?.visibility = android.view.View.VISIBLE
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

    // ================= Small floating panel =================

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
        status.text = if (isRefreshLearned(applicationContext)) "Кнопка обновления задана."
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
        getBtn.visibility = if (isRefreshLearned(applicationContext)) android.view.View.VISIBLE else android.view.View.GONE
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

    /** «Получить баланс» прямо с панели: тап по обновлению + чтение, результат на панели. */
    private fun panelBalanceRun() {
        if (panelBalanceInFlight) return
        if (!isRefreshLearned(this)) {
            panelStatus?.text = "Сначала добавь кнопку обновления."
            return
        }
        panelBalanceInFlight = true
        panelStatus?.text = "Нажимаю обновление…"
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = prefs.getFloat("refresh_x", 0f)
        val y = prefs.getFloat("refresh_y", 0f)
        handler.postDelayed({
            DebugLog.log("Панель: нажимаю обновление баланса…")
            tapAt(x, y) { }
            panelStatus?.text = "Читаю баланс…"
        }, 800L)
        handler.postDelayed({
            balanceForPanel = true
            readBalance()
        }, 800L + BALANCE_READ_AFTER_TAP_MS)
    }

    /** Publishes result: shows balance on the panel if started from it; screenshot path always returns to the app. */
    private fun finishBalance(message: String, value: String?) {
        publishBalance(applicationContext, message, value)
        if (balanceForPanel && value != null) {
            balanceForPanel = false
            panelBalanceInFlight = false
            panelStatus?.text = "Баланс: $value"
        } else {
            balanceForPanel = false
            panelBalanceInFlight = false
            returnToApp()
        }
    }

    // ================= Events =================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        // ignore clicks on our own panel/UI
        if (event.packageName?.toString() == applicationContext.packageName) return
        val node = event.source ?: return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return

        // PIN learning taps (old flow)
        val pkg = event.packageName?.toString() ?: return
        if (loginStartedAt != 0L && learnMode && pkg == loginPackage) {
            val label = node.text?.toString()?.trim().orEmpty()
            if (digitRegex.matches(label)) {
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val current = prefs.getString("pin_draft", "") ?: ""
                val updated = if (current.isEmpty()) "${rect.exactCenterX()},${rect.exactCenterY()}"
                    else "$current;${rect.exactCenterX()},${rect.exactCenterY()}"
                prefs.edit().putString("pin_draft", updated).apply()
                val count = updated.split(";").size
                DebugLog.log("Запомнено нажатие №$count.")
                if (count >= PIN_LENGTH) {
                    prefs.edit().putString("pin_steps", updated).remove("pin_draft").apply()
                    savedSteps = loadSteps(this)
                    learnMode = false
                    publishResult("Пин-код запомнен ($count нажатий). Дальше справлюсь сам!")
                }
            }
        }
    }

    // ================= Tick state machines =================

    private fun onTick() {
        // --- Balance check ---
        if (balanceStartedAt != 0L) {
            val now = SystemClock.elapsedRealtime()
            if (now - balanceStartedAt > BALANCE_TIMEOUT_MS) {
                publishBalance(this, "Не удалось получить баланс за отведённое время.", null)
                returnToApp()
                return
            }
            val root = rootInActiveWindow
            if (root == null || root.packageName?.toString() != balancePackage) return

            if (!balanceTapped) {
                if (now - balanceStartedAt < BALANCE_SETTLE_BEFORE_TAP_MS) return
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                tapAt(prefs.getFloat("refresh_x", 0f), prefs.getFloat("refresh_y", 0f)) { }
                balanceTapped = true
                DebugLog.log("Нажал обновление, жду свежие данные…")
                handler.postDelayed({ readBalance() }, BALANCE_READ_AFTER_TAP_MS)
            }
            return
        }

        // --- PIN login ---
        if (loginStartedAt == 0L) return
        val bank = loginPackage ?: return
        val now = SystemClock.elapsedRealtime()

        if (now - loginStartedAt > LOGIN_WINDOW_MS) {
            publishResult("Время вышло, вход не подтверждён. Попробуйте ещё раз.")
            return
        }

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != bank) return

        val texts = mutableListOf<String>()
        collectVisibleText(root, texts, 0)
        val screenText = texts.joinToString(" ")

        if (awaitingResult) {
            when {
                looksLikeLoggedInHome(screenText) -> publishResult("Красава, вход выполнен!")
                errorRegex.containsMatchIn(screenText) ->
                    publishResult("Вход не выполнен: банк сообщил об ошибке. Проверьте пин-код.")
            }
            return
        }

        if (learnMode) {
            if (!manualHintShown) {
                manualHintShown = true
                DebugLog.log("Жду ваш пин-код: просто введите его на клавиатуре банка.")
            }
        } else if (!replayInFlight) {
            replayPin()
        }
    }

    // ================= Balance reading =================

    /** Collects text from ALL windows (not only the active one). */
    private fun allScreenTexts(): List<String> {
        val out = mutableListOf<String>()
        try {
            val roots = mutableListOf(rootInActiveWindow)
            try { windows.forEach { roots.add(it.root) } } catch (_: Exception) {}
            roots.filterNotNull().forEach { collectVisibleText(it, out, 0) }
        } catch (e: Exception) {
            DebugLog.log("Ошибка чтения экрана: ${e.message}")
        }
        return out
    }

    private fun readBalance() {
        // The balance may be masked — try to tap the eye toggle first.
        if (tapMaskToggle()) {
            DebugLog.log("Нашёл кнопку показа баланса, нажимаю её…")
            handler.postDelayed({ doRead() }, 1_200L)
        } else {
            doRead()
        }
    }

    private fun doRead() {
        val texts = allScreenTexts()
        if (texts.isNotEmpty()) {
            DebugLog.log("Текст на экране: ${texts.joinToString(" | ").take(500)}")
            extractBalance(texts)?.let { value ->
                finishBalance("Баланс получен: $value", value)
                return
            }
            DebugLog.log("В тексте экрана сумму не нашёл, пробую скриншот…")
        }
        takeScreenshotAndRead()
    }

    /** Tries to tap a “show balance” (eye) toggle if the app hides the amount. */
    private fun tapMaskToggle(): Boolean {
        val root = rootInActiveWindow ?: return false
        val re = Regex("(?i)показать баланс|показ|глаз|eye|скрыт")
        var tapped = false
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || tapped) return
            val desc = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
            if (node.isClickable && re.containsMatchIn(desc) && desc.length < 60) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    tapAt(r.exactCenterX(), r.exactCenterY()) { }
                    tapped = true
                    return
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return tapped
    }

    private fun extractBalance(texts: List<String>): String? {
        val joined = texts.joinToString(" | ")
        labelAmountRegex.find(joined)?.let {
            return clean(it.groupValues[1] + " " + it.groupValues[2])
        }
        for (i in texts.indices) {
            amountWithCurrencyRegex.find(texts[i])?.let {
                return clean(it.groupValues[1] + " " + it.groupValues[2])
            }
        }
        amountWithCurrencyRegex.find(joined)?.let {
            return clean(it.groupValues[1] + " " + it.groupValues[2])
        }
        // currency written BEFORE the amount: “BYN 0.57”
        currencyAmountRegex.find(joined)?.let {
            return clean(it.groupValues[1] + " " + it.groupValues[2])
        }
        // last resort: any decimal number like “1 234,56” or “0.57”
        plainAmountRegex.find(joined)?.let {
            return clean(it.value)
        }
        return null
    }

    private fun takeScreenshotAndRead() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            finishBalance("Баланс не найден: нужен Android 11+ для скриншота.", null)
            return
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    hardwareBuffer.close()
                    if (bitmap == null) {
                        finishBalance("Скриншот не удался.", null)
                        return
                    }
                    val safe = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    // Save the screenshot so the app can show it to the user.
                    val file = java.io.File(applicationContext.filesDir, "balance_shot.png")
                    file.outputStream().use { safe.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("shot_path", file.absolutePath)
                        .putLong("shot_time", System.currentTimeMillis())
                        .apply()
                    finishBalance("Скриншот готов, показываю в приложении.", null)
                } catch (e: Exception) {
                    finishBalance("Ошибка скриншота: ${e.message}", null)
                }
            }
            override fun onFailure(errorCode: Int) {
                finishBalance("Скриншот запрещён системой (код $errorCode).", null)
            }
        })
    }

    // ================= PIN login helpers =================

    private fun returnToApp() {
        val back = packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        if (back != null) {
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(back)
        }
    }

    private fun replayPin() {
        if (savedSteps.isEmpty()) return
        replayInFlight = true
        DebugLog.log("Начинаю ввод пин-кода через 7 секунд…")
        val startDelayMs = 7_000L
        savedSteps.forEachIndexed { index, (x, y) ->
            handler.postDelayed({
                DebugLog.log("Нажимаю цифру ${index + 1} из ${savedSteps.size}…")
                tapAt(x, y) { }
            }, startDelayMs + index * TAP_INTERVAL_MS)
        }
        handler.postDelayed({
            awaitingResult = true
            replayInFlight = false
        }, startDelayMs + savedSteps.size * TAP_INTERVAL_MS + SETTLE_AFTER_LAST_TAP_MS)
    }

    private fun tapAt(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) = callback(true)
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                DebugLog.log("Нажатие отменено системой.")
                callback(false)
            }
        }, null)
        if (!dispatched) callback(false)
    }

    private fun looksLikeLoggedInHome(allText: String): Boolean {
        val all = allText.lowercase()
        if (loginScreenRegex.containsMatchIn(all)) return false
        return homeWords.count { it in all } >= 2
    }

    private fun publishResult(message: String) {
        loginStartedAt = 0L
        awaitingResult = false
        loginPackage = null
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pin_draft").apply()
        DebugLog.log(message)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("login_result", message)
            .putLong("login_result_time", System.currentTimeMillis())
            .apply()
        val back = prefs.getString("login_package", null)?.let { packageManager.getLaunchIntentForPackage(it) }
        if (back != null) {
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(back)
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 25 || out.size > 500) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        for (i in 0 until node.childCount) collectVisibleText(node.getChild(i), out, depth + 1)
    }

    private fun clean(s: String): String = s.replace(Regex("[\\s\\u00A0]+"), " ").trim()
}
