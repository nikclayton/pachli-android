/* Copyright 2021 Tusky Contributors
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

package app.pachli.components.search.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import app.pachli.R
import app.pachli.components.search.adapter.SearchStatusesAdapter
import app.pachli.core.activity.BaseActivity
import app.pachli.core.activity.OpenUrlUseCase
import app.pachli.core.activity.extensions.TransitionKind
import app.pachli.core.activity.extensions.startActivityWithDefaultTransition
import app.pachli.core.activity.extensions.startActivityWithTransition
import app.pachli.core.data.model.StatusViewData
import app.pachli.core.data.repository.StatusDisplayOptionsRepository
import app.pachli.core.database.model.AccountEntity
import app.pachli.core.domain.DownloadUrlUseCase
import app.pachli.core.model.Attachment
import app.pachli.core.model.Poll
import app.pachli.core.model.Status
import app.pachli.core.model.Status.Mention
import app.pachli.core.navigation.AttachmentViewData
import app.pachli.core.navigation.ComposeActivityIntent
import app.pachli.core.navigation.ComposeActivityIntent.ComposeOptions
import app.pachli.core.navigation.ComposeActivityIntent.ComposeOptions.InReplyTo
import app.pachli.core.navigation.EditContentFilterActivityIntent
import app.pachli.core.navigation.ReportActivityIntent
import app.pachli.core.navigation.ViewMediaActivityIntent
import app.pachli.core.ui.ClipboardUseCase
import app.pachli.core.ui.SetMarkdownContent
import app.pachli.core.ui.SetMastodonHtmlContent
import app.pachli.interfaces.StatusActionListener
import app.pachli.view.showMuteAccountDialog
import com.bumptech.glide.Glide
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class SearchStatusesFragment : SearchFragment<StatusViewData>(), StatusActionListener<StatusViewData> {
    @Inject
    lateinit var statusDisplayOptionsRepository: StatusDisplayOptionsRepository

    @Inject
    lateinit var downloadUrlUseCase: DownloadUrlUseCase

    @Inject
    lateinit var clipboard: ClipboardUseCase

    @Inject
    lateinit var openUrl: OpenUrlUseCase

    override val data: Flow<PagingData<StatusViewData>>
        get() = viewModel.statusesFlow

    override fun createAdapter(): PagingDataAdapter<StatusViewData, *> {
        val statusDisplayOptions = statusDisplayOptionsRepository.flow.value

        val setStatusContent = if (statusDisplayOptions.renderMarkdown) {
            SetMarkdownContent(requireContext())
        } else {
            SetMastodonHtmlContent
        }

        binding.searchRecyclerView.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), MaterialDividerItemDecoration.VERTICAL),
        )
        binding.searchRecyclerView.layoutManager = LinearLayoutManager(binding.searchRecyclerView.context)
        return SearchStatusesAdapter(Glide.with(this), setStatusContent, statusDisplayOptions, this)
    }

    override fun onContentHiddenChange(viewData: StatusViewData, isShowingContent: Boolean) {
        viewModel.contentHiddenChange(viewData, isShowingContent)
    }

    override fun onReply(viewData: StatusViewData) {
        reply(viewData)
    }

    override fun onFavourite(viewData: StatusViewData, favourite: Boolean) {
        viewModel.favorite(viewData, favourite)
    }

    override fun onBookmark(viewData: StatusViewData, bookmark: Boolean) {
        viewModel.bookmark(viewData, bookmark)
    }

    override fun onMore(view: View, viewData: StatusViewData) {
        more(viewData, view)
    }

    override fun onViewMedia(viewData: StatusViewData, attachmentIndex: Int, view: View?) {
        val actionable = viewData.actionable
        when (actionable.attachments[attachmentIndex].type) {
            Attachment.Type.GIFV, Attachment.Type.VIDEO, Attachment.Type.IMAGE, Attachment.Type.AUDIO -> {
                val attachments = AttachmentViewData.list(actionable)
                val intent = ViewMediaActivityIntent(
                    requireContext(),
                    pachliAccountId,
                    actionable.account.username,
                    attachments,
                    attachmentIndex,
                )
                if (view != null) {
                    val url = actionable.attachments[attachmentIndex].url
                    ViewCompat.setTransitionName(view, url)
                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        requireActivity(),
                        view,
                        url,
                    )
                    startActivityWithDefaultTransition(intent, options.toBundle())
                } else {
                    startActivityWithDefaultTransition(intent)
                }
            }
            Attachment.Type.UNKNOWN -> openUrl(actionable.attachments[attachmentIndex].url)
        }
    }

    override fun onViewThread(status: Status) {
        val actionableStatus = status.actionableStatus
        viewUrlActivity?.viewThread(pachliAccountId, actionableStatus.id, actionableStatus.url)
    }

    override fun onOpenReblog(status: Status) {
        viewUrlActivity?.viewAccount(pachliAccountId, status.account.id)
    }

    override fun onExpandedChange(viewData: StatusViewData, expanded: Boolean) {
        viewModel.expandedChange(viewData, expanded)
    }

    override fun onContentCollapsedChange(viewData: StatusViewData, isCollapsed: Boolean) {
        viewModel.collapsedChange(viewData, isCollapsed)
    }

    override fun onVoteInPoll(viewData: StatusViewData, poll: Poll, choices: List<Int>) {
        viewModel.voteInPoll(viewData, poll, choices)
    }

    override fun clearContentFilter(viewData: StatusViewData) {}

    override fun onReblog(viewData: StatusViewData, reblog: Boolean) {
        viewModel.reblog(viewData, reblog)
    }

    override fun onEditFilterById(pachliAccountId: Long, filterId: String) {
        startActivityWithTransition(
            EditContentFilterActivityIntent.edit(requireContext(), pachliAccountId, filterId),
            TransitionKind.SLIDE_FROM_END,
        )
    }

    private fun reply(status: StatusViewData) {
        val actionableStatus = status.actionable
        val mentionedUsernames = actionableStatus.mentions.map { it.username }
            .toMutableSet()
            .apply {
                add(actionableStatus.account.username)
                remove(viewModel.activeAccount?.username)
            }

        val intent = ComposeActivityIntent(
            requireContext(),
            status.pachliAccountId,
            ComposeOptions(
                inReplyTo = InReplyTo.Status.from(status.actionable),
                replyVisibility = actionableStatus.visibility,
                contentWarning = actionableStatus.spoilerText,
                mentionedUsernames = mentionedUsernames,
                language = actionableStatus.language,
                kind = ComposeOptions.ComposeKind.NEW,
            ),
        )
        startActivityWithDefaultTransition(intent)
    }

    private fun more(statusViewData: StatusViewData, view: View) {
        val id = statusViewData.actionableId
        val status = statusViewData.actionable
        val accountId = status.account.id
        val accountUsername = status.account.username
        val statusUrl = status.url
        val loggedInAccountId = viewModel.activeAccount?.accountId

        val popup = PopupMenu(view.context, view)
        val statusIsByCurrentUser = loggedInAccountId?.equals(accountId) == true
        // Give a different menu depending on whether this is the user's own toot or not.
        if (statusIsByCurrentUser) {
            popup.inflate(R.menu.status_more_for_user)
            val menu = popup.menu
            menu.findItem(R.id.status_open_as).isVisible = !statusUrl.isNullOrBlank()
            when (status.visibility) {
                Status.Visibility.PUBLIC, Status.Visibility.UNLISTED -> {
                    val textId = getString(if (status.isPinned()) R.string.unpin_action else R.string.pin_action)
                    menu.add(0, R.id.pin, 1, textId)
                }
                Status.Visibility.PRIVATE -> {
                    var reblogged = status.reblogged
                    status.reblog?.apply { reblogged = this.reblogged }
                    menu.findItem(R.id.status_reblog_private).isVisible = !reblogged
                    menu.findItem(R.id.status_unreblog_private).isVisible = reblogged
                }
                Status.Visibility.UNKNOWN, Status.Visibility.DIRECT -> {
                } // Ignore
            }
        } else {
            popup.inflate(R.menu.status_more)
            val menu = popup.menu
            menu.findItem(R.id.status_download_media).isVisible = status.attachments.isNotEmpty()
        }

        val openAsItem = popup.menu.findItem(R.id.status_open_as)
        val openAsText = viewUrlActivity?.openAsText
        if (openAsText == null) {
            openAsItem.isVisible = false
        } else {
            openAsItem.title = openAsText
        }

        val mutable = statusIsByCurrentUser || accountIsInMentions(viewModel.activeAccount, status.mentions)
        val muteConversationItem = popup.menu.findItem(R.id.status_mute_conversation).apply {
            isVisible = mutable
        }
        if (mutable) {
            muteConversationItem.setTitle(
                if (status.muted == true) {
                    R.string.action_unmute_conversation
                } else {
                    R.string.action_mute_conversation
                },
            )
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.post_share_content -> {
                    val statusToShare: Status = status.actionableStatus

                    val sendIntent = Intent()
                    sendIntent.action = Intent.ACTION_SEND

                    val stringToShare = statusToShare.account.username +
                        " - " +
                        statusToShare.content
                    sendIntent.putExtra(Intent.EXTRA_TEXT, stringToShare)
                    sendIntent.type = "text/plain"
                    startActivityWithDefaultTransition(Intent.createChooser(sendIntent, resources.getText(R.string.send_post_content_to)))
                    return@setOnMenuItemClickListener true
                }
                R.id.post_share_link -> {
                    val sendIntent = Intent()
                    sendIntent.action = Intent.ACTION_SEND
                    sendIntent.putExtra(Intent.EXTRA_TEXT, statusUrl)
                    sendIntent.type = "text/plain"
                    startActivityWithDefaultTransition(Intent.createChooser(sendIntent, resources.getText(R.string.send_post_link_to)))
                    return@setOnMenuItemClickListener true
                }
                R.id.status_copy_link -> {
                    statusUrl?.let { clipboard.copyTextTo(it) }
                    return@setOnMenuItemClickListener true
                }
                R.id.status_open_as -> {
                    showOpenAsDialog(statusUrl!!, item.title)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_download_media -> {
                    requestDownloadAllMedia(status)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_mute_conversation -> {
                    viewModel.muteConversation(statusViewData, status.muted != true)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_mute -> {
                    onMute(accountId, accountUsername)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_block -> {
                    onBlock(accountId, accountUsername)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_report -> {
                    openReportPage(accountId, accountUsername, id)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_unreblog_private -> {
                    onReblog(statusViewData, false)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_reblog_private -> {
                    onReblog(statusViewData, true)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_delete -> {
                    showConfirmDeleteDialog(statusViewData)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_delete_and_redraft -> {
                    showConfirmEditDialog(pachliAccountId, statusViewData)
                    return@setOnMenuItemClickListener true
                }
                R.id.status_edit -> {
                    editStatus(pachliAccountId, id, status)
                    return@setOnMenuItemClickListener true
                }
                R.id.pin -> {
                    viewModel.pinStatus(statusViewData, !status.isPinned())
                    return@setOnMenuItemClickListener true
                }
            }
            false
        }
        popup.show()
    }

    private fun onBlock(accountId: String, accountUsername: String) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.dialog_block_warning, accountUsername))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.blockAccount(accountId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onMute(accountId: String, accountUsername: String) {
        showMuteAccountDialog(
            this.requireActivity(),
            accountUsername,
        ) { notifications, duration ->
            viewModel.muteAccount(accountId, notifications, duration)
        }
    }

    private fun accountIsInMentions(account: AccountEntity?, mentions: List<Mention>): Boolean {
        return mentions.firstOrNull {
            account?.username == it.username && account.domain == it.url.toUri().host
        } != null
    }

    private fun showOpenAsDialog(statusUrl: String, dialogTitle: CharSequence?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewUrlActivity?.chooseAccount(dialogTitle, false)?.let { account ->
                viewUrlActivity?.openAsAccount(statusUrl, account)
            }
        }
    }

    private fun downloadAllMedia(status: Status) {
        Toast.makeText(context, R.string.downloading_media, Toast.LENGTH_SHORT).show()
        for ((_, url) in status.attachments) {
            lifecycleScope.launch {
                downloadUrlUseCase(url, viewModel.activeAccount!!.fullName, status.actionableStatus.account.username)
            }
        }
    }

    private fun requestDownloadAllMedia(status: Status) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permissions = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            (activity as BaseActivity).requestPermissions(permissions) { _, grantResults ->
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    downloadAllMedia(status)
                } else {
                    Toast.makeText(context, R.string.error_media_download_permission, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            downloadAllMedia(status)
        }
    }

    private fun openReportPage(accountId: String, accountUsername: String, statusId: String) {
        startActivityWithDefaultTransition(ReportActivityIntent(requireContext(), this.pachliAccountId, accountId, accountUsername, statusId))
    }

    // TODO: Identical to the same function in SFragment.kt
    private fun showConfirmDeleteDialog(statusViewData: StatusViewData) {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(R.string.dialog_delete_post_warning)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewModel.deleteStatusAsync(statusViewData.id)
                    viewModel.removeItem(statusViewData)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // TODO: Identical to the same function in SFragment.kt
    private fun showConfirmEditDialog(pachliAccountId: Long, statusViewData: StatusViewData) {
        activity?.let {
            AlertDialog.Builder(it)
                .setMessage(R.string.dialog_redraft_post_warning)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        viewModel.deleteStatusAsync(statusViewData.id).await().onSuccess { redraftStatus ->
                            viewModel.removeItem(statusViewData)

                            val intent = ComposeActivityIntent(
                                requireContext(),
                                pachliAccountId,
                                ComposeOptions(
                                    content = redraftStatus.text.orEmpty(),
                                    inReplyTo = redraftStatus.inReplyToId?.let { InReplyTo.Id(it) },
                                    visibility = redraftStatus.visibility,
                                    contentWarning = redraftStatus.spoilerText,
                                    mediaAttachments = redraftStatus.attachments,
                                    sensitive = redraftStatus.sensitive,
                                    poll = redraftStatus.poll?.toNewPoll(redraftStatus.createdAt),
                                    language = redraftStatus.language,
                                    kind = ComposeOptions.ComposeKind.NEW,
                                ),
                            )
                            startActivityWithDefaultTransition(intent)
                        }.onFailure { error ->
                            Timber.w("error deleting status: %s", error)
                            Toast.makeText(context, app.pachli.core.ui.R.string.error_generic, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun editStatus(pachliAccountId: Long, id: String, status: Status) {
        lifecycleScope.launch {
            mastodonApi.statusSource(id).onSuccess { response ->
                val source = response.body
                val composeOptions = ComposeOptions(
                    content = source.text,
                    inReplyTo = status.inReplyToId?.let { InReplyTo.Id(it) },
                    visibility = status.visibility,
                    contentWarning = source.spoilerText,
                    mediaAttachments = status.attachments,
                    sensitive = status.sensitive,
                    language = status.language,
                    statusId = source.id,
                    poll = status.poll?.toNewPoll(status.createdAt),
                    kind = ComposeOptions.ComposeKind.EDIT_POSTED,
                )
                startActivityWithDefaultTransition(ComposeActivityIntent(requireContext(), pachliAccountId, composeOptions))
            }.onFailure {
                Snackbar.make(
                    requireView(),
                    getString(R.string.error_status_source_load),
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
        }
    }

    companion object {
        fun newInstance(pachliAccountId: Long): SearchStatusesFragment {
            return SearchFragment.newInstance(pachliAccountId)
        }
    }
}
