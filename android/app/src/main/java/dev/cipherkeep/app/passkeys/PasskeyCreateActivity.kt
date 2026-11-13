package dev.cipherkeep.app.passkeys

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import org.json.JSONObject

/** Explicit consent + device-credential gate for passkey creation. */
class PasskeyCreateActivity : FragmentActivity() {
    private var origin = ""
    private var requestJson = ""
    private var rpId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val calling = provider?.callingRequest as? CreatePublicKeyCredentialRequest
        val info = provider?.callingAppInfo
        if (calling == null || info == null) {
            finish()
            return
        }
        origin = if (info.isOriginPopulated()) info.getOrigin(info.packageName).orEmpty() else ""
        requestJson = calling.requestJson
        rpId = runCatching {
            JSONObject(requestJson).getJSONObject("rp").optString("id")
        }.getOrDefault("")
        if (rpId.isEmpty()) rpId = originHost(origin).orEmpty()
        if (!originMatchesRp(origin, rpId)) {
            finish()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = "Create a CipherKeep passkey"
            textSize = 24f
        })
        layout.addView(TextView(this).apply {
            text = "Origin: $origin\nRP ID: $rpId\n\nThe next step requires your device credential."
            textSize = 16f
            setPadding(0, 32, 0, 32)
            contentDescription = "Create passkey for $origin. Device credential required."
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
                    runCatching { createRegistration(this@PasskeyCreateActivity, requestJson, origin) }
                        .onSuccess { json ->
                            PendingIntentHandler.setCreateCredentialResponse(
                                intent,
                                CreatePublicKeyCredentialResponse(json),
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
                .setTitle("Create passkey")
                .setSubtitle(origin)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }
}
