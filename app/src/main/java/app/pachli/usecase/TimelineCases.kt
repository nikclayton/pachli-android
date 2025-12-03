/* Copyright 2018 charlag
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

package app.pachli.usecase

import app.pachli.components.compose.ComposeActivity
import app.pachli.core.common.di.ApplicationScope
import app.pachli.core.data.model.IStatusViewData
import app.pachli.core.data.repository.AccountManager
import app.pachli.core.data.repository.DraftRepository
import app.pachli.core.data.repository.OfflineFirstStatusRepository
import app.pachli.core.data.repository.StatusActionError
import app.pachli.core.database.dao.RemoteKeyDao
import app.pachli.core.database.dao.TranslatedStatusDao
import app.pachli.core.database.model.AccountEntity
import app.pachli.core.database.model.RemoteKeyEntity
import app.pachli.core.database.model.RemoteKeyEntity.RemoteKeyKind
import app.pachli.core.database.model.TranslationState
import app.pachli.core.database.model.toEntity
import app.pachli.core.eventhub.BlockEvent
import app.pachli.core.eventhub.EventHub
import app.pachli.core.eventhub.MuteEvent
import app.pachli.core.eventhub.StatusDeletedEvent
import app.pachli.core.eventhub.UnfollowEvent
import app.pachli.core.model.AccountSource
import app.pachli.core.model.Draft
import app.pachli.core.model.Status
import app.pachli.core.model.Timeline
import app.pachli.core.model.asQuotePolicy
import app.pachli.core.model.translation.TranslatedStatus
import app.pachli.core.network.model.DeletedStatus
import app.pachli.core.network.model.Relationship
import app.pachli.core.network.retrofit.MastodonApi
import app.pachli.core.network.retrofit.apiresult.ApiError
import app.pachli.core.network.retrofit.apiresult.ApiResponse
import app.pachli.core.network.retrofit.apiresult.ApiResult
import app.pachli.translation.TranslationService
import app.pachli.translation.TranslatorError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class TimelineCases @Inject constructor(
    private val mastodonApi: MastodonApi,
    private val eventHub: EventHub,
    private val statusRepository: OfflineFirstStatusRepository,
    private val translatedStatusDao: TranslatedStatusDao,
    private val translationService: TranslationService,
    private val remoteKeyDao: RemoteKeyDao,
    private val accountManager: AccountManager,
    private val draftRepository: DraftRepository,
    @ApplicationScope private val externalScope: CoroutineScope,
) {
    suspend fun muteConversation(pachliAccountId: Long, statusId: String, mute: Boolean): Result<Status, StatusActionError.Mute> {
        return statusRepository.mute(pachliAccountId, statusId, mute)
    }

    /**
     * Sends a follow request for [accountId] to the server for [pachliAccountId].
     *
     * On success:
     *
     * - The following relationship is added to [AccountManager].
     *
     * @param pachliAccountId
     * @param accountId ID of account to follow.
     * @param showReblogs If true, show reblogs from this account. Null uses server default.
     * @param notify If true, receive notifications when this account posts. Null uses server default.
     */
    suspend fun followAccount(pachliAccountId: Long, accountId: String, showReblogs: Boolean? = null, notify: Boolean? = null): ApiResult<Relationship> {
        return mastodonApi.followAccount(accountId, showReblogs, notify)
            .onSuccess { accountManager.followAccount(pachliAccountId, accountId) }
    }

    /**
     * Unfollow [accountId].
     *
     * On success:
     *
     * - The following relationship is removed from [AccountManager].
     * - [UnfollowEvent] is dispatched.
     *
     * @param pachliAccountId
     * @param accountID ID of the account to unfollow.
     */
    suspend fun unfollowAccount(pachliAccountId: Long, accountId: String): ApiResult<Relationship> {
        return mastodonApi.unfollowAccount(accountId)
            .onSuccess {
                accountManager.unfollowAccount(pachliAccountId, accountId)
                eventHub.dispatch(UnfollowEvent(pachliAccountId, accountId))
            }
    }

    /**
     * Subscribe to [accountId].
     *
     * @param pachliAccountId
     * @param accountId ID of the account to subscribe to.
     */
    suspend fun subscribeAccount(pachliAccountId: Long, accountId: String): ApiResult<Relationship> {
        return mastodonApi.subscribeAccount(accountId)
    }

    /**
     * Unsubscribe from [accountId].
     *
     * @param pachliAccountId
     * @param accountId ID of the account to unsubscribe from.
     */
    suspend fun unsubscribeAccount(pachliAccountId: Long, accountId: String): ApiResult<Relationship> {
        return mastodonApi.unsubscribeAccount(accountId)
    }

    /**
     * Mute [accountId].
     *
     * On success:
     *
     * - [MuteEvent] is dispatched.
     *
     * @param pachliAccountId
     * @param accountId ID of the account to mute.
     */
    suspend fun muteAccount(pachliAccountId: Long, accountId: String, notifications: Boolean? = null, duration: Int? = null): Result<ApiResponse<Relationship>, ApiError> {
        return mastodonApi.muteAccount(accountId, notifications, duration)
            .onSuccess { eventHub.dispatch(MuteEvent(pachliAccountId, accountId)) }
    }

    /**
     * Unmute [accountId].
     *
     * @param pachliAccountId
     * @param accountId ID of the account to unmute.
     */
    suspend fun unmuteAccount(pachliAccountId: Long, accountId: String): ApiResult<Relationship> {
        return mastodonApi.unmuteAccount(accountId)
    }

    /**
     * Block [accountId].
     *
     * On success:
     *
     * - [BlockEvent] is dispatched.
     *
     * @param pachliAccountId
     * @param accountId ID of the account to block.
     */
    suspend fun blockAccount(pachliAccountId: Long, accountId: String): Result<ApiResponse<Relationship>, ApiError> {
        return mastodonApi.blockAccount(accountId)
            .onSuccess { eventHub.dispatch(BlockEvent(pachliAccountId, accountId)) }
    }

    /**
     * Unblock [accountId].
     *
     * @param pachliAccountId
     * @param accountId ID of the account to unblock.
     */
    suspend fun unblockAccount(pachliAccountId: Long, accountId: String): ApiResult<Relationship> {
        return mastodonApi.unblockAccount(accountId)
    }

    suspend fun delete(statusId: String): ApiResult<DeletedStatus> {
        // Some servers (Pleroma?, see https://github.com/tuskyapp/Tusky/pull/1461) don't
        // return the text of the status when deleting. Work around that by fetching
        // the status source first, and using content from that if necessary.
        val source = mastodonApi.statusSource(statusId)
            .getOrElse { return Err(it) }.body

        return mastodonApi.deleteStatus(statusId)
            .onSuccess { eventHub.dispatch(StatusDeletedEvent(statusId)) }
            .onFailure { Timber.w("Failed to delete status: %s", it) }
            .map {
                if (it.body.isEmpty()) {
                    it.copy(body = it.body.copy(text = source.text, spoilerText = source.spoilerText))
                } else {
                    it
                }
            }
    }

    suspend fun acceptFollowRequest(accountId: String): ApiResult<Relationship> {
        return mastodonApi.authorizeFollowRequest(accountId)
    }

    suspend fun rejectFollowRequest(accountId: String): ApiResult<Relationship> {
        return mastodonApi.rejectFollowRequest(accountId)
    }

    suspend fun translate(statusViewData: IStatusViewData): Result<TranslatedStatus, TranslatorError> {
        statusRepository.setTranslationState(statusViewData.pachliAccountId, statusViewData.actionableId, TranslationState.TRANSLATING)
        return translationService.translate(statusViewData)
            .onSuccess {
                translatedStatusDao.upsert(
                    it.toEntity(statusViewData.pachliAccountId, statusViewData.actionableId),
                )
                statusRepository.setTranslationState(statusViewData.pachliAccountId, statusViewData.actionableId, TranslationState.SHOW_TRANSLATION)
            }.onFailure {
                // Reset the translation state
                statusRepository.setTranslationState(statusViewData.pachliAccountId, statusViewData.actionableId, TranslationState.SHOW_ORIGINAL)
            }
    }

    suspend fun translateUndo(statusViewData: IStatusViewData) {
        statusRepository.setTranslationState(statusViewData.pachliAccountId, statusViewData.actionableId, TranslationState.SHOW_ORIGINAL)
    }

    /**
     * @param pachliAccountId
     * @param remoteKeyTimelineId The timeline's [Timeline.remoteKeyTimelineId][Timeline.remoteKeyTimelineId].
     * @return The most recent saved status ID to use in a refresh. Null if not set, or the refresh
     * should fetch the latest statuses.
     * @see saveRefreshStatusId
     */
    suspend fun getRefreshStatusId(pachliAccountId: Long, remoteKeyTimelineId: String): String? {
        return remoteKeyDao.getRefreshKey(pachliAccountId, remoteKeyTimelineId)
    }

    /**
     * Saves the ID of the status that future refreshes will try and restore
     * from.
     *
     * @param pachliAccountId
     * @param remoteKeyTimelineId The timeline's [Timeline.remoteKeyTimelineId][Timeline.remoteKeyTimelineId].
     * @param statusId Status ID to restore from. Null indicates the refresh should
     * refresh the newest statuses.
     * @see getRefreshStatusId
     */
    fun saveRefreshStatusId(pachliAccountId: Long, remoteKeyTimelineId: String, statusId: String?) = externalScope.launch {
        remoteKeyDao.upsert(
            RemoteKeyEntity(pachliAccountId, remoteKeyTimelineId, RemoteKeyKind.REFRESH, statusId),
        )
    }

    suspend fun detachQuote(pachliAccountId: Long, quoteId: String, parentId: String): Result<Status, StatusActionError.RevokeQuote> {
        return statusRepository.detachQuote(pachliAccountId, quoteId, parentId)
    }

    /**
     * Creates a draft and launches ComposeActivity to edit the draft.
     */
    // TODO: Would be safer if this took the account, not just the account ID
