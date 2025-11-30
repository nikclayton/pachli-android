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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.pachli.core.common.di.ApplicationScope
import app.pachli.core.database.dao.DraftDao
import app.pachli.core.database.model.DraftEntity
import app.pachli.core.database.model.asModel
import app.pachli.core.model.Draft
import app.pachli.core.model.Status
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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
class DraftRepository(
    @ApplicationScope private val externalScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
) {
    //    suspend fun saveStatusAsDraft(statusToSend: StatusToSend)

    fun getDrafts(pachliAccountId: Long): Flow<PagingData<Draft>> {
        return Pager(
            config = PagingConfig(pageSize = 20),
            pagingSourceFactory = { draftDao.draftsPagingSource(pachliAccountId) },
        ).flow.map { it.map { it.asModel() } }
    }

    fun deleteDraft(pachliAccountId: Long, draftId: Long) = externalScope.launch {
        draftDao.delete(pachliAccountId, draftId)
    }

    suspend fun deleteDraftAndAttachments(pachliAccountId: Long, draft: Draft) = externalScope.launch(Dispatchers.IO) {
        draft.attachments.forEach { attachment ->
            if (context.contentResolver.delete(attachment.uri, null, null) == 0) {
                Timber.e("Did not delete file %s", attachment.uri)
            }
        }
        deleteDraft(pachliAccountId, draft.id)
    }

    fun createDraft(pachliAccountId: Long) = externalScope.async {
        // TODO: Check how ComposeOptions are created, this needs the same parameters
//        val entity = DraftEntity(accountID = pachliAccountId)
        val entity = DraftEntity(
            id = 0,
            accountId = pachliAccountId,
            inReplyToId = null,
            content = "",
            contentWarning = null,
            // TODO: Should be the user's default sensitivity
            sensitive = false,
            // TODO: Should be the user's default visibility
            visibility = Status.Visibility.PUBLIC,
            attachments = emptyList(),
            poll = null,
            failedToSend = false,
            failedToSendNew = false,
            scheduledAt = null,
            // TODO: Should be the user's default language
            language = null,
            statusId = null,
            // TODO: This should pull the user's quote policy
            quotePolicy = null,
            quotedStatusId = null,
        )

        val id = draftDao.upsert(entity)
        return@async entity.copy(id = id).asModel()
    }

    /**
     * Creates a draft reply to [status].
     */
    fun createDraftReplyTo(pachliAccountId: Long, status: Status) = externalScope.async {
        val actionable = status.actionableStatus
        val account = actionable.account

        // TODO: This needs the username of our account so it can be removed from
        // the list of mentions
        val mentionedUserNames = LinkedHashSet(
            listOf(account.username) + actionable.mentions.map { it.username },
        ).apply {
            // remove(loggedInUserName)
        }

        val entity = DraftEntity(
            id = 0,
            accountId = pachliAccountId,
            inReplyToId = actionable.statusId,
            content = mentionedUserNames.joinToString(),
            contentWarning = actionable.spoilerText,
            sensitive = actionable.sensitive,
            visibility = actionable.visibility,
            attachments = emptyList(),
            poll = null,
            failedToSend = false,
            failedToSendNew = false,
            scheduledAt = null,
            language = actionable.language,
            statusId = null,
            // TODO: This should pull the user's quote policy
            quotePolicy = null,
            quotedStatusId = null,
        )

        val id = draftDao.upsert(entity)
        return@async entity.copy(id = id).asModel()
    }

    /**
     * Creates a draft quoting [status].
     */
    fun createDraftQuoting(pachliAccountId: Long, status: Status) = externalScope.async {
        val actionable = status.actionableStatus

        val entity = DraftEntity(
            id = 0,
            accountId = pachliAccountId,
            inReplyToId = null,
            content = "",
            contentWarning = actionable.spoilerText,
            sensitive = actionable.sensitive,
            visibility = actionable.visibility,
            attachments = emptyList(),
            poll = null,
            failedToSend = false,
            failedToSendNew = false,
            scheduledAt = null,
            language = actionable.language,
            statusId = null,
            // TODO: This should pull the user's quote policy
            quotePolicy = null,
            quotedStatusId = actionable.statusId,
        )

        val id = draftDao.upsert(entity)
        return@async entity.copy(id = id).asModel()
    }

    // TODO: Create a draft from an existing status (e.g., when editing, or delete+redraft)
}
