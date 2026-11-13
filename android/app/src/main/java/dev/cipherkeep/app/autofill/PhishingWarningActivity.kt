package dev.cipherkeep.app.autofill

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding

/** Explicit review screen for a suspicious autofill origin. */
class PhishingWarningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val decoded = intent.getStringExtra(EXTRA_DECODED).orEmpty()
        val reasons = intent.getStringExtra(EXTRA_REASONS).orEmpty()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48)
        }
        layout.addView(TextView(this).apply {
            text = "Suspicious autofill origin"
            textSize = 24f
        })
        val explanation = TextView(this).apply {
            text = "ASCII: $host\nDecoded: $decoded\nReasons: $reasons\n\n" +
                "CipherKeep will not fill this origin unless you allow it explicitly. " +
                "This override is stored only on this device."
            textSize = 16f
            setPadding(0, 24, 0, 24)
            contentDescription = "Suspicious origin. ASCII $host. Decoded $decoded. Reasons $reasons. CipherKeep will not fill unless you allow it."
        }
        layout.addView(explanation)
        layout.addView(Button(this).apply {
            text = "Allow this origin"
            setOnClickListener {
                PhishingOverrideStore(this@PhishingWarningActivity).allow(host)
                finish()
            }
        })
        layout.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        setContentView(layout)
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_DECODED = "decoded"
        const val EXTRA_REASONS = "reasons"
    }
}
