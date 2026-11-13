package dev.cipherkeep.app.passkeys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

internal data class PasskeyRecord(
    val credentialId: String,
    val rpId: String,
    val origin: String,
    val username: String,
    val displayName: String,
    val userHandle: String,
    val alias: String,
    var counter: Long,
)

/** Plain metadata only; the private key stays in AndroidKeyStore. */
internal class PasskeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("passkeys", Context.MODE_PRIVATE)

    fun list(): List<PasskeyRecord> = runCatching {
        val array = JSONArray(prefs.getString("records", "[]") ?: "[]")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PasskeyRecord(
                credentialId = item.getString("credentialId"),
                rpId = item.getString("rpId"),
                origin = item.getString("origin"),
                username = item.getString("username"),
                displayName = item.getString("displayName"),
                userHandle = item.getString("userHandle"),
                alias = item.getString("alias"),
                counter = item.optLong("counter"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(record: PasskeyRecord) {
        val records = list().filterNot { it.credentialId == record.credentialId } + record
        val array = JSONArray()
        records.forEach { item ->
            array.put(
                JSONObject()
                    .put("credentialId", item.credentialId)
                    .put("rpId", item.rpId)
                    .put("origin", item.origin)
                    .put("username", item.username)
                    .put("displayName", item.displayName)
                    .put("userHandle", item.userHandle)
                    .put("alias", item.alias)
                    .put("counter", item.counter),
            )
        }
        prefs.edit().putString("records", array.toString()).apply()
    }

    fun find(credentialId: String): PasskeyRecord? = list().firstOrNull { it.credentialId == credentialId }
}

internal object PasskeyCrypto {
    private const val KEYSTORE = "AndroidKeyStore"

    fun createKey(alias: String): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    fun key(alias: String): java.security.KeyStore.PrivateKeyEntry? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
    }

    fun sign(alias: String, data: ByteArray): ByteArray {
        val entry = key(alias) ?: error("passkey key missing")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data)
        return signature.sign()
    }
}

internal object Cbor {
    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xF6)
            is Int -> integer(out, value.toLong())
            is Long -> integer(out, value)
            is ByteArray -> {
                header(out, 2, value.size)
                out.write(value)
            }
            is String -> {
                val bytes = value.toByteArray()
                header(out, 3, bytes.size)
                out.write(bytes)
            }
            is List<*> -> {
                header(out, 4, value.size)
                value.forEach { write(out, it) }
            }
            is Map<*, *> -> {
                header(out, 5, value.size)
                value.forEach { (key, item) ->
                    write(out, key)
                    write(out, item)
                }
            }
            else -> error("unsupported CBOR value")
        }
    }

    private fun integer(out: ByteArrayOutputStream, value: Long) {
        if (value >= 0) header(out, 0, value) else header(out, 1, -1 - value)
    }

    private fun header(out: ByteArrayOutputStream, major: Int, value: Int) {
        header(out, major, value.toLong())
    }

    private fun header(out: ByteArrayOutputStream, major: Int, value: Long) {
        val prefix = major shl 5
        when {
            value < 24 -> out.write(prefix or value.toInt())
            value <= 0xFF -> {
                out.write(prefix or 24)
                out.write(value.toInt())
            }
            value <= 0xFFFF -> {
                out.write(prefix or 25)
                out.write((value shr 8).toInt())
                out.write(value.toInt())
            }
            else -> {
                out.write(prefix or 26)
                out.write((value shr 24).toInt())
                out.write((value shr 16).toInt())
                out.write((value shr 8).toInt())
                out.write(value.toInt())
            }
        }
    }
}

internal fun b64url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

internal fun unb64url(value: String): ByteArray =
    Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

internal fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

internal fun originHost(origin: String): String? =
    runCatching { java.net.URI(origin).host }.getOrNull()

internal fun originMatchesRp(origin: String, rpId: String): Boolean {
    val host = originHost(origin) ?: return false
    return host == rpId || host.endsWith(".$rpId")
}

