package nl.giejay.android.tv.immich.card

data class Card(
    override val title: String,
    override val description: String?,
    override val id: String,
    override val thumbnailUrl: String?,
    override val backgroundUrl: String?,
    override var selected: Boolean = false,
    // Photo grids show the picture only (no file name footer)
    val imageOnly: Boolean = false,
    // Without the file name, a play badge is what tells videos apart in the grids
    val isVideo: Boolean = false) : ICard
