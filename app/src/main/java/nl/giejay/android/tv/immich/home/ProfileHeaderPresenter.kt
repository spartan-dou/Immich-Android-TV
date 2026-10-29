package nl.giejay.android.tv.immich.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.RowHeaderPresenter
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.auth.AccountManager

/** Non-focusable header at the top of the left menu showing the active account. */
class ProfileHeaderPresenter : RowHeaderPresenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.presenter_profile_header, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: androidx.leanback.widget.Presenter.ViewHolder, item: Any?) {
        val view = viewHolder.view
        val account = AccountManager.accounts().firstOrNull { it.id == AccountManager.activeId() }
        val name = account?.name.orEmpty().ifBlank { view.context.getString(R.string.app_name) }
        view.findViewById<TextView>(R.id.profile_initial).text = name.take(1).uppercase()
        view.findViewById<TextView>(R.id.profile_name).text = name
        view.findViewById<TextView>(R.id.profile_host).text = account?.let { AccountManager.hostLabel(it.hostName) }.orEmpty()
    }

    override fun onUnbindViewHolder(viewHolder: androidx.leanback.widget.Presenter.ViewHolder) {}

    // Keep it fully visible: the default header presenter dims rows that aren't selected
    override fun onSelectLevelChanged(holder: ViewHolder) {}
}
