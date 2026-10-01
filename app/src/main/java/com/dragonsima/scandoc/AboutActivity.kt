package com.dragonsima.scandoc

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        val versionName = getVersionName()

        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.about_version, versionName)

        findViewById<View>(R.id.feedbackRow).setOnClickListener {
            sendFeedback(versionName)
        }

        findViewById<View>(R.id.privacyRow).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        findViewById<View>(R.id.licensesRow).setOnClickListener {
            showLicenses()
        }
    }

    // ==================== ВЕРСИЯ ====================

    private fun getVersionName(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "1.0"
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось получить версию", e)
            "1.0"
        }
    }

    // ==================== ОБРАТНАЯ СВЯЗЬ ====================

    private fun sendFeedback(versionName: String) {
        val email = getString(R.string.about_feedback_email)
        val body = getString(
            R.string.about_feedback_body,
            versionName,
            Build.VERSION.RELEASE
        )

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_feedback_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Нет почтового приложения", e)
            Toast.makeText(this, getString(R.string.about_no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== ССЫЛКИ ====================

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Не удалось открыть ссылку: $url", e)
            Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLicenses() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_licenses))
            .setMessage(getString(R.string.about_licenses_text))
            .setPositiveButton(getString(R.string.common_ok), null)
            .show()
    }

    companion object {
        private const val TAG = "AboutActivity"
    }
}