internal fun createRegistration(
    context: Context,
    requestJson: String,
    origin: String,
): String {
    val request = JSONObject(requestJson)
    val rp = request.getJSONObject("rp")
    val rpId = rp.optString("id").ifEmpty { originHost(origin) ?: error("no RP ID") }
    require(originMatchesRp(origin, rpId)) { "origin/RP mismatch" }
    val challenge = request.getString("challenge")
    val user = request.getJSONObject("user")
    val credentialId = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val credentialIdText = b64url(credentialId)
    val alias = "ck-passkey-${credentialIdText.take(24)}"
    val pair = PasskeyCrypto.createKey(alias)
    val publicKey = pair.public as java.security.interfaces.ECPublicKey
    val x = publicKey.w.affineX.toByteArray().takeLast(32).toByteArray()
    val y = publicKey.w.affineY.toByteArray().takeLast(32).toByteArray()
    val cose = Cbor.encode(
        mapOf(
            1 to 2,
            3 to -7,
            -1 to 1,
            -2 to x,
            -3 to y,
        ),
    )
    val rpIdHash = sha256(rpId.toByteArray())
    val authData = ByteArrayOutputStream().apply {
        write(rpIdHash)
        write(0x45)
        write(byteArrayOf(0, 0, 0, 0))
        write(ByteArray(16))
        write(byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()))
        write(credentialId)
        write(cose)
    }.toByteArray()
    val attestation = Cbor.encode(
        mapOf(
            "fmt" to "none",
            "attStmt" to emptyMap<String, Any>(),
            "authData" to authData,
        ),
    )
    val clientData = JSONObject()
        .put("type", "webauthn.create")
        .put("challenge", challenge)
        .put("origin", origin)
        .put("crossOrigin", false)
        .toString()
        .toByteArray()
    PasskeyStore(context).save(
        PasskeyRecord(
            credentialId = credentialIdText,
            rpId = rpId,
            origin = origin,
            username = user.optString("name"),
            displayName = user.optString("displayName"),
            userHandle = user.optString("id"),
            alias = alias,
            counter = 0,
        ),
    )
    return JSONObject()
        .put("id", credentialIdText)
        .put("rawId", credentialIdText)
        .put(
            "response",
            JSONObject()
                .put("clientDataJSON", b64url(clientData))
                .put("attestationObject", b64url(attestation)),
        )
        .put("type", "public-key")
        .put("clientExtensionResults", JSONObject())
        .put("authenticatorAttachment", "platform")
        .toString()
}

internal fun createAssertion(
    context: Context,
    record: PasskeyRecord,
    requestJson: String,
    origin: String,
): String {
    val request = JSONObject(requestJson)
    val rpId = request.optString("rpId").ifEmpty { record.rpId }
    require(originMatchesRp(origin, rpId) && record.rpId == rpId) { "origin/RP mismatch" }
    val challenge = request.getString("challenge")
    val clientData = JSONObject()
        .put("type", "webauthn.get")
        .put("challenge", challenge)
        .put("origin", origin)
        .put("crossOrigin", false)
        .toString()
        .toByteArray()
    record.counter += 1
    val authData = ByteArrayOutputStream().apply {
        write(sha256(rpId.toByteArray()))
        write(0x05)
        write(
            byteArrayOf(
                (record.counter shr 24).toByte(),
                (record.counter shr 16).toByte(),
                (record.counter shr 8).toByte(),
                record.counter.toByte(),
            ),
        )
    }.toByteArray()
    val signed = authData + sha256(clientData)
    val signature = PasskeyCrypto.sign(record.alias, signed)
    PasskeyStore(context).save(record)
    return JSONObject()
        .put("id", record.credentialId)
        .put("rawId", record.credentialId)
        .put(
            "response",
            JSONObject()
                .put("clientDataJSON", b64url(clientData))
                .put("authenticatorData", b64url(authData))
                .put("signature", b64url(signature))
                .put("userHandle", record.userHandle),
        )
        .put("type", "public-key")
        .put("clientExtensionResults", JSONObject())
        .put("authenticatorAttachment", "platform")
        .toString()
}
