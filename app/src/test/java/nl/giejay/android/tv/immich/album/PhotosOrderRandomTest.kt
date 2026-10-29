package nl.giejay.android.tv.immich.album

import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import org.junit.Test

// How RANDOM shuffles is covered by PhotosOrderTest
class PhotosOrderRandomTest {
    @Test
    fun `random can be restored from its stored name`() {
        assertThat(PhotosOrder.valueOfSafe("RANDOM", PhotosOrder.OLDEST_NEWEST)).isEqualTo(PhotosOrder.RANDOM)
    }
}
