package com.dragonsima.scandoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        // Версия
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.versionText).text = "Версия $versionName"

        // Обратная связь
        findViewById<android.view.View>(R.id.feedbackRow).setOnClickListener {
            val email = "feedback@dragonsima.com"
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, "ScanDoc Feedback")
                putExtra(Intent.EXTRA_TEXT, "\n\n---\nВерсия: $versionName\nAndroid: ${android.os.Build.VERSION.RELEASE}")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Нет почтового приложения", Toast.LENGTH_SHORT).show()
            }
        }

        // Политика конфиденциальности
        findViewById<android.view.View>(R.id.privacyRow).setOnClickListener {
            val url = "https://dragonsima.com/privacy"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
            }
        }

        // Лицензии
        findViewById<android.view.View>(R.id.licensesRow).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_licenses))
                .setMessage(getString(R.string.about_licenses_text))
                .setPositiveButton(getString(R.string.common_ok), null)
                .show()
        }
    }
}