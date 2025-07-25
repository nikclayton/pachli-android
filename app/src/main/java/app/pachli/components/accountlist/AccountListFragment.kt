/* Copyright 2017 Andrew Dawson
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
 * You should have received a copy of the GNU General Public License along with Pachli; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.pachli.components.accountlist

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import app.pachli.R
import app.pachli.components.accountlist.adapter.AccountAdapter
import app.pachli.components.accountlist.adapter.BlocksAdapter
import app.pachli.components.accountlist.adapter.FollowAdapter
import app.pachli.components.accountlist.adapter.FollowRequestsAdapter
import app.pachli.components.accountlist.adapter.FollowRequestsHeaderAdapter
import app.pachli.components.accountlist.adapter.MutesAdapter
import app.pachli.core.activity.ViewUrlActivity
import app.pachli.core.activity.extensions.startActivityWithDefaultTransition
import app.pachli.core.common.extensions.hide
import app.pachli.core.common.extensions.show
import app.pachli.core.common.extensions.viewBinding
import app.pachli.core.common.util.unsafeLazy
import app.pachli.core.data.repository.AccountManager
import app.pachli.core.model.Relationship
import app.pachli.core.navigation.AccountActivityIntent
import app.pachli.core.navigation.AccountListActivityIntent.Kind
import app.pachli.core.navigation.AccountListActivityIntent.Kind.BLOCKS
import app.pachli.core.navigation.AccountListActivityIntent.Kind.FAVOURITED
import app.pachli.core.navigation.AccountListActivityIntent.Kind.FOLLOWERS
import app.pachli.core.navigation.AccountListActivityIntent.Kind.FOLLOWS
import app.pachli.core.navigation.AccountListActivityIntent.Kind.FOLLOW_REQUESTS
import app.pachli.core.navigation.AccountListActivityIntent.Kind.MUTES
import app.pachli.core.navigation.AccountListActivityIntent.Kind.REBLOGGED
import app.pachli.core.navigation.TimelineActivityIntent
import app.pachli.core.network.model.HttpHeaderLink
import app.pachli.core.network.model.TimelineAccount
import app.pachli.core.network.model.asModel
import app.pachli.core.network.retrofit.MastodonApi
import app.pachli.core.network.retrofit.apiresult.ApiResult
import app.pachli.core.preferences.SharedPreferencesRepository
import app.pachli.core.ui.BackgroundMessage
import app.pachli.core.ui.LinkListener
import app.pachli.core.ui.extensions.applyDefaultWindowInsets
import app.pachli.databinding.FragmentAccountListBinding
import app.pachli.interfaces.AccountActionListener
import app.pachli.interfaces.AppBarLayoutHost
import app.pachli.usecase.TimelineCases
import app.pachli.view.EndlessOnScrollListener
import com.bumptech.glide.Glide
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.properties.Delegates
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class AccountListFragment :
    Fragment(R.layout.fragment_account_list),
    AccountActionListener,
    LinkListener {

    @Inject
    lateinit var api: MastodonApi

    @Inject
    lateinit var accountManager: AccountManager

    @Inject
    lateinit var sharedPreferencesRepository: SharedPreferencesRepository

    @Inject
    lateinit var timelineCases: TimelineCases

    private val binding by viewBinding(FragmentAccountListBinding::bind)

    private lateinit var kind: Kind
    private var id: String? = null

    private lateinit var scrollListener: EndlessOnScrollListener
    private lateinit var adapter: AccountAdapter<*>
    private var fetching = false
    private var bottomId: String? = null

    private var pachliAccountId by Delegates.notNull<Long>()

    private val glide by unsafeLazy { Glide.with(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pachliAccountId = requireArguments().getLong(ARG_PACHLI_ACCOUNT_ID)
        kind = requireArguments().getSerializable(ARG_KIND) as Kind
        id = requireArguments().getString(ARG_ID)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val layoutManager = LinearLayoutManager(view.context)
        with(binding.recyclerView) {
            applyDefaultWindowInsets()
            setHasFixedSize(true)
            this.layoutManager = layoutManager
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            addItemDecoration(
                MaterialDividerItemDecoration(requireContext(), MaterialDividerItemDecoration.VERTICAL),
            )
        }

        binding.swipeRefreshLayout.setOnRefreshListener { fetchAccounts() }
        binding.swipeRefreshLayout.setColorSchemeColors(MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary))

        val animateAvatar = sharedPreferencesRepository.animateAvatars
        val animateEmojis = sharedPreferencesRepository.animateEmojis
        val showBotOverlay = sharedPreferencesRepository.showBotOverlay

        val activeAccount = accountManager.activeAccount!!

        adapter = when (kind) {
            BLOCKS -> BlocksAdapter(glide, this, animateAvatar, animateEmojis, showBotOverlay)
            MUTES -> MutesAdapter(glide, this, animateAvatar, animateEmojis, showBotOverlay)
            FOLLOW_REQUESTS -> {
                val headerAdapter = FollowRequestsHeaderAdapter(
                    instanceName = activeAccount.domain,
                    accountLocked = activeAccount.locked,
                )
                val followRequestsAdapter = FollowRequestsAdapter(glide, this, this, animateAvatar, animateEmojis, showBotOverlay)
                binding.recyclerView.adapter = ConcatAdapter(headerAdapter, followRequestsAdapter)
                followRequestsAdapter
            }

            else -> FollowAdapter(glide, this, animateAvatar, animateEmojis, showBotOverlay)
        }
        if (binding.recyclerView.adapter == null) {
            binding.recyclerView.adapter = adapter
        }

        scrollListener = object : EndlessOnScrollListener(layoutManager) {
            override fun onLoadMore(totalItemsCount: Int, view: RecyclerView) {
                if (bottomId == null) {
                    return
                }
                fetchAccounts(bottomId)
            }
        }

        binding.recyclerView.addOnScrollListener(scrollListener)

        fetchAccounts()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? AppBarLayoutHost)?.appBarLayout?.setLiftOnScrollTargetView(binding.recyclerView)
    }

    override fun onViewTag(tag: String) {
        startActivityWithDefaultTransition(
            TimelineActivityIntent.hashtag(requireContext(), pachliAccountId, tag),
        )
    }

    override fun onViewAccount(id: String) {
        startActivityWithDefaultTransition(
            AccountActivityIntent(requireContext(), pachliAccountId, id),
        )
    }

    override fun onViewUrl(url: String) {
        (activity as? ViewUrlActivity)?.viewUrl(pachliAccountId, url)
    }

    override fun onMute(mute: Boolean, id: String, position: Int, notifications: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!mute) {
                timelineCases.unmuteAccount(pachliAccountId, id)
            } else {
                timelineCases.muteAccount(pachliAccountId, id, notifications)
            }
                .onSuccess { onMuteSuccess(mute, id, position, notifications) }
                .onFailure { onMuteFailure(mute, id, notifications) }
        }
    }

    private fun onMuteSuccess(muted: Boolean, id: String, position: Int, notifications: Boolean) {
        val mutesAdapter = adapter as MutesAdapter
        if (muted) {
            mutesAdapter.updateMutingNotifications(id, notifications, position)
            return
        }
        val unmutedUser = mutesAdapter.removeItem(position)

        if (unmutedUser != null) {
            Snackbar.make(binding.recyclerView, R.string.confirmation_unmuted, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) {
                    mutesAdapter.addItem(unmutedUser, position)
                    onMute(true, id, position, notifications)
                }
                .show()
        }
    }

    private fun onMuteFailure(mute: Boolean, accountId: String, notifications: Boolean) {
        val verb = if (mute) {
            if (notifications) {
                "mute (notifications = true)"
            } else {
                "mute (notifications = false)"
            }
        } else {
            "unmute"
        }
        Timber.e("Failed to %s account id %s", verb, accountId)
    }

    override fun onBlock(block: Boolean, id: String, position: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (block) {
                timelineCases.blockAccount(pachliAccountId, id)
            } else {
                timelineCases.unblockAccount(pachliAccountId, id)
            }
                .onSuccess { onBlockSuccess(block, id, position) }
                .onFailure { onBlockFailure(block, id, it.throwable) }
        }
    }

    private fun onBlockSuccess(blocked: Boolean, id: String, position: Int) {
        if (blocked) {
            return
        }
        val blocksAdapter = adapter as BlocksAdapter
        val unblockedUser = blocksAdapter.removeItem(position)

        if (unblockedUser != null) {
            Snackbar.make(binding.recyclerView, R.string.confirmation_unblocked, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) {
                    blocksAdapter.addItem(unblockedUser, position)
                    onBlock(true, id, position)
                }
                .show()
        }
    }

    private fun onBlockFailure(block: Boolean, accountId: String, throwable: Throwable) {
        val verb = if (block) {
            "block"
        } else {
            "unblock"
        }
        Timber.e(throwable, "Failed to %s account accountId %s", verb, accountId)
    }

    override fun onRespondToFollowRequest(
        accept: Boolean,
        accountId: String,
        position: Int,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (accept) {
                api.authorizeFollowRequest(accountId)
            } else {
                api.rejectFollowRequest(accountId)
            }.onSuccess {
                onRespondToFollowRequestSuccess(position)
            }.onFailure { error ->
                val verb = if (accept) "accept" else "reject"
                Timber.e("Failed to %s accountId %s: %s", verb, accountId, error.fmt(requireContext()))
            }
        }
    }

    private fun onRespondToFollowRequestSuccess(position: Int) {
        val followRequestsAdapter = adapter as FollowRequestsAdapter
        followRequestsAdapter.removeItem(position)
    }

    private suspend fun getFetchCallByListType(fromId: String?): ApiResult<List<TimelineAccount>> {
        return when (kind) {
            FOLLOWS -> {
                val accountId = requireId(kind, id)
                api.accountFollowing(accountId, fromId)
            }
            FOLLOWERS -> {
                val accountId = requireId(kind, id)
                api.accountFollowers(accountId, fromId)
            }
            BLOCKS -> api.blocks(fromId)
            MUTES -> api.mutes(fromId)
            FOLLOW_REQUESTS -> api.followRequests(fromId)
            REBLOGGED -> {
                val statusId = requireId(kind, id)
                api.statusRebloggedBy(statusId, fromId)
            }
            FAVOURITED -> {
                val statusId = requireId(kind, id)
                api.statusFavouritedBy(statusId, fromId)
            }
        }
    }

    private fun requireId(kind: Kind, id: String?): String {
        return requireNotNull(id) { "id must not be null for kind " + kind.name }
    }

    private fun fetchAccounts(fromId: String? = null) {
        if (fetching) {
            return
        }
        fetching = true
        binding.swipeRefreshLayout.isRefreshing = true

        if (fromId != null) {
            binding.recyclerView.post { adapter.setBottomLoading(true) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val response = getFetchCallByListType(fromId)
                .getOrElse {
                    onFetchAccountsFailure(it.throwable)
                    return@launch
                }

            val accountList = response.body
            val linkHeader = response.headers["Link"]
            onFetchAccountsSuccess(accountList, linkHeader)
        }
    }

    private fun onFetchAccountsSuccess(accounts: List<TimelineAccount>, linkHeader: String?) {
        adapter.setBottomLoading(false)
        binding.swipeRefreshLayout.isRefreshing = false

        val links = HttpHeaderLink.parse(linkHeader)
        val next = HttpHeaderLink.findByRelationType(links, "next")
        val fromId = next?.uri?.getQueryParameter("max_id")

        if (adapter.itemCount > 0) {
            adapter.addItems(accounts.asModel())
        } else {
            adapter.update(accounts.asModel())
        }

        if (adapter is MutesAdapter) {
            fetchRelationships(accounts.map { it.id })
        }

        bottomId = fromId

        fetching = false

        if (adapter.itemCount == 0) {
            binding.messageView.show()
            binding.messageView.setup(BackgroundMessage.Empty())
        } else {
            binding.messageView.hide()
        }
    }

    private fun fetchRelationships(ids: List<String>) {
        lifecycleScope.launch {
            api.relationships(ids)
                .onSuccess { onFetchRelationshipsSuccess(it.body.asModel()) }
                .onFailure { throwable ->
                    Timber.e("Fetch failure for relationships of accounts: %s: %s", ids, throwable)
                }
        }
    }

    private fun onFetchRelationshipsSuccess(relationships: List<Relationship>) {
        val mutesAdapter = adapter as MutesAdapter
        val mutingNotificationsMap = HashMap<String, Boolean>()
        relationships.map { mutingNotificationsMap.put(it.id, it.mutingNotifications) }
        mutesAdapter.updateMutingNotificationsMap(mutingNotificationsMap)
    }

    private fun onFetchAccountsFailure(throwable: Throwable) {
        fetching = false
        binding.swipeRefreshLayout.isRefreshing = false
        Timber.e(throwable, "Fetch failure")

        if (adapter.itemCount == 0) {
            binding.messageView.show()
            binding.messageView.setup(throwable) {
                binding.messageView.hide()
                this.fetchAccounts(null)
            }
        }
    }

    companion object {
        private const val ARG_PACHLI_ACCOUNT_ID = "app.pachli.ARG_PACHLI_ACCOUNT_ID"
        private const val ARG_KIND = "app.pachli.ARG_KIND"
        private const val ARG_ID = "app.pachli.ARG_ID"

        fun newInstance(pachliAccountId: Long, kind: Kind, id: String? = null): AccountListFragment {
            return AccountListFragment().apply {
                arguments = Bundle(3).apply {
                    putLong(ARG_PACHLI_ACCOUNT_ID, pachliAccountId)
                    putSerializable(ARG_KIND, kind)
                    putString(ARG_ID, id)
                }
            }
        }
    }
}
