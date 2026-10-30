package nl.giejay.android.tv.immich.home

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DividerPresenter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.SectionRow
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.album.AlbumFragment
import nl.giejay.android.tv.immich.assets.AllAssetFragment
import nl.giejay.android.tv.immich.assets.FolderFragment
import nl.giejay.android.tv.immich.assets.RandomAssetsFragment
import nl.giejay.android.tv.immich.assets.RecentAssetsFragment
import nl.giejay.android.tv.immich.assets.SimilarTimeAssetsFragment
import nl.giejay.android.tv.immich.people.PeopleFragment
import nl.giejay.android.tv.immich.settings.SettingsFragment
import nl.giejay.android.tv.immich.shared.fragment.GridFragment
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.timeline.TimelineFragment
import timber.log.Timber

class HomeFragment : BrowseSupportFragment() {
    private lateinit var mRowsAdapter: ArrayObjectAdapter
    private lateinit var rows: List<PageRow>
    private val profileRow = SectionRow(HeaderItem(-1, "profile"))
    val immichRowPresenter = ImmichRowPresenter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("Loaded Home")

        setupUi()
        loadData()

        mainFragmentRegistry.registerFragment(PageRow::class.java, PageRowFragmentFactory())
        // The profile row is never a page, but Leanback starts on it and doesn't reliably swap to
        // the start page selected below: show the first page for it so the app opens on it
        mainFragmentRegistry.registerFragment(SectionRow::class.java, object : FragmentFactory<Fragment>() {
            override fun createFragment(row: Any): Fragment = HEADERS[0].fragment()
        })
        val emptyPage = object : FragmentFactory<Fragment>() {
            override fun createFragment(row: Any): Fragment = GridFragment(hideProgressBar = true)
        }
        mainFragmentRegistry.registerFragment(DividerRow::class.java, emptyPage)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            // Row 0 is the (non-focusable) profile header: start on the first page. Must happen
            // once the view exists, Leanback posts the selection on it.
            selectedPosition = 1
        }
        headersSupportFragment.setOnHeaderViewSelectedListener { _, row ->
            selectedPosition = row?.let { mRowsAdapter.indexOf(it) } ?: 0
            val name = row?.headerItem?.name ?: "-"
            // Timeline is a full-bleed mosaic — never show the Leanback page title there
            // (it flashes and slides away when entering the page).
            if (name == getString(R.string.timeline)) {
                title = ""
                showTitle(false)
            } else {
                title = name
            }
        }

        headersSupportFragment.setOnHeaderClickedListener { _, row ->
            if (row.headerItem.name == getString(R.string.edit)) {
                immichRowPresenter.editMode = !immichRowPresenter.editMode
                if(immichRowPresenter.editMode){
                    showMenu(rows.filter { it.headerItem.name != getString(R.string.settings) })
                } else {
                    showMenu(rows.filter { !PreferenceManager.itemInStringSet(it.headerItem.name, HIDDEN_HOME_ITEMS) })
                }
                adapter.notifyItemRangeChanged(0, mRowsAdapter.size());
            } else if(immichRowPresenter.editMode){
                PreferenceManager.toggleStringSetItem(row.headerItem.name, HIDDEN_HOME_ITEMS)
                adapter.notifyItemRangeChanged(0, mRowsAdapter.size())
            } else{
                if (!this.isInHeadersTransition) {
                    this.startHeadersTransition(false)
//                    this.mainFragment.requireView().requestFocus()
                }
            }
        }
    }

    private fun setupUi() {
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = resources.getColor(R.color.brand_header)
        // First page is Albums
        title = getString(R.string.albums)
//        setOnSearchClickedListener {
//            Toast.makeText(
//                activity, "Search!", Toast.LENGTH_SHORT
//            ).show()
//        }

        // Lines of code to be added
        val sHeaderPresenter: PresenterSelector = ClassPresenterSelector()
            .addClassPresenter(DividerRow::class.java, DividerPresenter())
            .addClassPresenter(
                SectionRow::class.java,
                ProfileHeaderPresenter()
            )
            .addClassPresenter(Row::class.java, immichRowPresenter)

        setHeaderPresenterSelector(sHeaderPresenter)
    }

    private fun loadData() {
        mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = mRowsAdapter
        rows = createRows()
        showMenu(rows.filter { !PreferenceManager.itemInStringSet(it.headerItem.name, HIDDEN_HOME_ITEMS) })
    }

    // Active account on top (non-focusable), then the pages, with a divider before Edit/Settings
    private fun showMenu(pages: List<PageRow>) {
        mRowsAdapter.clear()
        mRowsAdapter.add(profileRow)
        pages.forEach { page ->
            if (page.headerItem.name == getString(R.string.edit)) mRowsAdapter.add(DividerRow())
            mRowsAdapter.add(page)
        }
    }

    private fun createRows(): List<PageRow> {
        return HEADERS.mapIndexed { index, header -> PageRow(HeaderItem(index.toLong(), header.name)) }
    }

    private class PageRowFragmentFactory : FragmentFactory<Fragment>() {
        override fun createFragment(rowObj: Any): Fragment {
            val row = rowObj as Row
            Timber.i("Going to show page: ${row.headerItem.name}")
            return HEADERS[row.headerItem.id.toInt()].fragment()
        }
    }

    companion object {
        private val HEADERS: List<Header> = listOf(
            // Albums first: it is the page shown when the app opens
            Header(ImmichApplication.appContext!!.getString(R.string.albums)) {
                AlbumFragment().apply {
                    arguments = bundleOf("selectionMode" to false)
                }
            },
            Header(ImmichApplication.appContext!!.getString(R.string.timeline)) { TimelineFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.photos)) { AllAssetFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.random)) { RandomAssetsFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.people)) { PeopleFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.recent)) { RecentAssetsFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.seasonal)) { SimilarTimeAssetsFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.folders)) { FolderFragment() },
            Header(ImmichApplication.appContext!!.getString(R.string.edit)) { GridFragment(hideProgressBar = true) },
            Header(ImmichApplication.appContext!!.getString(R.string.settings)) { SettingsFragment() },
        )
    }
}

class Header(val name: String, var show: Boolean = false, val fragment: () -> Fragment)
