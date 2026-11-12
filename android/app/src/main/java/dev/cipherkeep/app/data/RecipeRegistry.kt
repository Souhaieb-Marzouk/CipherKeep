package dev.cipherkeep.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Verified offline recipe registry. Selectors are plain strings; none are executable. */
class RecipeRegistry private constructor(
    val version: Int,
    val hash: String,
    val origins: List<String>,
) {
    companion object {
        private const val X = "vW4bWI0zqIKXUqfuPknmHR9KQkfNYMjOdL61UIHjERY"
        private const val Y = "mzVUW-M9eJ3qJZ_wgW9e_Vr9dOSNokWkz4mXgmtfreg"
        private const val PREF = "recipe_registry"
        private const val VERSION = "version"

        fun load(context: Context): RecipeRegistry? = runCatching {
            val json = context.assets.open("recipes/registry.json").bufferedReader().use { it.readText() }
            val signature = context.assets.open("recipes/registry.sig").bufferedReader().use { it.readText().trim() }
            val parsed = JSONObject(json)
            val version = parsed.getInt("version")
            require(version > 0)
            val recipes = parsed.getJSONArray("recipes")
            require(recipes.length() <= 1_000)
            val origins = mutableListOf<String>()
            for (index in 0 until recipes.length()) {
                val recipe = recipes.getJSONObject(index)
                val origin = recipe.getString("origin")
                require(validOrigin(origin))
                require(validSelector(recipe.getString("usernameSelector")))
                require(validSelector(recipe.getString("passwordSelector")))
                recipe.optString("submitSelector").takeIf { it.isNotEmpty() }?.let { require(validSelector(it)) }
                origins.add(origin.lowercase())
            }
            require(verify(canonical(parsed), signature))
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            require(version >= prefs.getInt(VERSION, 0))
            prefs.edit().putInt(VERSION, version).apply()
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(json.toByteArray())
                .joinToString("") { "%02x".format(it) }
            RecipeRegistry(version, hash, origins)
        }.getOrNull()

        private fun validOrigin(origin: String): Boolean =
            origin.isNotEmpty() && origin.length <= 255 &&
                origin.none { it.isWhitespace() || it in "/:@?#" }

        private fun validSelector(selector: String): Boolean =
            selector.isNotEmpty() && selector.length <= 512 &&
                selector.none { it.code < 0x20 } &&
                !selector.lowercase().contains("javascript:") &&
                !selector.contains("innerHTML")

        private fun verify(canonical: String, signatureBase64: String): Boolean {
            val x = BigInteger(1, Base64.decode(X, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val y = BigInteger(1, Base64.decode(Y, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
            val raw = Base64.decode(signatureBase64, Base64.DEFAULT)
            require(raw.size == 64)
            val der = rawToDer(raw)
            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(canonical.toByteArray())
                verify(der)
            }
        }

        private fun rawToDer(raw: ByteArray): ByteArray {
            fun integer(bytes: ByteArray): ByteArray {
                var start = 0
                while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
                var value = bytes.copyOfRange(start, bytes.size)
                if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
                return byteArrayOf(0x02, value.size.toByte()) + value
            }
            val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }

        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                JSONObject.quote(key) + ":" + canonical(value.get(key))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }
}
