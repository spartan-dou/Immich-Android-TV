package nl.giejay.android.tv.immich.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class LoginException(message: String) : Exception(message)

/** API key created for the TV, with the account it belongs to (from Immich's login response). */
data class SignedInAccount(val apiKey: String, val name: String, val email: String)

/**
 * Exchanges Immich credentials (password or SSO) for an API key, directly against the user's own
 * server: login -> create an API key -> logout. The rest of the app keeps using API keys only.
 */
class ImmichLoginClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    fun createApiKey(hostName: String, email: String, password: String, keyName: String): SignedInAccount {
        val body = JSONObject().put("email", email).put("password", password)
        val login = execute(post("$hostName/api/auth/login", body)).use { response ->
            if (response.code == 401 || response.code == 400) {
                throw LoginException(errorMessage(response) ?: "Invalid email or password")
            }
            ensureSuccess(response)
            JSONObject(response.body?.string().orEmpty())
        }
        return createApiKeyWithSession(hostName, login, keyName)
    }

    /** Starts an SSO sign-in: returns the identity provider URL the phone must open. */
    fun startOAuth(hostName: String, redirectUri: String, state: String, codeChallenge: String): String {
        val body = JSONObject()
            .put("redirectUri", redirectUri)
            .put("state", state)
            .put("codeChallenge", codeChallenge)
        execute(post("$hostName/api/oauth/authorize", body)).use { response ->
            ensureSuccess(response)
            return JSONObject(response.body?.string().orEmpty()).optString("url").ifEmpty {
                throw LoginException("No SSO URL returned by the server")
            }
        }
    }

    /** Finishes an SSO sign-in from the URL the identity provider redirected the phone to. */
    fun createApiKeyWithOAuth(hostName: String, callbackUrl: String, state: String, codeVerifier: String, keyName: String): SignedInAccount {
        val body = JSONObject()
            .put("url", callbackUrl)
            .put("state", state)
            .put("codeVerifier", codeVerifier)
        val login = execute(post("$hostName/api/oauth/callback", body)).use { response ->
            ensureSuccess(response)
            JSONObject(response.body?.string().orEmpty())
        }
        return createApiKeyWithSession(hostName, login, keyName)
    }

    // login: Immich's LoginResponseDto (password and SSO sign-ins return the same thing)
    private fun createApiKeyWithSession(hostName: String, login: JSONObject, keyName: String): SignedInAccount {
        val accessToken = login.optString("accessToken").ifEmpty {
            throw LoginException("No access token returned by the server")
        }
        try {
            val apiKey = try {
                createKey(hostName, accessToken, keyName, PERMISSIONS)
            } catch (e: UnknownPermissionException) {
                // Older servers don't know every permission listed below
                Timber.w(e, "Server rejected the permission list, falling back to 'all'")
                createKey(hostName, accessToken, keyName, listOf("all"))
            }
            val email = login.optString("userEmail")
            return SignedInAccount(apiKey, login.optString("name").ifEmpty { email }, email)
        } finally {
            // The session is only needed to create the key
            logout(hostName, accessToken)
        }
    }

    private fun createKey(hostName: String, accessToken: String, keyName: String, permissions: List<String>): String {
        val body = JSONObject().put("name", keyName).put("permissions", JSONArray(permissions))
        execute(post("$hostName/api/api-keys", body, accessToken)).use { response ->
            if (response.code == 400 && permissions != listOf("all")) {
                throw UnknownPermissionException(errorMessage(response))
            }
            ensureSuccess(response)
            return JSONObject(response.body?.string().orEmpty()).optString("secret").ifEmpty {
                throw LoginException("No API key returned by the server")
            }
        }
    }

    private fun logout(hostName: String, accessToken: String) {
        try {
            execute(post("$hostName/api/auth/logout", JSONObject(), accessToken)).close()
        } catch (e: Exception) {
            Timber.w(e, "Could not log out the temporary session")
        }
    }

    private fun post(url: String, body: JSONObject, accessToken: String? = null): Request =
        Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .build()

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw LoginException("Could not reach the Immich server: ${e.message}")
    }

    private fun ensureSuccess(response: Response) {
        if (!response.isSuccessful) {
            throw LoginException(errorMessage(response) ?: "Unexpected server response (HTTP ${response.code})")
        }
    }

    private fun errorMessage(response: Response): String? = try {
        JSONObject(response.body?.string().orEmpty()).optString("message").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private class UnknownPermissionException(message: String?) : Exception(message)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // PKCE (RFC 7636): random verifier, S256 challenge sent to the identity provider
        fun newCodeVerifier(): String = randomUrlSafe(32)

        fun codeChallenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun newState(): String = randomUrlSafe(16)

        private fun randomUrlSafe(bytes: Int): String =
            base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        private const val BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        // Unpadded base64url, written out because java.util.Base64 needs API 26 (not desugared)
        // and android.util.Base64 isn't available in plain JVM unit tests
        internal fun base64Url(bytes: ByteArray): String = buildString {
            for (i in bytes.indices step 3) {
                val chunk = (bytes[i].toInt() and 0xff shl 16) or
                    ((bytes.getOrNull(i + 1)?.toInt() ?: 0) and 0xff shl 8) or
                    ((bytes.getOrNull(i + 2)?.toInt() ?: 0) and 0xff)
                val chars = when (bytes.size - i) { 1 -> 2; 2 -> 3; else -> 4 }
                for (j in 0 until chars) {
                    append(BASE64_URL_ALPHABET[(chunk shr (18 - 6 * j)) and 0x3f])
                }
            }
        }

        // Same list as "Required API Permissions" in the README
        val PERMISSIONS = listOf(
            "activity.read", "album.download", "album.read", "archive.read", "asset.download",
            "asset.read", "asset.view", "face.read", "folder.read", "library.read", "memory.read",
            "partner.read", "person.read", "session.read", "tag.asset", "tag.read", "timeline.read"
        )
    }
}