//    suspend fun compose(context: Context, pachliAccountId: Long, timeline: Timeline): Draft.NewDraft {
//        val pachliAccount = accountManager.getAccountById(pachliAccountId)!!
//
//        val visibility = when (timeline) {
//            Timeline.Conversations -> Status.Visibility.PRIVATE
//            else -> pachliAccount.defaultPostPrivacy
//        }
//
//        val quotePolicy = pachliAccount.defaultQuotePolicy.clampToVisibility(visibility)
//
//        val draft = Draft.NewDraft(
//            contentWarning = "",
//            content = when (timeline) {
//                is Timeline.Hashtags -> {
//                    val tag = timeline.tags.first()
//                    getString(context, R.string.title_tag_with_initial_position).format(tag)
//                }
//
//                else -> ""
//            },
//            visibility = visibility,
//            sensitive = pachliAccount.defaultMediaSensitivity,
//            language = pachliAccount.defaultPostLanguage,
//            quotePolicy = quotePolicy,
//        )
//
//        // TODO: Need to record the cursor position
//
// //        val draft = draftRepository.createDraft(pachliAccountId, draftOptions).await()
//        return draftRepository.saveDraft(pachliAccountId, draft).await()
//
// //        val composeOptions = ComposeActivityIntent.ComposeOptions(
// //            draft = savedDraft,
// //        )
// //
// //        val intent = ComposeActivityIntent(
// //            context,
// //            pachliAccountId,
// //            composeOptions,
// //        )
// //
// //        context.startActivity(intent)
//    }

    /**
     * Creates a draft replying to [status] and launches [ComposeActivity] to edit the
     * draft.
     */
