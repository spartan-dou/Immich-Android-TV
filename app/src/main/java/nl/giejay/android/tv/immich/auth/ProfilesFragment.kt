package nl.giejay.android.tv.immich.auth

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** "Who's watching?": picks the active Immich account, adds or removes accounts. */
class ProfilesFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.profiles_title),
            getString(R.string.profiles_description),
            "",
            requireContext().getDrawable(R.drawable.icon)
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val activeId = AccountManager.activeId()
        AccountManager.accounts().forEachIndexed { index, account ->
            actions.add(GuidedAction.Builder(context)
                .id(index.toLong())
                .title(account.name)
                .description(listOfNotNull(account.email, AccountManager.hostLabel(account.hostName))
                    .distinct().joinToString(" · ")
                    + if (account.id == activeId) " · " + getString(R.string.profiles_active) else "")
                .build())
        }
    }

    override fun onCreateButtonActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(GuidedAction.Builder(context)
            .id(ACTION_ADD)
            .title(R.string.profiles_add)
            .build())
        val accounts = AccountManager.accounts()
        if (accounts.isNotEmpty()) {
            actions.add(GuidedAction.Builder(context)
                .id(ACTION_REMOVE)
                .title(R.string.profiles_remove)
                .subActions(accounts.map { account ->
                    GuidedAction.Builder(context)
                        .id(REMOVE_OFFSET + accounts.indexOf(account))
                        .title(account.name)
                        .description(AccountManager.hostLabel(account.hostName))
                        .build()
                })
                .build())
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ADD -> findNavController().navigate(ProfilesFragmentDirections.actionProfilesToAuth())
            else -> AccountManager.accounts().getOrNull(action.id.toInt())?.let {
                AccountManager.activate(it.id)
                openHome()
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val accounts = AccountManager.accounts()
        accounts.getOrNull((action.id - REMOVE_OFFSET).toInt())?.let { AccountManager.remove(it.id) }
        if (!PreferenceManager.isLoggedId()) {
            findNavController().navigate(
                ProfilesFragmentDirections.actionProfilesToAuth(),
                NavOptions.Builder().setPopUpTo(R.id.profilesFragment, true).build()
            )
        } else {
            // Rebuild the list without the removed account
            actions = mutableListOf<GuidedAction>().also { onCreateActions(it, null) }
            buttonActions = mutableListOf<GuidedAction>().also { onCreateButtonActions(it, null) }
        }
        return true
    }

    // Fresh back stack: nothing from the previous account must stay reachable with "back"
    private fun openHome() {
        findNavController().navigate(
            ProfilesFragmentDirections.actionGlobalHomeFragment(),
            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build()
        )
    }

    companion object {
        private const val ACTION_ADD = -1L
        private const val ACTION_REMOVE = -2L
        private const val REMOVE_OFFSET = 1_000_000L
    }
}
