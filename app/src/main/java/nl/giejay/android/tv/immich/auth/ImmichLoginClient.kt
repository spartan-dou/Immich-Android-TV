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
import java.util.concurrent.TimeUnit

class LoginException(message: String) : Exception(message)

/**
 * Exchanges Immich credentials for an API key, directly against the user's own server:
 * login -> create a read-only API key -> logout. The rest of the app keeps using API keys only.
 */
class ImmichLoginClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    fun createApiKey(hostName: String, email: String, password: String, keyName: String): String {
        val accessToken = login(hostName, email, password)
        try {
            return try {
                createKey(hostName, accessToken, keyName, PERMISSIONS)
            } catch (e: UnknownPermissionException) {
                // Older servers don't know every permission listed below
                Timber.w(e, "Server rejected the permission list, falling back to 'all'")
                createKey(hostName, accessToken, keyName, listOf("all"))
            }
        } finally {
            // The session is only needed to create the key
            logout(hostName, accessToken)
        }
    }

    private fun login(hostName: String, email: String, password: String): String {
        val body = JSONObject().put("email", email).put("password", password)
        execute(post("$hostName/api/auth/login", body)).use { response ->
            if (response.code == 401 || response.code == 400) {
                throw LoginException(errorMessage(response) ?: "Invalid email or password")
            }
            ensureSuccess(response)
            return JSONObject(response.body?.string().orEmpty()).optString("accessToken").ifEmpty {
                throw LoginException("No access token returned by the server")
            }
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

        // Same list as "Required API Permissions" in the README
        val PERMISSIONS = listOf(
            "activity.read", "album.download", "album.read", "archive.read", "asset.download",
            "asset.read", "asset.view", "face.read", "folder.read", "library.read", "memory.read",
            "partner.read", "person.read", "session.read", "tag.asset", "tag.read", "timeline.read"
        )
    }
}
