package dev.cipherkeep.app.data

import android.net.Uri

/** Fixed-request-code bridge for the backup document picker. */
object BackupFileBridge {
    const val REQUEST_CODE = 1001
    const val IMPORT_REQUEST_CODE = 1002
    @Volatile
    var handler: ((Uri) -> Unit)? = null
    @Volatile
    var importHandler: ((Uri) -> Unit)? = null
}
