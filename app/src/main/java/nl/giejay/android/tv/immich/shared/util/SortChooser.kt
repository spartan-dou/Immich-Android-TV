package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.EnumWithTitle
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/**
 * Sort order picker opened straight from a grid with the Menu key; grids already re-sort when the
 * pref changes. "More settings" leads to the full settings screen that Menu used to open.
 */
object SortChooser {
    fun <T> show(context: Context, pref: EnumByTitlePref<T>, onMoreSettings: () -> Unit)
            where T : Enum<T>, T : EnumWithTitle {
        val entries = pref.getEnumEntries()
        val current = PreferenceManager.get(pref)
        AlertDialog.Builder(context, R.style.ImmichDialogTheme)
            .setTitle(R.string.order)
            .setSingleChoiceItems(entries.map { it.getTitle() }.toTypedArray(), entries.indexOf(current)) { dialog, which ->
                PreferenceManager.save(pref, entries[which])
                dialog.dismiss()
            }
            .setNeutralButton(R.string.more_settings) { _, _ -> onMoreSettings() }
            .show()
    }
}
