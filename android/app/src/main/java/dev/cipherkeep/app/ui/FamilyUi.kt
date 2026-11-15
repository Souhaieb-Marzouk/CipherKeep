package dev.cipherkeep.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cipherkeep.app.data.SessionController
import dev.cipherkeep.app.data.family.FamilyStore
import dev.cipherkeep.app.ui.theme.CKDimens
import dev.cipherkeep.core.familyIdentity
import dev.cipherkeep.core.familyUnwrap
import dev.cipherkeep.core.familyWrap
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun FamilySection(context: android.content.Context, controller: SessionController) {
    val store = remember { FamilyStore(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(JSONObject()) }
    var vaultId by remember { mutableStateOf("") }
    var memberId by remember { mutableStateOf("") }
    var memberPublic by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("editor") }
    var message by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<String?>(null) }
    var selfId by remember { mutableStateOf("member-1") }
    var selfRole by remember { mutableStateOf("editor") }
    var joinVaultId by remember { mutableStateOf("") }
    var joinMemberId by remember { mutableStateOf("") }
    var joinPublic by remember { mutableStateOf("") }
    var joinNonce by remember { mutableStateOf("") }
    var joinCiphertext by remember { mutableStateOf("") }
    var joinSecret by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var joinRole by remember { mutableStateOf("editor") }
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    fun copy(label: String, value: String) {
        if (value.isBlank()) return
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
        message = "$label copied."
    }

    fun refresh() {
        state = store.load() ?: JSONObject()
    }

    LaunchedEffect(Unit) {
        refresh()
        vaultId = runCatching { controller.syncNamespace() }.getOrDefault("")
    }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Family sharing", style = MaterialTheme.typography.titleMedium)
        Text(
            "The owner wraps the vault key to each approved member. Revoking rotates the vault key and rewraps remaining members. A removed member keeps ciphertext they already downloaded; verify fingerprints out of band.",
            style = MaterialTheme.typography.labelSmall,
        )
        val owner = state.optJSONObject("owner")
        if (owner == null) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        val identity = JSONObject(familyIdentity("owner", "owner"))
                        val next = JSONObject()
                            .put("owner", identity)
                            .put("members", JSONArray())
                            .put("audit", JSONArray())
                        store.save(next)
                        state = next
                        message = "Owner identity created. Share its fingerprint out of band."
                    }.onFailure { message = "Identity generation failed." }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create family owner identity") }
        } else {
            Text("Owner fingerprint: ${owner.optString("fingerprint")}", style = MaterialTheme.typography.bodySmall)
            if (vaultId.isNotBlank()) {
                Text("Vault id (32 hex): $vaultId", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { copy("Vault id", vaultId) }) { Text("Copy vault id") }
            }
        }
        Text("Owner: invite a member", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(memberId, { memberId = it }, label = { Text("Member id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(memberPublic, { memberPublic = it }, label = { Text("Member public key (64 hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedButton(onClick = { role = "editor" }) { Text(if (role == "editor") "✓ Editor" else "Editor") }
            OutlinedButton(onClick = { role = "viewer" }) { Text(if (role == "viewer") "✓ Viewer" else "Viewer") }
        }
        OutlinedButton(
            enabled = owner != null,
            onClick = {
                scope.launch {
                    runCatching {
                        require(memberId.isNotBlank() && memberPublic.matches(Regex("^[0-9a-fA-F]{64}$")))
                        val wrapped = familyWrap(
                            controller.syncNamespace(),
                            controller.familyVaultKeyHex(),
                            memberId.trim(),
                            memberPublic.trim().lowercase(),
                            role,
                        )
                        val members = state.optJSONArray("members") ?: JSONArray()
                        val record = JSONObject()
                            .put("id", memberId.trim())
                            .put("public", memberPublic.trim().lowercase())
                            .put("role", role)
                            .put("wrapped", JSONObject(wrapped))
                        members.put(record)
                        state.put("members", members)
                        state.put("audit", (state.optJSONArray("audit") ?: JSONArray()).put(JSONObject().put("event", "invited").put("memberId", memberId.trim()).put("role", role)))
                        store.save(state)
                        memberId = ""
                        memberPublic = ""
                        message = "Member invited. Send them the envelope below, plus the vault id."
                    }.onFailure { message = "Invite failed; check the public key." }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Invite member") }

        val members = state.optJSONArray("members") ?: JSONArray()
        if (members.length() == 0) {
            Text("No members yet.", style = MaterialTheme.typography.labelSmall)
        }
        for (index in 0 until members.length()) {
            val member = members.getJSONObject(index)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${member.optString("id")} · ${member.optString("role")}", style = MaterialTheme.typography.bodySmall)
                val wrapped = member.optJSONObject("wrapped")
                if (wrapped != null) {
                    Text("Envelope for this member:", style = MaterialTheme.typography.labelSmall)
                    Text("ephemeralPublic: ${wrapped.optString("ephemeralPublic")}", style = MaterialTheme.typography.labelSmall)
                    Text("nonce: ${wrapped.optString("nonce")}", style = MaterialTheme.typography.labelSmall)
                    Text("ciphertext: ${wrapped.optString("ciphertext")}", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                        TextButton(onClick = { copy("Ephemeral public", wrapped.optString("ephemeralPublic")) }) { Text("Copy ephemeral") }
                        TextButton(onClick = { copy("Nonce", wrapped.optString("nonce")) }) { Text("Copy nonce") }
                        TextButton(onClick = { copy("Ciphertext", wrapped.optString("ciphertext")) }) { Text("Copy ciphertext") }
                    }
                }
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val nextRole = if (member.optString("role") == "editor") "viewer" else "editor"
                                val rewrapped = familyWrap(
                                    controller.syncNamespace(),
                                    controller.familyVaultKeyHex(),
                                    member.optString("id"),
                                    member.optString("public"),
                                    nextRole,
                                )
                                member.put("role", nextRole)
                                member.put("wrapped", JSONObject(rewrapped))
                                state.put("audit", (state.optJSONArray("audit") ?: JSONArray()).put(JSONObject().put("event", "role_changed").put("memberId", member.optString("id")).put("role", nextRole)))
                                store.save(state)
                                message = "Role changed and wrapped key updated."
                            }.onFailure { message = "Role change failed." }
                        }
                    }) { Text(if (member.optString("role") == "editor") "Make viewer" else "Make editor") }
                    TextButton(onClick = { revoking = member.optString("id") }) { Text("Revoke") }
                }
            }
        }

        Text("Member: create your identity", style = MaterialTheme.typography.titleSmall)
        Text(
            "Generate this on the member device, send the member id and public key to the owner, and keep the secret private until you join.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(selfId, { selfId = it }, label = { Text("Your member id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedButton(onClick = { selfRole = "editor" }) { Text(if (selfRole == "editor") "✓ Editor" else "Editor") }
            OutlinedButton(onClick = { selfRole = "viewer" }) { Text(if (selfRole == "viewer") "✓ Viewer" else "Viewer") }
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    val identity = JSONObject(familyIdentity(selfId.trim().ifBlank { "member-1" }, selfRole))
                    state.put("member", identity)
                    store.save(state)
                    message = "Member identity created. Send memberId and public to the owner."
                }.onFailure { message = "Member identity generation failed." }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create member identity") }
        state.optJSONObject("member")?.let { member ->
            Text("Your member id: ${member.optString("memberId")}", style = MaterialTheme.typography.bodySmall)
            Text("Your public key: ${member.optString("public")}", style = MaterialTheme.typography.bodySmall)
            Text("Your secret (keep private): ${member.optString("secret")}", style = MaterialTheme.typography.bodySmall)
            Text("Fingerprint: ${member.optString("fingerprint")}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
                TextButton(onClick = { copy("Member id", member.optString("memberId")) }) { Text("Copy id") }
                TextButton(onClick = { copy("Public key", member.optString("public")) }) { Text("Copy public") }
                TextButton(onClick = { copy("Member secret", member.optString("secret")) }) { Text("Copy secret") }
            }
        }

        Text("Join a family vault", style = MaterialTheme.typography.titleSmall)
        Text("This replaces the local vault on this device with the owner's vault key. Use a fresh install or confirm you have a backup.", style = MaterialTheme.typography.labelSmall)
        listOf(
            "Vault id (32 hex)" to (joinVaultId to { value: String -> joinVaultId = value }),
            "Member id" to (joinMemberId to { value: String -> joinMemberId = value }),
            "Envelope ephemeral public (64 hex)" to (joinPublic to { value: String -> joinPublic = value }),
            "Envelope nonce (48 hex)" to (joinNonce to { value: String -> joinNonce = value }),
            "Envelope ciphertext" to (joinCiphertext to { value: String -> joinCiphertext = value }),
            "Member secret (64 hex)" to (joinSecret to { value: String -> joinSecret = value }),
            "New local master password" to (joinPassword to { value: String -> joinPassword = value }),
        ).forEach { (labelText, pair) ->
            OutlinedTextField(pair.first, pair.second, label = { Text(labelText) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
            OutlinedButton(onClick = { joinRole = "editor" }) { Text(if (joinRole == "editor") "✓ Editor" else "Editor") }
            OutlinedButton(onClick = { joinRole = "viewer" }) { Text(if (joinRole == "viewer") "✓ Viewer" else "Viewer") }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching {
                        val unwrapped = JSONObject(
                            familyUnwrap(joinVaultId.trim(), joinMemberId.trim(), joinPublic.trim(), joinNonce.trim(), joinCiphertext.trim(), joinRole, joinSecret.trim()),
                        )
                        controller.joinFamilyVault(joinPassword, joinVaultId.trim(), unwrapped.getString("vaultKey")).getOrThrow()
                        message = "Joined family vault. Configure sync to pull items."
                    }.onFailure { message = "Join failed; check the envelope, secret and password." }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Join family vault") }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }

    if (revoking != null) {
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke member and rotate key?") },
            text = { Text("This generates a new vault key, rekeys stored items and rewraps remaining members. The removed member keeps access to ciphertext they already had.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = revoking
                    revoking = null
                    if (target != null) {
                        scope.launch {
                            runCatching {
                                controller.rotateFamilyKey().getOrThrow()
                                val membersNow = state.optJSONArray("members") ?: JSONArray()
                                val remaining = JSONArray()
                                for (index in 0 until membersNow.length()) {
                                    val member = membersNow.getJSONObject(index)
                                    if (member.optString("id") == target) continue
                                    val rewrapped = familyWrap(
                                        controller.syncNamespace(),
                                        controller.familyVaultKeyHex(),
                                        member.optString("id"),
                                        member.optString("public"),
                                        member.optString("role"),
                                    )
                                    member.put("wrapped", JSONObject(rewrapped))
                                    remaining.put(member)
                                }
                                state.put("members", remaining)
                                state.put("audit", (state.optJSONArray("audit") ?: JSONArray()).put(JSONObject().put("event", "revoked").put("memberId", target)))
                                store.save(state)
                                message = "Member revoked and vault key rotated."
                            }.onFailure { message = "Revocation failed." }
                        }
                    }
                }) { Text("Revoke and rotate") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}
