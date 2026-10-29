package nl.giejay.android.tv.immich.auth

import nl.giejay.android.tv.immich.security.ApiKeyCipher
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.CHANNEL_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Account(
    val id: String,
    val name: String,
    val email: String?,
    val hostName: String,
    val apiKey: String,
    // Album ids only make sense for the account they come from
    val screensaverAlbums: Set<String> = emptySet(),
    val channelAlbums: Set<String> = emptySet(),
    val excludedAlbums: Set<String> = emptySet()
)

/**
 * Several Immich accounts (possibly on different servers) on one TV. The active account is
 * mirrored into the single-account prefs the rest of the app reads (HOST_NAME, API_KEY and the
 * album selections), so switching only has to swap those values.
 */
object AccountManager {
    private const val ACCOUNTS_KEY = "accounts"
    private const val ACTIVE_KEY = "activeAccountId"

    private val prefs get() = PreferenceManager.sharedPreference

    @Synchronized
    fun accounts(): List<Account> {
        val stored = read()
        if (stored.isNotEmpty() || !PreferenceManager.isLoggedId()) return stored
        // Signed in before multi-account existed: adopt that sign-in as the first account
        val hostName = PreferenceManager.get(HOST_NAME)
        val adopted = currentPrefsInto(
            Account(UUID.randomUUID().toString(), hostLabel(hostName), null, hostName, PreferenceManager.get(API_KEY))
        )
        write(listOf(adopted))
        prefs.edit().putString(ACTIVE_KEY, adopted.id).apply()
        return listOf(adopted)
    }

    fun activeId(): String? = prefs.getString(ACTIVE_KEY, null)

    /** Adds (or refreshes, for the same user on the same server) an account and activates it. */
    @Synchronized
    fun add(name: String, email: String?, hostName: String, apiKey: String): Account {
        val accounts = snapshotActive(accounts())
        val existing = accounts.firstOrNull { it.hostName == hostName && !email.isNullOrBlank() && it.email == email }
        val account = existing?.copy(name = name, apiKey = apiKey)
            ?: Account(UUID.randomUUID().toString(), name.ifBlank { hostLabel(hostName) }, email, hostName, apiKey)
        write(accounts.filterNot { it.id == account.id } + account)
        load(account)
        return account
    }

    @Synchronized
    fun activate(id: String) {
        val accounts = snapshotActive(accounts())
        val target = accounts.firstOrNull { it.id == id } ?: return
        write(accounts)
        if (target.id != activeId() || !PreferenceManager.isLoggedId()) load(target)
    }

    /** Forgets the account on this TV (its API key stays valid in Immich until revoked there). */
    @Synchronized
    fun remove(id: String) {
        val remaining = accounts().filterNot { it.id == id }
        write(remaining)
        if (activeId() == id) {
            remaining.firstOrNull()?.let { load(it) } ?: clearActive()
        }
    }

    // Keeps the album selections made while an account was active
    private fun snapshotActive(accounts: List<Account>): List<Account> {
        val activeId = activeId() ?: return accounts
        return accounts.map { if (it.id == activeId) currentPrefsInto(it) else it }
    }

    private fun currentPrefsInto(account: Account) = account.copy(
        screensaverAlbums = PreferenceManager.get(SCREENSAVER_ALBUMS),
        channelAlbums = PreferenceManager.get(CHANNEL_ALBUMS),
        excludedAlbums = PreferenceManager.get(EXCLUDE_ASSETS_IN_ALBUM)
    )

    private fun load(account: Account) {
        PreferenceManager.save(SCREENSAVER_ALBUMS, account.screensaverAlbums)
        PreferenceManager.save(CHANNEL_ALBUMS, account.channelAlbums)
        PreferenceManager.save(EXCLUDE_ASSETS_IN_ALBUM, account.excludedAlbums)
        PreferenceManager.save(HOST_NAME, account.hostName)
        PreferenceManager.save(API_KEY, account.apiKey)
        prefs.edit().putString(ACTIVE_KEY, account.id).apply()
    }

    private fun clearActive() {
        PreferenceManager.save(SCREENSAVER_ALBUMS, emptySet())
        PreferenceManager.save(CHANNEL_ALBUMS, emptySet())
        PreferenceManager.save(EXCLUDE_ASSETS_IN_ALBUM, emptySet())
        PreferenceManager.removeApiSettings()
        prefs.edit().remove(ACTIVE_KEY).apply()
    }

    fun hostLabel(hostName: String): String = hostName.toHttpUrlOrNull()?.host ?: hostName

    private fun read(): List<Account> {
        val array = JSONArray(prefs.getString(ACCOUNTS_KEY, null) ?: return emptyList())
        return (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { json ->
            // A key that can't be decrypted (keystore reset) leaves an unusable account: drop it
            val apiKey = ApiKeyCipher.decryptOrAdoptLegacy(json.optString("apiKey")).ifEmpty { return@mapNotNull null }
            Account(
                id = json.getString("id"),
                name = json.optString("name"),
                email = json.optString("email").ifEmpty { null },
                hostName = json.getString("hostName"),
                apiKey = apiKey,
                screensaverAlbums = json.stringSet("screensaverAlbums"),
                channelAlbums = json.stringSet("channelAlbums"),
                excludedAlbums = json.stringSet("excludedAlbums")
            )
        }
    }

    private fun write(accounts: List<Account>) {
        val array = JSONArray(accounts.map { account ->
            JSONObject()
                .put("id", account.id)
                .put("name", account.name)
                .put("email", account.email.orEmpty())
                .put("hostName", account.hostName)
                // Encrypted at rest like the API_KEY pref
                .put("apiKey", ApiKeyCipher.encrypt(account.apiKey))
                .put("screensaverAlbums", JSONArray(account.screensaverAlbums.toList()))
                .put("channelAlbums", JSONArray(account.channelAlbums.toList()))
                .put("excludedAlbums", JSONArray(account.excludedAlbums.toList()))
        })
        prefs.edit().putString(ACCOUNTS_KEY, array.toString()).apply()
    }

    private fun JSONObject.stringSet(key: String): Set<String> {
        val array = optJSONArray(key) ?: return emptySet()
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
}