//    suspend fun reply(context: Context, pachliAccountId: Long, status: Status) {
//        val pachliAccount = accountManager.getAccountById(pachliAccountId) ?: return
//
//        val actionable = status.actionableStatus
//        val account = actionable.account
//        val quotePolicy = pachliAccount.defaultQuotePolicy.clampToVisibility(actionable.visibility)
//
//        val content = run {
//            val builder = StringBuilder()
//
//            LinkedHashSet(
//                listOf(account.username) + actionable.mentions.map { it.username },
//            ).apply {
//                remove(pachliAccount.username)
//            }.forEach {
//                builder.append('@')
//                builder.append(it)
//                builder.append(' ')
//            }
//            builder.toString()
//        }
//
//        val draftOptions = DraftOptions(
//            visibility = actionable.visibility,
//            contentWarning = actionable.spoilerText,
//            content = content,
//            sensitive = actionable.sensitive || pachliAccount.defaultMediaSensitivity,
//            language = actionable.language ?: pachliAccount.defaultPostLanguage,
//            quotePolicy = quotePolicy,
//            inReplyToId = actionable.statusId,
//        )
//
//        // TODO: Need to record the cursor position
//        val draft = draftRepository.createDraft(pachliAccountId, draftOptions).await()
//
//        val composeOptions = ComposeActivityIntent.ComposeOptions(draft = draft)
//        val intent = ComposeActivityIntent(context, pachliAccountId, composeOptions)
//        context.startActivity(intent)
//    }

    fun createDraftReply(pachliAccountId: Long, status: Status): Draft.New {
        val pachliAccount = accountManager.getAccountById(pachliAccountId)!!

        return createDraftReply(pachliAccount, status)
    }

    fun createDraftReply(pachliAccountEntity: AccountEntity, status: Status): Draft.NewDraft {
        val actionable = status.actionableStatus
        val account = actionable.account
        val quotePolicy = pachliAccountEntity.defaultQuotePolicy.clampToVisibility(actionable.visibility)

        val content = run {
            val builder = StringBuilder()

            LinkedHashSet(
                listOf(account.username) + actionable.mentions.map { it.username },
            ).apply {
                remove(pachliAccountEntity.username)
            }.forEach {
                builder.append('@')
                builder.append(it)
                builder.append(' ')
            }
            builder.toString()
        }

        val draft = Draft.NewDraft(
            contentWarning = actionable.spoilerText,
            content = content,
            sensitive = actionable.sensitive || pachliAccountEntity.defaultMediaSensitivity,
            visibility = actionable.visibility,
            language = actionable.language ?: pachliAccountEntity.defaultPostLanguage,
            quotePolicy = quotePolicy,
            inReplyToId = actionable.statusId,
        )

        // TODO: Need to record the cursor position
        return draft
    }

    suspend fun createDraftMention(pachliAccountId: Long, username: String): Draft.NewDraft {
        val pachliAccount = accountManager.getAccountById(pachliAccountId)!!

        val draft = Draft.NewDraft(
            contentWarning = "",
            content = "@$username",
            visibility = pachliAccount.defaultPostPrivacy,
            sensitive = pachliAccount.defaultMediaSensitivity,
            language = pachliAccount.defaultPostLanguage,
            quotePolicy = pachliAccount.defaultQuotePolicy,
        )

        // TODO: Need to record the cursor position
        return draft

//        val draft = draftRepository.createDraft(pachliAccountId, draftOptions).await()
//        return draftRepository.saveDraft(pachliAccountId, draft).await()
    }

    /**
     * Create a draft quoting [status] and launches [ComposeActivity] to edit the
     * draft.
     */
