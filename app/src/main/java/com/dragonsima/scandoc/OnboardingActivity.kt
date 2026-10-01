package com.dragonsima.scandoc

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback

class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"
        private const val PREFS_APP = "app_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button

    private val pages = listOf(
        OnboardingPage(
            iconRes = R.drawable.ic_camera,
            titleRes = R.string.onboarding_page1_title,
            descRes = R.string.onboarding_page1_desc
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_search,
            titleRes = R.string.onboarding_page2_title,
            descRes = R.string.onboarding_page2_desc
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_cloud,
            titleRes = R.string.onboarding_page3_title,
            descRes = R.string.onboarding_page3_desc
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Уже пройден — сразу на главную
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.viewPager)
        nextButton = findViewById(R.id.nextButton)
        skipButton = findViewById(R.id.skipButton)

        viewPager.adapter = OnboardingAdapter(pages)
        skipButton.text = getString(R.string.onboarding_skip)

        // Явная установка текста для первой страницы
        updateNextButtonText(0)

        nextButton.setOnClickListener {
            if (viewPager.currentItem < pages.size - 1) {
                viewPager.currentItem++
            } else {
                finishOnboarding()
            }
        }

        skipButton.setOnClickListener { finishOnboarding() }

        viewPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNextButtonText(position)
            }
        })

        // Back = пропустить онбординг (не блокировать пользователя)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishOnboarding()
            }
        })
    }

    private fun updateNextButtonText(position: Int) {
        nextButton.text = if (position == pages.size - 1) {
            getString(R.string.onboarding_start)
        } else {
            getString(R.string.onboarding_next)
        }
    }

    private fun finishOnboarding() {
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_ONBOARDING_DONE, true) }

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

data class OnboardingPage(
    val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int
)