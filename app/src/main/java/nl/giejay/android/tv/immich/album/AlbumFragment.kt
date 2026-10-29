package nl.giejay.android.tv.immich.album

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import arrow.core.Either
import kotlinx.coroutines.launch
import java.util.Calendar
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.SHOW_DATES
import nl.giejay.android.tv.immich.shared.util.SortChooser
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.homescreenchannels.HomeScreenChannelManager
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment
import nl.giejay.android.tv.immich.shared.prefs.ALBUMS_SORTING
import nl.giejay.android.tv.immich.shared.prefs.CHANNEL_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.StringSetPref
import nl.giejay.android.tv.immich.shared.prefs.ViewPrefScreen
import timber.log.Timber

enum class SelectionType {
    SET_SCREENSAVER,
    EXCLUDED_ALBUMS,
    SET_CHANNEL_ALBUMS,
}

class AlbumFragment : VerticalCardGridFragment<Album>() {
    private lateinit var selectionTypeKey: StringSetPref
    private val selectionType: SelectionType
        get() = arguments?.getString("selectionType")?.let { SelectionType.valueOf(it) } ?: SelectionType.SET_SCREENSAVER

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectionTypeKey = when (selectionType) {
            SelectionType.SET_SCREENSAVER -> {
                SCREENSAVER_ALBUMS
            }

            SelectionType.EXCLUDED_ALBUMS -> {
                EXCLUDE_ASSETS_IN_ALBUM
            }

            SelectionType.SET_CHANNEL_ALBUMS -> {
                CHANNEL_ALBUMS
            }
        }
        PreferenceManager.subscribe(ALBUMS_SORTING) {
            resortItems()
        }
    }

    override fun sortItems(items: List<Album>): List<Album> {
        if(selectionMode) {
            val sorted = items.sortedWith(PreferenceManager.get(ALBUMS_SORTING).sort)
            val selected = sorted.filter { PreferenceManager.get(selectionTypeKey).contains(it.id) }
            val unselected = sorted.filter { !selected.contains(it) }
            return selected + unselected
        } else {
            try {
                return items.sortedWith(PreferenceManager.get(ALBUMS_SORTING).sort)
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Could not sort using sorting order: " + PreferenceManager.get(ALBUMS_SORTING).toString())
                return items
            }
        }
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Album>> {
        if (page == startPage) {
            // no pagination possible yet!
            return apiClient.listAlbums()
        }
        return Either.Right(emptyList())
    }

    override fun onItemSelected(card: Card, indexOf: Int) {
        val currentAlbums = PreferenceManager.get(selectionTypeKey)
        if (card.selected) {
            PreferenceManager.save(selectionTypeKey, currentAlbums + card.id)
            if (selectionType == SelectionType.SET_CHANNEL_ALBUMS) {
                // creating the channel + requesting approval needs the Activity and must happen
                // while the app is foregrounded, so it can't wait for the debounced background sync
                lifecycleScope.launch {
                    HomeScreenChannelManager.createAlbumChannelAndRequestApproval(requireActivity(), card.id, card.title)
                }
            }
        } else {
            PreferenceManager.save(selectionTypeKey, currentAlbums - card.id)
            if (selectionType == SelectionType.SET_CHANNEL_ALBUMS) {
                lifecycleScope.launch {
                    HomeScreenChannelManager.deleteAlbumChannel(requireContext().applicationContext, card.id)
                }
            }
        }
    }

    override fun onItemClicked(card: Card) {
        findNavController().navigate(
            HomeFragmentDirections.actionHomeFragmentToAlbumDetailsFragment(
                card.id,
                card.title
            )
        )
    }

    // The dimmed background doesn't need the original file: the preview is enough and far lighter
    override fun getBackgroundPicture(it: Album): String? {
        return ApiUtil.getThumbnailUrl(it.albumThumbnailAssetId, "preview")
    }

    override fun openPopUpMenu() {
        SortChooser.show(requireContext(), ALBUMS_SORTING) {
            findNavController().navigate(
                HomeFragmentDirections.actionGlobalToSettingsDialog("view")
            )
        }
    }

    override fun createCard(a: Album): Card {
        return Card(
            a.albumName,
            albumSummary(a),
            a.id,
            // The 250 px "thumbnail" looks blurry once stretched on the card: Glide downsamples
            // the preview to the card size, so it doesn't cost more memory
            ApiUtil.getThumbnailUrl(a.albumThumbnailAssetId, "preview"),
            getBackgroundPicture(a),
            if (selectionMode) PreferenceManager.get(selectionTypeKey).contains(a.id) else false
        )
    }

    // "149 photos · 2024 – 2025" instead of the raw description
    private fun albumSummary(album: Album): String {
        val count = resources.getQuantityString(R.plurals.album_item_count, album.assetCount, album.assetCount)
        if (!PreferenceManager.get(SHOW_DATES)) return count
        val years = listOfNotNull(album.startDate, album.endDate)
            .map { Calendar.getInstance().apply { time = it }.get(Calendar.YEAR) }
            .distinct()
        return if (years.isEmpty()) count else "$count · ${years.joinToString(" – ")}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if(this.selectionMode){
            val returnTo = arguments?.getString("returnTo")
            val target = if (returnTo.isNullOrBlank()) ViewPrefScreen.key else returnTo
            findNavController().navigate(
                HomeFragmentDirections.actionGlobalToSettingsDialog(target)
            )
        }
    }
}