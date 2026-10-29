package nl.giejay.android.tv.immich.album

import androidx.navigation.fragment.findNavController
import arrow.core.Either
import nl.giejay.android.tv.immich.shared.util.SortChooser
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.assets.GenericAssetFragment
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE_FOR_SPECIFIC_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PHOTOS_SORTING_FOR_SPECIFIC_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder


class AlbumDetailsFragment : GenericAssetFragment() {
    lateinit var albumId: String
    lateinit var albumName: String

    override fun getFilterKey(): EnumByTitlePref<ContentType> {
        albumId = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumId
        albumName = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumName
        return FILTER_CONTENT_TYPE_FOR_SPECIFIC_ALBUM(albumId, albumName)
    }

    override fun getSortingKey(): EnumByTitlePref<PhotosOrder> {
        albumId = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumId
        albumName = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumName
        return PHOTOS_SORTING_FOR_SPECIFIC_ALBUM(albumId, albumName)
    }

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Asset>> {
        if (!currentSort.isDateOrder) {
            // Name/favorites/random orders need every asset: load the whole album once, sorted
            // client-side by setData (the server only pages by date)
            if (page > startPage) return Either.Right(emptyList())
            return apiClient.listAssetsFromAlbum(listOf(albumId), currentFilter, MAX_SEARCH_PAGE_SIZE)
                .map { it.assets.map { a -> a.copy(albumName = albumName) } }
        }
        return apiClient.listAssets(
            page = page,
            pageCount = pageCount,
            order = if (currentSort == PhotosOrder.NEWEST_OLDEST) "desc" else "asc",
            contentType = currentFilter,
            albumIds = listOf(albumId)
        ).map { it.assets.map { a -> a.copy(albumName = albumName) } }
    }

    override fun onItemSelected(card: Card, indexOf: Int) {
        // no use case yet
    }

    override fun openPopUpMenu() {
        SortChooser.show(requireContext(), getSortingKey()) {
            findNavController().navigate(
                HomeFragmentDirections.actionGlobalToSettingsDialog("album_details", albumId, albumName)
            )
        }
    }

    override fun setTitle(response: List<Asset>) {
        title = albumName
    }
}
