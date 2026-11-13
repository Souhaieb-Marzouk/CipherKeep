package dev.cipherkeep.app.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.cipherkeep.app.passkeys.CipherKeepCredentialProvider
import dev.cipherkeep.app.ui.theme.CKDimens

@Composable
fun PasskeyProviderSection(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < 34) {
        Text(
            "Passkey provider requires Android 14 or newer.",
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }
    PasskeyProviderSection34(context)
}

@RequiresApi(34)
@Composable
private fun PasskeyProviderSection34(context: android.content.Context) {
    val component = remember { ComponentName(context, CipherKeepCredentialProvider::class.java) }
    var enabled by remember { mutableStateOf(true) }
    fun refresh() {
        enabled = context.packageManager.getComponentEnabledSetting(component) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Passkey provider", style = MaterialTheme.typography.titleMedium)
        Text(
            "The provider currently returns no passkeys and always falls back to another provider. Disabling removes it from the Android picker.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedButton(
            onClick = {
                context.packageManager.setComponentEnabledSetting(
                    component,
                    if (enabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    },
                    PackageManager.DONT_KILL_APP,
                )
                refresh()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (enabled) "Disable passkey provider" else "Enable passkey provider") }
    }
}
