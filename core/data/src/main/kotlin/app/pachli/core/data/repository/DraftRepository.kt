/*
 * Copyright 2025 Pachli Association
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

package app.pachli.core.data.repository

import android.content.Context
import androidx.core.content.ContextCompat.getString
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.pachli.core.common.di.ApplicationScope
import app.pachli.core.data.R
import app.pachli.core.database.dao.DraftDao
import app.pachli.core.database.model.AccountEntity
import app.pachli.core.database.model.DraftEntity
import app.pachli.core.database.model.asModel
import app.pachli.core.model.AccountSource
import app.pachli.core.model.Draft
import app.pachli.core.model.DraftAttachment
import app.pachli.core.model.ScheduledStatus
import app.pachli.core.model.Status
import app.pachli.core.model.Timeline
import app.pachli.core.model.asQuotePolicy
import app.pachli.core.network.model.StatusSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/*

Drafts probably have a state, which controls whether or not they can be edited.

States

- DRAFTING -- user is editing the draft, it has not been sent
- SENDING -- the draft is being sent, the user can't edit it
- FAILED+error -- draft couldn't be sent

 */

@Singleton
class DraftRepository @Inject constructor(
    @ApplicationScope private val externalScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
) {
    //    suspend fun saveStatusAsDraft(statusToSend: StatusToSend)

    fun getDrafts(pachliAccountId: Long): Flow<PagingData<Draft.NewDraft>> {
        return Pager(
            config = PagingConfig(pageSize = 20),
            pagingSourceFactory = { draftDao.draftsPagingSource(pachliAccountId) },
        ).flow.map { it.map { it.asModel() as Draft.NewDraft } }
    }

    fun deleteDraft(pachliAccountId: Long, draftId: Long) = externalScope.launch {
        draftDao.delete(pachliAccountId, draftId)
    }

    suspend fun deleteDraftAndAttachments(pachliAccountId: Long, draftId: Long) = externalScope.launch {
        val draft = draftDao.find(draftId) ?: return@launch
        draft.attachments.forEach { attachment ->
            if (context.contentResolver.delete(attachment.uri, null, null) == 0) {
                Timber.e("Did not delete file %s", attachment.uri)
            }
        }
        deleteDraft(pachliAccountId, draft.id)
    }

    suspend fun deleteDraftAndAttachments(pachliAccountId: Long, draft: Draft.New) = externalScope.launch(Dispatchers.IO) {
        draft.attachments.forEach { attachment ->
            if (context.contentResolver.delete(attachment.uri, null, null) == 0) {
                Timber.e("Did not delete file %s", attachment.uri)
            }
        }
        deleteDraft(pachliAccountId, draft.id)
    }

    suspend fun deleteAttachments(attachments: List<DraftAttachment>) {
        attachments.forEach { attachment ->
            if (context.contentResolver.delete(attachment.uri, null, null) == 0) {
                Timber.e("Did not delete file %s", attachment.uri)
            }
        }
    }

//    fun createDraft(pachliAccountId: Long, draftOptions: DraftOptions) = externalScope.async {
//        // TODO: Check how ComposeOptions are created, this needs the same parameters
// //        val entity = DraftEntity(accountID = pachliAccountId)
//        val entity = DraftEntity(
//            id = 0,
//            accountId = pachliAccountId,
//            inReplyToId = null,
//            content = draftOptions.content,
//            contentWarning = draftOptions.contentWarning,
//            sensitive = draftOptions.sensitive,
//            visibility = draftOptions.visibility,
//            attachments = emptyList(),
//            poll = null,
//            failedToSend = false,
//            failedToSendNew = false,
//            scheduledAt = null,
//            language = draftOptions.language,
//            statusId = null,
//            quotePolicy = draftOptions.quotePolicy,
//            quotedStatusId = null,
//        )
//
//        val id = draftDao.upsert(entity)
//        return@async entity.copy(id = id).asModel()
//    }

    fun <T : Draft> saveDraft(pachliAccountId: Long, draft: T): Deferred<T> = externalScope.async {
        val entity = draft.asEntity(pachliAccountId)
        val id = draftDao.upsert(entity)
        return@async entity.copy(id = id).asModel() as T
    }

    fun updateFailureState(pachliAccountId: Long, draftId: Long, failedToSend: Boolean, failedToSendNew: Boolean) = externalScope.async {
        draftDao.updateFailureState(draftId, failedToSend, failedToSendNew)
    }
}

