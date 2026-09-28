package com.dragonsima.scandoc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Экран ввода PIN-кода.
 * Работает в 4-х режимах: CREATE, CONFIRM, VERIFY, CHANGE_OLD.
 */
class PinActivity : AppCompatActivity() {

    companion object {
        const val MODE_VERIFY = "verify"
        const val MODE_CREATE = "create"
        const val MODE_CHANGE_OLD = "change_old"

        const val EXTRA_MODE = "mode"

        const val PREFS_PIN = "pin_prefs"
        const val KEY_PIN_HASH = "pin_hash"

        const val PIN_LENGTH = 4

        fun createIntent(context: Context, mode: String): Intent {
            return Intent(context, PinActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }
        }

        /** Возвращает true, если PIN установлен. */
        fun isPinSet(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            return prefs.contains(KEY_PIN_HASH)
        }

        /** Хэш PIN-кода (простой, для локальной защиты от любопытных глаз). */
        private fun hashPin(pin: String): String {
            // Простой хэш через SHA-256
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(pin.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        /** Сохраняет PIN-код. */
        fun savePin(context: Context, pin: String) {
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        }

        /** Проверяет PIN-код. */
        fun checkPin(context: Context, pin: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
            return storedHash == hashPin(pin)
        }

        /** Удаляет PIN-код. */
        fun clearPin(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_PIN, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PIN_HASH).apply()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY

        dotsContainer = findViewById(R.id.pinDotsContainer)
        titleText = findViewById(R.id.pinTitleText)
        subtitleText = findViewById(R.id.pinSubtitleText)
        errorText = findViewById(R.id.pinErrorText)
        keypad = findViewById(R.id.pinKeypad)

        setupKeypad()
        updateTitle()
        updateDots()

        // Back = выход из приложения (если VERIFY) или отмена (если CREATE/CHANGE)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == MODE_VERIFY) {
                    // Не даём обойти PIN
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

    /**
     * Рисует кнопки 1-9, 0, backspace и пустую ячейку.
     */
    private fun setupKeypad() {
        val density = resources.displayMetrics.density
        val buttonSize = (72 * density).toInt()
        val margin = (6 * density).toInt()

        val layout = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "", "0", "⌫"
        )

        layout.forEach { label ->
            val btn = TextView(this).apply {
                text = label
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(
                    this@PinActivity,
                    if (label.isEmpty()) android.R.color.transparent else R.color.text_primary
                ))
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

    /**
     * Обработка нажатия на кнопку.
     */
    private fun onKeyPressed(label: String) {
        errorText.visibility = View.GONE

        when (label) {
            "⌫" -> {
                if (enteredPin.isNotEmpty()) {
                    enteredPin.deleteCharAt(enteredPin.length - 1)
                    updateDots()
                }
            }
            "" -> { /* пусто */ }
            else -> {
                if (enteredPin.length < PIN_LENGTH) {
                    enteredPin.append(label)
                    updateDots()
                    if (enteredPin.length == PIN_LENGTH) {
                        // Небольшая задержка, чтобы пользователь увидел заполненные точки
                        dotsContainer.postDelayed({ onPinComplete() }, 120)
                    }
                }
            }
        }
    }

    /**
     * Обновляет точки-индикаторы.
     */
    private fun updateDots() {
        dotsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val dotSize = (16 * density).toInt()
        val margin = (10 * density).toInt()

        for (i in 0 until PIN_LENGTH) {
            val dot = View(this).apply {
                val filled = i < enteredPin.length
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

    /**
     * PIN введён полностью — обрабатываем в зависимости от режима.
     */
    private fun onPinComplete() {
        val pin = enteredPin.toString()

        when (mode) {
            MODE_VERIFY -> {
                if (checkPin(this, pin)) {
                    ScanDocApp.isUnlocked = true
                    setResult(RESULT_OK)
                    finish()
                } else {
                    showError(getString(R.string.pin_error_wrong))
                    enteredPin.clear()
                    updateDots()
                }
            }
            MODE_CREATE -> {
                if (firstPinForCreate == null) {
                    firstPinForCreate = pin
                    enteredPin.clear()
                    updateDots()
                    updateTitle()
                } else {
                    if (firstPinForCreate == pin) {
                        savePin(this, pin)
                        ScanDocApp.isUnlocked = true
                        Toast.makeText(this, getString(R.string.pin_set_success), Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        showError(getString(R.string.pin_error_mismatch))
                        firstPinForCreate = null
                        enteredPin.clear()
                        updateDots()
                        updateTitle()
                    }
                }
            }
            MODE_CHANGE_OLD -> {
                if (checkPin(this, pin)) {
                    ScanDocApp.isUnlocked = true
                    mode = MODE_CREATE
                    firstPinForCreate = null
                    enteredPin.clear()
                    updateDots()
                    updateTitle()
                } else {
                    showError(getString(R.string.pin_error_wrong))
                    enteredPin.clear()
                    updateDots()
                }
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }
}