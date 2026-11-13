package dev.cipherkeep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    var memberId by remember { mutableStateOf("") }
    var memberPublic by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("editor") }
    var message by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<String?>(null) }
    var joinVaultId by remember { mutableStateOf("") }
    var joinMemberId by remember { mutableStateOf("") }
    var joinPublic by remember { mutableStateOf("") }
    var joinNonce by remember { mutableStateOf("") }
    var joinCiphertext by remember { mutableStateOf("") }
    var joinSecret by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var joinRole by remember { mutableStateOf("editor") }

    fun refresh() {
        state = store.load() ?: JSONObject()
    }
    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(CKDimens.Space2)) {
        Text("Family sharing", style = MaterialTheme.typography.titleMedium)
        Text(
            "Owner-approved members receive a wrapped vault key. Revoking rotates the vault key and rekeys future writes. Ciphertext a removed member already had remains readable; verify fingerprints out of band.",
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
        }
        OutlinedTextField(memberId, { memberId = it }, label = { Text("Member id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(memberPublic, { memberPublic = it }, label = { Text("Member X25519 public key (64 hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                        message = "Member invited. Verify their fingerprint out of band."
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
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("${member.optString("id")} · ${member.optString("role")}", style = MaterialTheme.typography.bodySmall)
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
        Text("Join a family vault", style = MaterialTheme.typography.titleSmall)
        Text("This replaces the local vault on this device with the owner's vault key. Use a fresh install or confirm you have a backup.", style = MaterialTheme.typography.labelSmall)
        listOf(
            "Vault id (32 hex)" to (joinVaultId to { value: String -> joinVaultId = value }),
            "Member id" to (joinMemberId to { value: String -> joinMemberId = value }),
            "Member public key (64 hex)" to (joinPublic to { value: String -> joinPublic = value }),
            "Envelope nonce (48 hex)" to (joinNonce to { value: String -> joinNonce = value }),
            "Envelope ciphertext" to (joinCiphertext to { value: String -> joinCiphertext = value }),
            "Member secret (64 hex)" to (joinSecret to { value: String -> joinSecret = value }),
            "New local master password" to (joinPassword to { value: String -> joinPassword = value }),
        ).forEach { (labelText, pair) ->
            OutlinedTextField(pair.first, pair.second, label = { Text(labelText) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
