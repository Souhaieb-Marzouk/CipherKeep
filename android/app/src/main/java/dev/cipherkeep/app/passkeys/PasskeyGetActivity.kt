package dev.cipherkeep.app.passkeys

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

/** Explicit consent + device-credential gate for passkey assertions. */
class PasskeyGetActivity : FragmentActivity() {
    private var record: PasskeyRecord? = null
    private var origin = ""
    private var requestJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
        val option = provider?.credentialOptions?.filterIsInstance<GetPublicKeyCredentialOption>()?.firstOrNull()
        val info = provider?.callingAppInfo
        if (credentialId == null || option == null || info == null) {
            finish()
            return
        }
        record = PasskeyStore(this).find(credentialId)
        origin = if (info.isOriginPopulated()) info.getOrigin(info.packageName).orEmpty() else ""
        requestJson = option.requestJson
        val rpId = runCatching { JSONObject(requestJson).optString("rpId") }
            .getOrDefault("")
            .ifEmpty { record?.rpId.orEmpty() }
        if (record == null || !originMatchesRp(origin, rpId) || record?.rpId != rpId) {
            finish()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "Use a CipherKeep passkey"
            textSize = 24f
        })
        layout.addView(TextView(this).apply {
            text = "Origin: $origin\nRP ID: $rpId\n\nOnly continue if you initiated this sign-in."
            textSize = 16f
            setPadding(0, 32, 0, 32)
            contentDescription = "Use passkey for $origin. Device credential required."
        })
        layout.addView(Button(this).apply {
            text = "Continue"
            setOnClickListener { authenticate() }
        })
        layout.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        })
        setContentView(layout)
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val current = record
                    if (current == null) {
                        setResult(RESULT_CANCELED)
                        finish()
                        return
                    }
                    runCatching { createAssertion(this@PasskeyGetActivity, current, requestJson, origin) }
                        .onSuccess { json ->
                            PendingIntentHandler.setGetCredentialResponse(
                                intent,
                                GetCredentialResponse(PublicKeyCredential(json)),
                            )
                            setResult(RESULT_OK, intent)
                            finish()
                        }
                        .onFailure {
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Use passkey")
                .setSubtitle(origin)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    companion object {
        const val EXTRA_CREDENTIAL_ID = "credentialId"
    }
}
