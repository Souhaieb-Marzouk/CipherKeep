package dev.cipherkeep.app.autofill

import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.SessionProvider
import dev.cipherkeep.app.data.VaultUiState
import dev.cipherkeep.app.data.keystore.BiometricKeyManager
import dev.cipherkeep.app.data.keystore.WrappedSecret
import dev.cipherkeep.app.ui.canUseBiometrics
import dev.cipherkeep.app.ui.promptBiometricAuthentication
import dev.cipherkeep.app.ui.theme.CipherKeepTheme
import kotlinx.coroutines.launch

/**
 * Translucent unlock sheet used by the autofill authentication flow.
 *
 * Android starts it when the user taps "Unlock CipherKeep" in the autofill
 * popup. After a successful unlock it returns the datasets through
 * [AutofillManager.EXTRA_AUTHENTICATION_RESULT] and finishes, so the system
 * returns to the requesting app and fills the login instead of opening the
 * CipherKeep main window.
 */
class AutofillUnlockActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val form = readForm()
        if (form == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val controller = SessionProvider.get(this)
        lifecycleScope.launch {
            if (controller.state is VaultUiState.Loading) {
                controller.bootstrap()
            }
            if (controller.state is VaultUiState.Unlocked) {
                deliver(controller, form)
            } else {
                showUnlockSheet(controller, form)
            }
        }
    }

    private fun readForm(): LoginForm? {
        val domain = intent.getStringExtra(EXTRA_DOMAIN)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        @Suppress("DEPRECATION")
        val userId = intent.getParcelableExtra(EXTRA_USER_ID) as? AutofillId
        @Suppress("DEPRECATION")
        val passwordId = intent.getParcelableExtra(EXTRA_PASSWORD_ID) as? AutofillId
        @Suppress("DEPRECATION")
        val otpIds = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_OTP_IDS).orEmpty()
        if (passwordId == null && otpIds.isEmpty()) return null
        return LoginForm(userId, passwordId, domain, packageName, otpIds)
    }

    private fun deliver(controller: SessionController, form: LoginForm) {
        val items = AutofillFlow.matchItems(this, controller.items, form)
        if (items.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val response = AutofillFlow.buildResponse(
            this,
            items,
            form,
            System.currentTimeMillis() / 1000,
        )
        val data = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun showUnlockSheet(controller: SessionController, form: LoginForm) {
        setContent {
            CipherKeepTheme {
                UnlockSheet(controller, form)
            }
        }
    }

    @Composable
    private fun UnlockSheet(controller: SessionController, form: LoginForm) {
        var password by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var wrapped by remember { mutableStateOf<WrappedSecret?>(null) }
        val scope = rememberCoroutineScope()
        val keyManager = remember { BiometricKeyManager() }
        LaunchedEffect(controller) {
            wrapped = controller.biometricWrapped()
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Unlock CipherKeep", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Unlock to fill this login. You will return to the app automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                val result = controller.unlock(password)
                                password = ""
                                busy = false
                                if (result.isSuccess) {
                                    deliver(controller, form)
                                } else {
                                    error = "Vault could not be opened. Check your master password."
                                }
                            }
                        },
                        enabled = !busy && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (busy) "Unlocking…" else "Unlock and fill")
                    }
                    if (wrapped != null && canUseBiometrics(this@AutofillUnlockActivity)) {
                        OutlinedButton(
                            onClick = {
                                val secret = wrapped ?: return@OutlinedButton
                                promptBiometricAuthentication(
                                    activity = this@AutofillUnlockActivity,
                                    title = "Unlock CipherKeep",
                                    subtitle = "Authenticate to fill this login.",
                                    onSuccess = {
                                        scope.launch {
                                            runCatching {
                                                val master = keyManager.decrypt(secret)
                                                try {
                                                    controller.unlockWithMasterKey(master).getOrThrow()
                                                } finally {
                                                    master.fill(0)
                                                }
                                            }.onSuccess {
                                                deliver(controller, form)
                                            }.onFailure {
                                                error = "Biometric unlock failed. Use your master password."
                                            }
                                        }
                                    },
                                    onFailure = { reason ->
                                        error = "Authentication failed: $reason"
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use biometrics")
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "autofill.package"
        const val EXTRA_DOMAIN = "autofill.domain"
        const val EXTRA_USER_ID = "autofill.user"
        const val EXTRA_PASSWORD_ID = "autofill.password"
        const val EXTRA_OTP_IDS = "autofill.otp"
    }
}
