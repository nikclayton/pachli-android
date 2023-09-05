/*
 * Copyright 2019 Conny Duck
 *
 * This file is a part of Pachli.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Pachli is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.pachli

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import app.pachli.adapter.ItemInteractionListener
import app.pachli.adapter.TabAdapter
import app.pachli.appstore.EventHub
import app.pachli.appstore.MainTabsChangedEvent
import app.pachli.databinding.ActivityTabPreferenceBinding
import app.pachli.di.Injectable
import app.pachli.entity.MastoList
import app.pachli.network.MastodonApi
import app.pachli.util.getDimension
import app.pachli.util.hide
import app.pachli.util.show
import app.pachli.util.unsafeLazy
import app.pachli.util.viewBinding
import app.pachli.util.visible
import at.connyduck.calladapter.networkresult.fold
import at.connyduck.sparkbutton.helpers.Utils
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialArcMotion
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.regex.Pattern
import javax.inject.Inject

class TabPreferenceActivity : BaseActivity(), Injectable, ItemInteractionListener {

    @Inject
    lateinit var mastodonApi: MastodonApi

    @Inject
    lateinit var eventHub: EventHub

    private val binding by viewBinding(ActivityTabPreferenceBinding::inflate)

    private lateinit var currentTabs: MutableList<TabData>
    private lateinit var currentTabsAdapter: TabAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var addTabAdapter: TabAdapter

    private var tabsChanged = false

    private val selectedItemElevation by unsafeLazy { resources.getDimension(R.dimen.selected_drag_item_elevation) }

    private val hashtagRegex by unsafeLazy { Pattern.compile("([\\w_]*[\\p{Alpha}_][\\w_]*)", Pattern.CASE_INSENSITIVE) }

    private val onFabDismissedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            toggleFab(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)

        setSupportActionBar(binding.includedToolbar.toolbar)

        supportActionBar?.apply {
            setTitle(R.string.title_tab_preferences)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        currentTabs = accountManager.activeAccount?.tabPreferences.orEmpty().toMutableList()
        currentTabsAdapter = TabAdapter(currentTabs, false, this, currentTabs.size <= MIN_TAB_COUNT)
        binding.currentTabsRecyclerView.adapter = currentTabsAdapter
        binding.currentTabsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.currentTabsRecyclerView.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        addTabAdapter = TabAdapter(listOf(createTabDataFromId(DIRECT)), true, this)
        binding.addTabRecyclerView.adapter = addTabAdapter
        binding.addTabRecyclerView.layoutManager = LinearLayoutManager(this)

        touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.END)
                }

                override fun isLongPressDragEnabled(): Boolean {
                    return true
                }

                override fun isItemViewSwipeEnabled(): Boolean {
                    return MIN_TAB_COUNT < currentTabs.size
                }

                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val temp = currentTabs[viewHolder.bindingAdapterPosition]
                    currentTabs[viewHolder.bindingAdapterPosition] = currentTabs[target.bindingAdapterPosition]
                    currentTabs[target.bindingAdapterPosition] = temp

                    currentTabsAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    saveTabs()
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    onTabRemoved(viewHolder.bindingAdapterPosition)
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.elevation = selectedItemElevation
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.elevation = 0f
                }
            },
        )

        touchHelper.attachToRecyclerView(binding.currentTabsRecyclerView)

        binding.actionButton.setOnClickListener {
            toggleFab(true)
        }

        binding.scrim.setOnClickListener {
            toggleFab(false)
        }

        updateAvailableTabs()

        onBackPressedDispatcher.addCallback(onFabDismissedCallback)
    }

    override fun onTabAdded(tab: TabData) {
        toggleFab(false)

        if (tab.id == HASHTAG) {
            showAddHashtagDialog()
            return
        }

        if (tab.id == LIST) {
            showSelectListDialog()
            return
        }

        currentTabs.add(tab)
        currentTabsAdapter.notifyItemInserted(currentTabs.size - 1)
        updateAvailableTabs()
        saveTabs()
    }

    override fun onTabRemoved(position: Int) {
        currentTabs.removeAt(position)
        currentTabsAdapter.notifyItemRemoved(position)
        updateAvailableTabs()
        saveTabs()
    }

    override fun onActionChipClicked(tab: TabData, tabPosition: Int) {
        showAddHashtagDialog(tab, tabPosition)
    }

    override fun onChipClicked(tab: TabData, tabPosition: Int, chipPosition: Int) {
        val newArguments = tab.arguments.filterIndexed { i, _ -> i != chipPosition }
        val newTab = tab.copy(arguments = newArguments)
        currentTabs[tabPosition] = newTab
        saveTabs()

        currentTabsAdapter.notifyItemChanged(tabPosition)
    }

    private fun toggleFab(expand: Boolean) {
        val transition = MaterialContainerTransform().apply {
            startView = if (expand) binding.actionButton else binding.sheet
            val endView: View = if (expand) binding.sheet else binding.actionButton
            this.endView = endView
            addTarget(endView)
            scrimColor = Color.TRANSPARENT
            setPathMotion(MaterialArcMotion())
        }

        TransitionManager.beginDelayedTransition(binding.root, transition)
        binding.actionButton.visible(!expand)
        binding.sheet.visible(expand)
        binding.scrim.visible(expand)

        onFabDismissedCallback.isEnabled = expand
    }

    private fun showAddHashtagDialog(tab: TabData? = null, tabPosition: Int = 0) {
        val frameLayout = FrameLayout(this)
        val padding = Utils.dpToPx(this, 8)
        frameLayout.updatePadding(left = padding, right = padding)

        val editText = AppCompatEditText(this)
        editText.setHint(R.string.edit_hashtag_hint)
        editText.setText("")
        frameLayout.addView(editText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_hashtag_title)
            .setView(frameLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val input = editText.text.toString().trim()
                if (tab == null) {
                    val newTab = createTabDataFromId(HASHTAG, listOf(input))
                    currentTabs.add(newTab)
                    currentTabsAdapter.notifyItemInserted(currentTabs.size - 1)
                } else {
                    val newTab = tab.copy(arguments = tab.arguments + input)
                    currentTabs[tabPosition] = newTab

                    currentTabsAdapter.notifyItemChanged(tabPosition)
                }

                updateAvailableTabs()
                saveTabs()
            }
            .create()

        editText.doOnTextChanged { s, _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = validateHashtag(s)
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = validateHashtag(editText.text)
        editText.requestFocus()
    }

    private fun showSelectListDialog() {
        val adapter = object : ArrayAdapter<MastoList>(this, android.R.layout.simple_list_item_1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                getItem(position)?.let { item -> (view as TextView).text = item.title }
                return view
            }
        }

        val statusLayout = LinearLayout(this)
        statusLayout.gravity = Gravity.CENTER
        val progress = ProgressBar(this)
        val preferredPadding = getDimension(this, androidx.appcompat.R.attr.dialogPreferredPadding)
        progress.setPadding(preferredPadding, 0, preferredPadding, 0)
        progress.visible(false)

        val noListsText = TextView(this)
        noListsText.setPadding(preferredPadding, 0, preferredPadding, 0)
        noListsText.text = getText(R.string.select_list_empty)
        noListsText.visible(false)

        statusLayout.addView(progress)
        statusLayout.addView(noListsText)

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(R.string.select_list_title)
            .setNeutralButton(R.string.select_list_manage) { _, _ ->
                val listIntent = Intent(applicationContext, ListsActivity::class.java)
                startActivity(listIntent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setView(statusLayout)
            .setAdapter(adapter) { _, position ->
                adapter.getItem(position)?.let { item ->
                    val newTab = createTabDataFromId(LIST, listOf(item.id, item.title))
                    currentTabs.add(newTab)
                    currentTabsAdapter.notifyItemInserted(currentTabs.size - 1)
                    updateAvailableTabs()
                    saveTabs()
                }
            }

        val showProgressBarJob = getProgressBarJob(progress, 500)
        showProgressBarJob.start()

        val dialog = dialogBuilder.show()

        lifecycleScope.launch {
            mastodonApi.getLists().fold(
                { lists ->
                    showProgressBarJob.cancel()
                    adapter.addAll(lists)
                    if (lists.isEmpty()) {
                        noListsText.show()
                    }
                },
                { throwable ->
                    dialog.hide()
                    Log.e("TabPreferenceActivity", "failed to load lists", throwable)
                    Snackbar.make(binding.root, R.string.error_list_load, Snackbar.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun getProgressBarJob(progressView: View, delayMs: Long) = this.lifecycleScope.launch(
        start = CoroutineStart.LAZY,
    ) {
        try {
            delay(delayMs)
            progressView.show()
            awaitCancellation()
        } finally {
            progressView.hide()
        }
    }

    private fun validateHashtag(input: CharSequence?): Boolean {
        val trimmedInput = input?.trim() ?: ""
        return trimmedInput.isNotEmpty() && hashtagRegex.matcher(trimmedInput).matches()
    }

    private fun updateAvailableTabs() {
        val addableTabs: MutableList<TabData> = mutableListOf()

        val homeTab = createTabDataFromId(HOME)
        if (!currentTabs.contains(homeTab)) {
            addableTabs.add(homeTab)
        }
        val notificationTab = createTabDataFromId(NOTIFICATIONS)
        if (!currentTabs.contains(notificationTab)) {
            addableTabs.add(notificationTab)
        }
        val localTab = createTabDataFromId(LOCAL)
        if (!currentTabs.contains(localTab)) {
            addableTabs.add(localTab)
        }
        val federatedTab = createTabDataFromId(FEDERATED)
        if (!currentTabs.contains(federatedTab)) {
            addableTabs.add(federatedTab)
        }
        val directMessagesTab = createTabDataFromId(DIRECT)
        if (!currentTabs.contains(directMessagesTab)) {
            addableTabs.add(directMessagesTab)
        }
        val trendingTagsTab = createTabDataFromId(TRENDING_TAGS)
        if (!currentTabs.contains(trendingTagsTab)) {
            addableTabs.add(trendingTagsTab)
        }
        val trendingLinksTab = createTabDataFromId(TRENDING_LINKS)
        if (!currentTabs.contains(trendingLinksTab)) {
            addableTabs.add(trendingLinksTab)
        }
        createTabDataFromId(TRENDING_STATUSES).apply {
            currentTabs.contains(this) || addableTabs.add(this)
        }
        val bookmarksTab = createTabDataFromId(BOOKMARKS)
        if (!currentTabs.contains(trendingTagsTab)) {
            addableTabs.add(bookmarksTab)
        }

        addableTabs.add(createTabDataFromId(HASHTAG))
        addableTabs.add(createTabDataFromId(LIST))

        addTabAdapter.updateData(addableTabs)

        currentTabsAdapter.setRemoveButtonVisible(currentTabs.size > MIN_TAB_COUNT)
    }

    override fun onStartDelete(viewHolder: RecyclerView.ViewHolder) {
        touchHelper.startSwipe(viewHolder)
    }

    override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
        touchHelper.startDrag(viewHolder)
    }

    private fun saveTabs() {
        accountManager.activeAccount?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                it.tabPreferences = currentTabs
                accountManager.saveAccount(it)
            }
        }
        tabsChanged = true
    }

    override fun onPause() {
        super.onPause()
        if (tabsChanged) {
            lifecycleScope.launch {
                eventHub.dispatch(MainTabsChangedEvent(currentTabs))
            }
        }
    }

    companion object {
        private const val MIN_TAB_COUNT = 2
    }
}