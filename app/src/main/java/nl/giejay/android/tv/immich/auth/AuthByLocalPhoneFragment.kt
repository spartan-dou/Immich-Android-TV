package nl.giejay.android.tv.immich.auth

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.github.alexzhirkevich.customqrgenerator.QrData
import com.github.alexzhirkevich.customqrgenerator.vector.QrCodeDrawable
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.databinding.FragmentAuthByPhoneBinding
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber

/**
 * Sign in by phone without any third-party server: the TV serves a sign-in page on the local
 * network, the phone signs in to Immich through it (SSO or password), and the TV creates its own
 * API key.
 */
class AuthByLocalPhoneFragment : Fragment() {
    private data class PendingSso(val hostName: String, val state: String, val codeVerifier: String)

    private var server: LocalLoginServer? = null
    private var binding: FragmentAuthByPhoneBinding? = null
    @Volatile
    private var pendingSso: PendingSso? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentAuthByPhoneBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        val server = LocalLoginServer(pageTexts(), PreferenceManager.hostName, ::signInWithPassword, ::startSso, ::finishSso)
        this.server = server

        val url = try {
            server.start()
        } catch (e: Exception) {
            Timber.e(e, "Could not start the local sign-in server")
            null
        }
        binding.qrProgressBar.visibility = View.GONE
        if (url == null) {
            binding.qrText.text = getString(R.string.local_sign_in_no_network)
            return
        }
        binding.qr.setImageDrawable(QrCodeDrawable(QrData.Url(url)))
        binding.qr.visibility = View.VISIBLE
        binding.qrText.text = getString(R.string.local_sign_in_open_url, url)
    }

    // The callbacks below run on the server thread: network calls are allowed, but the fragment
    // may be gone, so strings come from the application context

    private fun signInWithPassword(form: LocalLoginServer.LoginForm): LocalLoginServer.LoginResult {
        val hostName = validHostName(form.hostName)
            ?: return LocalLoginServer.LoginResult(false, string(R.string.enter_valid_server_url))
        return signIn(hostName) {
            ImmichLoginClient().createApiKey(hostName, form.email.trim(), form.password, keyName())
        }
    }

    private fun startSso(hostInput: String, redirectUri: String): LocalLoginServer.SsoStart {
        val hostName = validHostName(hostInput)
            ?: return LocalLoginServer.SsoStart(null, string(R.string.enter_valid_server_url))
        val pending = PendingSso(hostName, ImmichLoginClient.newState(), ImmichLoginClient.newCodeVerifier())
        return try {
            val url = ImmichLoginClient().startOAuth(
                hostName, redirectUri, pending.state, ImmichLoginClient.codeChallenge(pending.codeVerifier)
            )
            pendingSso = pending
            LocalLoginServer.SsoStart(url, null)
        } catch (e: LoginException) {
            LocalLoginServer.SsoStart(null, e.message ?: string(R.string.local_sign_in_failed))
        }
    }

    private fun finishSso(callbackUrl: String, params: Map<String, String>): LocalLoginServer.LoginResult {
        val pending = pendingSso
        if (pending == null || params["state"] != pending.state) {
            return LocalLoginServer.LoginResult(false, string(R.string.local_sign_in_sso_not_started))
        }
        // One attempt per SSO start: the authorization code can only be used once
        pendingSso = null
        params["error"]?.let { error ->
            return LocalLoginServer.LoginResult(false, params["error_description"] ?: error)
        }
        return signIn(pending.hostName) {
            ImmichLoginClient().createApiKeyWithOAuth(pending.hostName, callbackUrl, pending.state, pending.codeVerifier, keyName())
        }
    }

    private fun signIn(hostName: String, createApiKey: () -> SignedInAccount): LocalLoginServer.LoginResult = try {
        val account = createApiKey()
        view?.post { onSignedIn(hostName, account) }
        LocalLoginServer.LoginResult(true, string(R.string.local_sign_in_success))
    } catch (e: LoginException) {
        LocalLoginServer.LoginResult(false, e.message ?: string(R.string.local_sign_in_failed))
    }

    private fun onSignedIn(hostName: String, account: SignedInAccount) {
        if (!isAdded) return
        AccountManager.add(account.name, account.email, hostName, account.apiKey)
        // Fresh back stack: the profiles/sign-in screens shouldn't come back with "back"
        findNavController().navigate(
            AuthByLocalPhoneFragmentDirections.actionGlobalHomeFragment(),
            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
        )
    }

    private fun validHostName(input: String): String? =
        normalizeHostName(input).takeIf { PreferenceManager.isValid(it, "placeholder") }

    private fun string(id: Int): String = ImmichApplication.appContext!!.getString(id)

    private fun keyName(): String = "Immich TV (${Build.MODEL})"

    private fun pageTexts() = LocalLoginServer.PageTexts(
        title = getString(R.string.app_name),
        serverUrl = getString(R.string.server_url_hint),
        ssoButton = getString(R.string.local_sign_in_sso),
        orPassword = getString(R.string.local_sign_in_or_password),
        email = getString(R.string.local_sign_in_email),
        password = getString(R.string.local_sign_in_password),
        submit = getString(R.string.submit),
        localNetworkNotice = getString(R.string.local_sign_in_notice),
        ssoRedirectNotice = getString(R.string.local_sign_in_sso_redirect),
        tooManyAttempts = getString(R.string.local_sign_in_too_many_attempts)
    )

    override fun onDestroyView() {
        super.onDestroyView()
        server?.stop()
        server = null
        binding = null
    }

    companion object {
        // The app appends /api itself
        fun normalizeHostName(input: String): String =
            input.trim().removeSuffix("/").removeSuffix("/api").removeSuffix("/")
    }
}
