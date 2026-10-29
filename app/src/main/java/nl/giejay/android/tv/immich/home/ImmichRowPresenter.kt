package nl.giejay.android.tv.immich.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

class ImmichRowPresenter : Presenter() {
    var editMode: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup): ImmichRowViewHolder {
        val root: View = LayoutInflater.from(parent.context)
            .inflate(R.layout.presenter_row, parent, false)

        val viewHolder = ImmichRowViewHolder(root)
        // The label holds the focus: light up the whole pill (and the icon) while it does
        viewHolder.tvTitle.setOnFocusChangeListener { _, hasFocus ->
            root.isSelected = hasFocus
            viewHolder.headerIcon.isSelected = hasFocus
        }
        return viewHolder
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val headerItem = if (item == null) null else (item as Row).headerItem
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = headerItem?.name
        // Header ids are their index in HomeFragment's HEADERS
        val iconRes = headerItem?.id?.toInt()?.let { HEADER_ICONS.getOrNull(it) }
        vh.headerIcon.visibility = if (iconRes != null) View.VISIBLE else View.GONE
        iconRes?.let { vh.headerIcon.setImageResource(it) }

        if (editMode) {
            if (headerItem?.name == viewHolder.view.context.getString(R.string.edit)) {
                vh.tvTitle.text = viewHolder.view.context.getString(R.string.done)
            } else {
                vh.icon.visibility = View.VISIBLE
                if (PreferenceManager.itemInStringSet(headerItem?.name, HIDDEN_HOME_ITEMS)) {
                    vh.icon.setImageResource(R.drawable.closed_eye)
                } else {
                    vh.icon.setImageResource(R.drawable.visible_eye)
                }
            }
        } else {
            vh.icon.visibility = View.GONE
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = null
    }

    companion object {
        // Same order as HomeFragment's HEADERS
        private val HEADER_ICONS = listOf(
            R.drawable.ic_nav_timeline,
            R.drawable.ic_nav_albums,
            R.drawable.ic_nav_photos,
            R.drawable.ic_nav_random,
            R.drawable.ic_nav_people,
            R.drawable.ic_nav_recent,
            R.drawable.ic_nav_seasonal,
            R.drawable.ic_nav_folders,
            R.drawable.ic_nav_edit,
            R.drawable.ic_nav_settings
        )
    }
}
