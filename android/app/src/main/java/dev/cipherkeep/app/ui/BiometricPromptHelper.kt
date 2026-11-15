package dev.cipherkeep.app.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Whether this device can satisfy the biometric-or-device-credential gate. */
fun canUseBiometrics(activity: FragmentActivity): Boolean {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return BiometricManager.from(activity).canAuthenticate(allowed) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Show a plain authentication prompt. The Keystore key is time-bound, so the
 * caller must run its encrypt/decrypt immediately after [onSuccess].
 */
fun promptBiometricAuthentication(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure(errString.toString())
            }

            override fun onAuthenticationFailed() {
                // Keep the prompt open; the framework reports a terminal error
                // if the user cancels or exhausts attempts.
            }
        },
    )
    prompt.authenticate(promptInfo(title, subtitle))
}

private fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    return builder.build()
}
