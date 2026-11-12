package dev.cipherkeep.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.cipherkeep.app.ui.CipherKeepApp
import dev.cipherkeep.app.data.BackupFileBridge
import dev.cipherkeep.app.data.SensitiveClipboard

class MainActivity : FragmentActivity() {
    override fun onStop() {
        super.onStop()
        SensitiveClipboard.clearIfOwned(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                when (requestCode) {
                    BackupFileBridge.REQUEST_CODE -> BackupFileBridge.handler?.invoke(uri)
                    BackupFileBridge.IMPORT_REQUEST_CODE -> BackupFileBridge.importHandler?.invoke(uri)
                }
            }
        }
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
