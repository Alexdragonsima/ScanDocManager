package com.dragonsima.scandoc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Locale

/**
 * Экран ввода PIN-кода.
 * Работает в 4-х режимах: VERIFY, CREATE, CHANGE_OLD (внутри переключается на CREATE).
 */
class PinActivity : AppCompatActivity() {

    companion object {
        const val MODE_VERIFY = "verify"
        const val MODE_CREATE = "create"
        const val MODE_CHANGE_OLD = "change_old"

        const val EXTRA_MODE = "mode"

        const val PREFS_PIN = "pin_prefs"
        private const val KEY_PIN_HASH = "pin_hash"

        const val PIN_LENGTH = 4

        private const val TAG = "PinActivity"
        private const val PIN_COMPLETE_DELAY_MS = 120L
        private const val HASH_ALGORITHM = "SHA-256"
        private const val BACKSPACE = "⌫"

        // ← ДОБАВИТЬ ЭТИ ТРИ СТРОКИ:
        private const val STATE_MODE = "state_mode"
        private const val STATE_FIRST_PIN = "state_first_pin"
        private const val STATE_ENTERED_PIN = "state_entered_pin"

        private val KEYPAD_LABELS = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "", "0", "⌫"
        )


        // ==================== Публичное API ====================

        fun createIntent(context: Context, mode: String): Intent =
            Intent(context, PinActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }

        fun isPinSet(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            return prefs.contains(KEY_PIN_HASH)
        }

        fun savePin(context: Context, pin: String) {
            require(pin.isNotBlank()) { "PIN не может быть пустым" }
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        }

        fun checkPin(context: Context, pin: String): Boolean {
            if (pin.isBlank()) return false
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
            return storedHash == hashPin(pin)
        }

