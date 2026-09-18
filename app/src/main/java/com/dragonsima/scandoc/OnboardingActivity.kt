package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button

    private val pages = listOf(
        OnboardingPage(
            icon = "📄",
            titleRes = R.string.onboarding_page1_title,
            descRes = R.string.onboarding_page1_desc
        ),
        OnboardingPage(
            icon = "🔍",
            titleRes = R.string.onboarding_page2_title,
            descRes = R.string.onboarding_page2_desc
        ),
        OnboardingPage(
            icon = "☁️",
            titleRes = R.string.onboarding_page3_title,
            descRes = R.string.onboarding_page3_desc
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
        skipButton.text = getString(R.string.onboarding_skip)

        viewPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                nextButton.text = if (position == pages.size - 1) {
                    getString(R.string.onboarding_start)
                } else {
                    getString(R.string.onboarding_next)
                }
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
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int
)