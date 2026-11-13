package dev.cipherkeep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.webauthn.WebAuthnEnrollment
import dev.cipherkeep.app.data.webauthn.WebAuthnPrfManager
import dev.cipherkeep.app.data.webauthn.WebAuthnStore
import dev.cipherkeep.app.ui.theme.CKDimens
import kotlinx.coroutines.launch

@Composable
fun WebAuthnSection(context: android.content.Context, controller: SessionController) {
    val manager = remember { WebAuthnPrfManager(context) }
    val store = remember { WebAuthnStore(context) }
    val scope = rememberCoroutineScope()
    var enrollment by remember { mutableStateOf<WebAuthnEnrollment?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { enrollment = store.load() }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Hardware unlock (WebAuthn PRF)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional. The authenticator releases a PRF-derived wrapping key after user presence. Password and recovery shares remain fallbacks.",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            val (enrolled, prf) = manager.enroll().getOrThrow()
                            val sealed = controller.webauthnSeal(prf)
                            prf.fill(0)
                            val record = enrolled.copy(sealed = sealed)
                            store.save(record)
                            enrollment = record
                            message = "Enrolled. Password and recovery shares remain valid."
                        }.onFailure {
                            message = "Unsupported authenticator, cancelled, or no PRF result. Password unlock remains available."
                        }
                    }
                },
            ) { Text("Enroll security key") }
            OutlinedButton(
                enabled = enrollment != null,
                onClick = {
                    scope.launch {
                        runCatching {
                            val record = enrollment ?: error("not enrolled")
                            val prf = manager.assert(record).getOrThrow()
                            controller.webauthnOpen(record.sealed, prf).getOrThrow()
                            prf.fill(0)
                            message = "Unlocked with the security key."
                        }.onFailure { message = "Hardware unlock failed; use the master password." }
                    }
                },
            ) { Text("Unlock") }
            OutlinedButton(
                enabled = enrollment != null,
                onClick = {
                    store.clear()
                    enrollment = null
                    message = "Hardware wrapping removed; password-only unlock remains."
                },
            ) { Text("Remove") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
