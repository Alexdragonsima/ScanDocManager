package com.dragonsima.scandoc

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
    }
}