fun Draft.asEntity(pachliAccountId: Long) = DraftEntity(
    accountId = pachliAccountId,
    id = id,
    contentWarning = contentWarning,
    content = content,
    inReplyToId = inReplyToId,
    sensitive = sensitive,
    visibility = visibility,
    attachments = (this as? Draft.NewDraft)?.attachments.orEmpty(),
    remoteAttachments = (this as? Draft.Edit)?.attachments.orEmpty(),
    poll = poll,
    failedToSend = failedToSend,
    failedToSendNew = failedToSendNew,
    scheduledAt = scheduledAt,
    language = language,
    statusId = (this as? Draft.Edit)?.statusId,
    quotePolicy = quotePolicy,
    quotedStatusId = quotedStatusId,
)

fun Status.asDraft(source: StatusSource): Draft.Edit {
    val actionable = this.actionableStatus

    return Draft.NewEdit(
        contentWarning = source.spoilerText,
        content = source.text,
        attachments = actionable.attachments,
        poll = actionable.poll?.toNewPoll(createdAt),
        sensitive = actionable.sensitive,
        visibility = actionable.visibility,
        language = actionable.language,
        quotePolicy = actionable.quoteApproval.asQuotePolicy(),
        statusId = actionable.statusId,
        inReplyToId = actionable.inReplyToId,
        quotedStatusId = actionable.quote?.statusId,
    )
}

fun ScheduledStatus.asDraft(): Draft.Edit {
    return Draft.NewEdit(
        contentWarning = params.spoilerText,
        content = params.text,
        attachments = mediaAttachments,
        poll = params.poll,
        sensitive = params.sensitive == true,
        visibility = params.visibility,
        language = params.language,
        quotePolicy = params.quotePolicy,
        scheduledAt = scheduledAt,
        statusId = id,
        inReplyToId = params.inReplyToId,
        quotedStatusId = params.quotedStatusId,
    )
}

data class DraftOptions(
    val visibility: Status.Visibility,
    val contentWarning: String,
    val content: String,
    val sensitive: Boolean,
    val language: String,
    val quotePolicy: AccountSource.QuotePolicy,
    val inReplyToId: String? = null,
    val quotedStatusId: String? = null,
)

fun Draft.Companion.createDraft(context: Context, pachliAccountEntity: AccountEntity, timeline: Timeline): Draft.NewDraft {
    val visibility = when (timeline) {
        Timeline.Conversations -> Status.Visibility.DIRECT
        else -> pachliAccountEntity.defaultPostPrivacy
    }

    val quotePolicy = pachliAccountEntity.defaultQuotePolicy.clampToVisibility(visibility)

    val draft = Draft.NewDraft(
        contentWarning = "",
        content = when (timeline) {
            is Timeline.Hashtags -> {
                val tag = timeline.tags.first()
                getString(context, R.string.title_tag_with_initial_position).format(tag)
            }

            else -> ""
        },
        visibility = visibility,
        sensitive = pachliAccountEntity.defaultMediaSensitivity,
        language = pachliAccountEntity.defaultPostLanguage,
        quotePolicy = quotePolicy,
    )

    // TODO: Need to record the cursor position

    return draft
}

fun Draft.Companion.createDraftReply(pachliAccountEntity: AccountEntity, status: Status): Draft.NewDraft {
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

fun Draft.Companion.createDraftQuote(pachliAccountEntity: AccountEntity, status: Status): Draft.NewDraft {
    val actionable = status.actionableStatus

    val quotePolicy = pachliAccountEntity.defaultQuotePolicy.clampToVisibility(actionable.visibility)

    val draft = Draft.NewDraft(
        contentWarning = actionable.spoilerText,
        content = "",
        sensitive = actionable.sensitive || pachliAccountEntity.defaultMediaSensitivity,
        visibility = actionable.visibility,
        language = actionable.language ?: pachliAccountEntity.defaultPostLanguage,
        quotePolicy = quotePolicy,
        quotedStatusId = actionable.statusId,
    )

    // TODO: Need to record the cursor position
    return draft
}

fun Draft.Companion.createDraftMention(context: Context, pachliAccountEntity: AccountEntity, timeline: Timeline, username: String): Draft.NewDraft {
    return Draft.createDraft(context, pachliAccountEntity, timeline).copy(
        content = "@$username ",
    )
}