//    suspend fun quote2(context: Context, pachliAccountId: Long, status: Status) {
//        val pachliAccount = accountManager.getAccountById(pachliAccountId) ?: return
//
//        val actionable = status.actionableStatus
//
//        // TODO: Compute the quote policy from a combination of status.visibility
//        // and the account's default quote policy.
//        val quotePolicy = pachliAccount.defaultQuotePolicy.clampToVisibility(actionable.visibility)
//
//        val draftOptions = DraftOptions(
//            visibility = actionable.visibility,
//            contentWarning = actionable.spoilerText,
//            content = "",
//            sensitive = actionable.sensitive || pachliAccount.defaultMediaSensitivity,
//            language = actionable.language ?: pachliAccount.defaultPostLanguage,
//            quotePolicy = quotePolicy,
//            inReplyToId = actionable.statusId,
//        )
//
//        // TODO: Need to record the cursor position
//        val draft = draftRepository.createDraft(pachliAccountId, draftOptions).await()
//
//        val composeOptions = ComposeActivityIntent.ComposeOptions(draft = draft)
//        val intent = ComposeActivityIntent(context, pachliAccountId, composeOptions)
//        context.startActivity(intent)
//    }

    suspend fun createDraftQuote(pachliAccountId: Long, status: Status): Draft.NewDraft {
        val pachliAccount = accountManager.getAccountById(pachliAccountId)!!

        val actionable = status.actionableStatus

        // TODO: Compute the quote policy from a combination of status.visibility
        // and the account's default quote policy.
        val quotePolicy = pachliAccount.defaultQuotePolicy.clampToVisibility(actionable.visibility)

        val draft = Draft.NewDraft(
            contentWarning = actionable.spoilerText,
            content = "",
            sensitive = actionable.sensitive || pachliAccount.defaultMediaSensitivity,
            visibility = actionable.visibility,
            language = actionable.language ?: pachliAccount.defaultPostLanguage,
            quotePolicy = quotePolicy,
            quotedStatusId = actionable.statusId,
        )

        // TODO: Need to record the cursor position
        return draft
//        return draftRepository.saveDraft(pachliAccountId, draft).await()
    }

    suspend fun createDraftFromDeletedStatus(pachliAccountId: Long, deletedStatus: app.pachli.core.model.DeletedStatus): Draft {
        val draft = Draft.NewDraft(
            contentWarning = deletedStatus.spoilerText,
            content = deletedStatus.text.orEmpty(),
            sensitive = deletedStatus.sensitive,
            visibility = deletedStatus.visibility,
            language = deletedStatus.language,
            quotePolicy = deletedStatus.quoteApproval?.asQuotePolicy() ?: AccountSource.QuotePolicy.NOBODY,
            inReplyToId = deletedStatus.inReplyToId,
            quotedStatusId = deletedStatus.quote?.statusId,
        )

        return draft
//        return draftRepository.saveDraft(pachliAccountId, draft).await()
    }
}
