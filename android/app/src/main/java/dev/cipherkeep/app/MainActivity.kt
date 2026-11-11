package dev.cipherkeep.app

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.cipherkeep.app.ui.CipherKeepApp
import dev.cipherkeep.app.data.SensitiveClipboard

class MainActivity : FragmentActivity() {
    override fun onStop() {
        super.onStop()
        SensitiveClipboard.clearIfOwned(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            CipherKeepApp()
        }
    }
}
