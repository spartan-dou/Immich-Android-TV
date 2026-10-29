package nl.giejay.android.tv.immich.card

import android.content.Context
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import nl.giejay.android.tv.immich.R

class CardPresenterSelector(val context: Context): PresenterSelector() {
    private val defaultPresenter by lazy { CardPresenter(context) }
    // Same thumbnail-only card as the timeline
    private val imageOnlyPresenter by lazy { CardPresenter(context, R.style.TimelineCardTheme) }

    override fun getPresenter(item: Any?): Presenter {
        return if ((item as? Card)?.imageOnly == true) imageOnlyPresenter else defaultPresenter
    }

    override fun getPresenters(): Array<Presenter> = arrayOf(defaultPresenter, imageOnlyPresenter)
}
