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
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS
import timber.log.Timber

/**
 * Sign in by phone without any third-party server: the TV serves a sign-in page on the local
 * network, the phone submits the Immich credentials to it, and the TV creates its own API key.
 */
class AuthByLocalPhoneFragment : Fragment() {
    private var server: LocalLoginServer? = null
    private var binding: FragmentAuthByPhoneBinding? = null

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
        val server = LocalLoginServer(pageTexts(), PreferenceManager.hostName, ::signIn)
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

    // Runs on the server thread: network calls are allowed here, but the fragment may be gone,
    // so strings come from the application context
    private fun signIn(form: LocalLoginServer.LoginForm): LocalLoginServer.LoginResult {
        val context = ImmichApplication.appContext!!
        val hostName = normalizeHostName(form.hostName)
        if (!PreferenceManager.isValid(hostName, "placeholder")) {
            return LocalLoginServer.LoginResult(false, context.getString(R.string.enter_valid_server_url))
        }
        return try {
            val apiKey = ImmichLoginClient().createApiKey(hostName, form.email.trim(), form.password, keyName())
            view?.post { onSignedIn(hostName, apiKey) }
            LocalLoginServer.LoginResult(true, context.getString(R.string.local_sign_in_success))
        } catch (e: LoginException) {
            LocalLoginServer.LoginResult(false, e.message ?: context.getString(R.string.local_sign_in_failed))
        }
    }

    private fun onSignedIn(hostName: String, apiKey: String) {
        if (!isAdded) return
        PreferenceManager.save(SCREENSAVER_ALBUMS, emptySet())
        PreferenceManager.save(API_KEY, apiKey)
        PreferenceManager.save(HOST_NAME, hostName)
        findNavController().navigate(
            AuthByLocalPhoneFragmentDirections.actionGlobalHomeFragment(),
            NavOptions.Builder().setPopUpTo(R.id.authFragment, true).build()
        )
    }

    private fun keyName(): String = "Immich TV (${Build.MODEL})"

    private fun pageTexts() = LocalLoginServer.PageTexts(
        title = getString(R.string.app_name),
        serverUrl = getString(R.string.server_url_hint),
        email = getString(R.string.local_sign_in_email),
        password = getString(R.string.local_sign_in_password),
        submit = getString(R.string.submit),
        localNetworkNotice = getString(R.string.local_sign_in_notice),
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