        fun clearPin(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PIN_HASH).apply()
        }

        // ==================== Приватное ====================

        private fun hashPin(pin: String): String {
            return try {
                val md = MessageDigest.getInstance(HASH_ALGORITHM)
                val bytes = md.digest(pin.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось захешировать PIN", e)
                // Fallback — сам PIN как хэш (лучше, чем ничего)
                pin.hashCode().toString()
            }
        }
    }

    // ==================== Состояние ====================
    private var mode = MODE_VERIFY
    private var enteredPin = StringBuilder()
    private var firstPinForCreate: String? = null

    // UI
    private lateinit var dotsContainer: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var errorText: TextView
    private lateinit var keypad: GridLayout

    // ==================== Жизненный цикл ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_pin)

        // Восстанавливаем состояние при пересоздании (поворот экрана)
        if (savedInstanceState != null) {
            mode = savedInstanceState.getString(STATE_MODE, MODE_VERIFY)
            firstPinForCreate = savedInstanceState.getString(STATE_FIRST_PIN)
            savedInstanceState.getString(STATE_ENTERED_PIN)?.let { enteredPin.append(it) }
        } else {
            mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
        }

        initViews()
        setupKeypad()
        updateTitle()
        updateDots()
        setupBackHandler()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode)
        outState.putString(STATE_FIRST_PIN, firstPinForCreate)
        outState.putString(STATE_ENTERED_PIN, enteredPin.toString())
    }

    // ==================== Инициализация ====================

    private fun initViews() {
        dotsContainer = findViewById(R.id.pinDotsContainer)
        titleText = findViewById(R.id.pinTitleText)
        subtitleText = findViewById(R.id.pinSubtitleText)
        errorText = findViewById(R.id.pinErrorText)
        keypad = findViewById(R.id.pinKeypad)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == MODE_VERIFY) {
                    // Не даём обойти PIN — сворачиваем приложение
                    moveTaskToBack(true)
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        })
    }

    private fun updateTitle() {
        when (mode) {
            MODE_CREATE -> {
                if (firstPinForCreate == null) {
                    titleText.text = getString(R.string.pin_create_title)
                    subtitleText.text = getString(R.string.pin_create_subtitle)
                } else {
                    titleText.text = getString(R.string.pin_confirm_title)
                    subtitleText.text = getString(R.string.pin_confirm_subtitle)
                }
            }
            MODE_CHANGE_OLD -> {
                titleText.text = getString(R.string.pin_old_title)
                subtitleText.text = getString(R.string.pin_old_subtitle)
            }
            else -> {
                titleText.text = getString(R.string.pin_enter_title)
                subtitleText.text = getString(R.string.pin_enter_subtitle)
            }
        }
    }

    // ==================== Клавиатура ====================

    private fun setupKeypad() {
        val density = resources.displayMetrics.density
        val buttonSize = (72 * density).toInt()
        val margin = (6 * density).toInt()

        KEYPAD_LABELS.forEach { label ->
            val btn = TextView(this).apply {
                text = label
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        this@PinActivity,
                        if (label.isEmpty()) android.R.color.transparent else R.color.text_primary
                    )
                )
                if (label.isNotEmpty()) {
                    background = ContextCompat.getDrawable(this@PinActivity, R.drawable.bg_pin_key)
                    isClickable = true
                    isFocusable = true
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = buttonSize
                    height = buttonSize
                    setMargins(margin, margin, margin, margin)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setOnClickListener { onKeyPressed(label) }
            }
            keypad.addView(btn)
        }
    }

    private fun onKeyPressed(label: String) {
        errorText.visibility = View.GONE

        when (label) {
            BACKSPACE -> {
                if (enteredPin.isNotEmpty()) {
                    enteredPin.deleteCharAt(enteredPin.length - 1)
                    updateDots()
                }
            }
            "" -> { /* пустая ячейка */ }
            else -> {
                if (enteredPin.length < PIN_LENGTH) {
                    enteredPin.append(label)
                    updateDots()
                    if (enteredPin.length == PIN_LENGTH) {
                        // Небольшая задержка, чтобы пользователь увидел заполненные точки
                        dotsContainer.postDelayed({
                            if (isFinishing || isDestroyed) return@postDelayed
                            onPinComplete()
                        }, PIN_COMPLETE_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun updateDots() {
        dotsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val dotSize = (16 * density).toInt()
        val margin = (10 * density).toInt()

        for (i in 0 until PIN_LENGTH) {
            val filled = i < enteredPin.length
            val dot = View(this).apply {
                background = ContextCompat.getDrawable(
                    this@PinActivity,
                    if (filled) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty
                )
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(margin, 0, margin, 0)
                }
            }
            dotsContainer.addView(dot)
        }
    }

    // ==================== Обработка PIN ====================

    private fun onPinComplete() {
        val pin = enteredPin.toString()

        when (mode) {
            MODE_VERIFY -> handleVerify(pin)
            MODE_CREATE -> handleCreate(pin)
            MODE_CHANGE_OLD -> handleChangeOld(pin)
        }
    }

    private fun handleVerify(pin: String) {
        if (checkPin(this, pin)) {
            ScanDocApp.isUnlocked = true
            setResult(RESULT_OK)
            finish()
        } else {
            showErrorAndReset(getString(R.string.pin_error_wrong))
        }
    }

    private fun handleCreate(pin: String) {
        if (firstPinForCreate == null) {
            // Первый ввод — запоминаем и просим подтверждение
            firstPinForCreate = pin
            enteredPin.clear()
            updateDots()
            updateTitle()
            return
        }

        // Второй ввод — сравниваем
        if (firstPinForCreate == pin) {
            savePin(this, pin)
            ScanDocApp.isUnlocked = true
            Toast.makeText(this, getString(R.string.pin_set_success), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            firstPinForCreate = null
            showErrorAndReset(getString(R.string.pin_error_mismatch))
            updateTitle()
        }
    }

    private fun handleChangeOld(pin: String) {
        if (checkPin(this, pin)) {
            ScanDocApp.isUnlocked = true
            mode = MODE_CREATE
            firstPinForCreate = null
            enteredPin.clear()
            updateDots()
            updateTitle()
        } else {
            showErrorAndReset(getString(R.string.pin_error_wrong))
        }
    }

    private fun showErrorAndReset(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
        enteredPin.clear()
        updateDots()
    }

    // ==================== Сохранение состояния ====================
}