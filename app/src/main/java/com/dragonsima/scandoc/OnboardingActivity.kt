package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.tabs.TabLayoutMediator

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button

    private val pages = listOf(
        OnboardingPage(
            icon = "📄",
            title = "Сканируйте документы",
            description = "Мгновенное сканирование с автоматическим определением границ"
        ),
        OnboardingPage(
            icon = "🔍",
            title = "Распознавайте текст",
            description = "OCR-распознавание на русском и английском"
        ),
        OnboardingPage(
            icon = "☁️",
            title = "Делитесь и храните",
            description = "Экспорт в Google Drive, защита PDF паролем"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_completed", false)) {
            // Уже пройден — сразу на главный экран
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        nextButton = findViewById(R.id.nextButton)
        skipButton = findViewById(R.id.skipButton)

        viewPager.adapter = OnboardingAdapter(this, pages)

        nextButton.setOnClickListener {
            if (viewPager.currentItem < pages.size - 1) {
                viewPager.currentItem++
            } else {
                finishOnboarding()
            }
        }

        skipButton.setOnClickListener {
            finishOnboarding()
        }

        viewPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                nextButton.text = if (position == pages.size - 1) "🚀 Начать" else "Далее →"
            }
        })
    }

    private fun finishOnboarding() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_completed", true).apply()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

data class OnboardingPage(
    val icon: String,
    val title: String,
    val description: String
)