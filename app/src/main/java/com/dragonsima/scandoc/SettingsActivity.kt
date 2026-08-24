package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private lateinit var darkThemeSwitch: SwitchCompat
    private lateinit var languageButton: Button
    private lateinit var onboardingButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        darkThemeSwitch = findViewById(R.id.darkThemeSwitch)
        languageButton = findViewById(R.id.languageButton)
        onboardingButton = findViewById(R.id.onboardingButton)
        backButton = findViewById(R.id.backButton)

        val prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        darkThemeSwitch.isChecked = prefs.getString("theme_mode", "light") == "dark"

        darkThemeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit {
                putString("theme_mode", if (isChecked) "dark" else "light")
            }

            // Мгновенно переключаем тему
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        languageButton.setOnClickListener {
            // Заглушка — будет позже
        }

        onboardingButton.setOnClickListener {
            val prefs2 = getSharedPreferences("app_prefs", MODE_PRIVATE)
            prefs2.edit {
                putBoolean("onboarding_completed", false)
            }
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }

        backButton.setOnClickListener { finish() }
    }
}