package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.util.Utils.compareToNullSafe

// The server can only page by date, so the other orders need the whole list (an album) client-side
enum class PhotosOrder(val sort: Comparator<Asset>, val isDateOrder: Boolean = false): EnumWithTitle {
    NEWEST_OLDEST(
        { a1, a2 ->
            (a2.exifInfo?.dateTimeOriginal ?: a2.fileModifiedAt)?.compareToNullSafe(
                a1.exifInfo?.dateTimeOriginal ?: a1.fileModifiedAt
            ) ?: 1
        },
        isDateOrder = true
    ) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_newest_oldest)
        }
    },
    OLDEST_NEWEST(
        { a1, a2 ->
            (a1.exifInfo?.dateTimeOriginal ?: a1.fileModifiedAt)?.compareToNullSafe(
                a2.exifInfo?.dateTimeOriginal ?: a2.fileModifiedAt
            ) ?: 1
        },
        isDateOrder = true
    ) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_oldest_newest)
        }
    },
    FILENAME_A_Z(compareBy(String.CASE_INSENSITIVE_ORDER) { it.originalFileName.orEmpty() }) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_filename_az)
        }
    },
    FILENAME_Z_A(FILENAME_A_Z.sort.reversed()) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_filename_za)
        }
    },
    FAVORITES_FIRST(compareByDescending<Asset> { it.isFavorite }.then(NEWEST_OLDEST.sort)) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_favorites_first)
        }
    },
    // A comparator must be stable: shuffle by a per-session seed instead of Random
    RANDOM(compareBy { (it.id.hashCode() xor RandomSeed.value).toLong() }) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_random)
        }
    };

    companion object {
        fun valueOfSafe(name: String, default: PhotosOrder): PhotosOrder{
            return entries.find { it.toString() == name } ?: default
        }

        val dateOrders: Array<PhotosOrder> get() = entries.filter { it.isDateOrder }.toTypedArray()
    }
}

private object RandomSeed {
    val value = (Math.random() * Int.MAX_VALUE).toInt()
}
