package dev.cipherkeep.app.passkeys

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import org.json.JSONObject

/**
 * Android passkey provider. Every create/get is user-gated and origin-bound.
 */
class CipherKeepCredentialProvider : CredentialProviderService() {
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PasskeyCreateActivity::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val entry = CreateEntry(
            "CipherKeep passkey",
            pending,
            "Create a passkey in CipherKeep",
            null,
            null,
            null,
            null,
            null,
            false,
        )
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val builder = BeginGetCredentialResponse.Builder()
        val store = PasskeyStore(this)
        val info = request.callingAppInfo ?: run {
            callback.onResult(BeginGetCredentialResponse.Builder().build())
            return
        }
        val origin = if (info.isOriginPopulated()) {
            info.getOrigin(info.packageName).orEmpty()
        } else {
            ""
        }
        for (option in request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()) {
            val rpId = runCatching {
                JSONObject(option.requestJson).optString("rpId")
            }.getOrDefault("")
            if (rpId.isEmpty() || !originMatchesRp(origin, rpId)) continue
            for (record in store.list().filter { it.rpId == rpId }) {
                val pending = PendingIntent.getActivity(
                    this,
                    record.credentialId.hashCode(),
                    Intent(this, PasskeyGetActivity::class.java)
                        .putExtra(PasskeyGetActivity.EXTRA_CREDENTIAL_ID, record.credentialId),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addCredentialEntry(
                    PublicKeyCredentialEntry.Builder(this, record.username, pending, option)
                        .setDisplayName(record.displayName)
                        .build(),
                )
            }
        }
        callback.onResult(builder.build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, androidx.credentials.exceptions.ClearCredentialException>,
    ) {
        callback.onResult(null)
    }
}
