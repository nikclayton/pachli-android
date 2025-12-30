/* Copyright 2020 Tusky Contributors
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

package app.pachli.components.drafts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.map
import app.pachli.core.data.repository.AccountManager
import app.pachli.core.data.repository.DraftRepository
import app.pachli.core.model.Draft
import app.pachli.core.navigation.ComposeActivityIntent
import app.pachli.core.navigation.ComposeActivityIntent.ComposeOptions
import app.pachli.core.network.retrofit.MastodonApi
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class DraftViewData(
    val draft: Draft,
    val composeOptions: ComposeOptions,
    val error: DraftError? = null
)

sealed interface UiAction {
    data object Reload : UiAction
    data class Load(val pachliAccountId: Long) : UiAction
    data class DeleteDraft(val pachliAccountId: Long, val draft: Draft): UiAction
}

@HiltViewModel
class DraftsViewModel @Inject constructor(
    val api: MastodonApi,
    private val draftRepository: DraftRepository,
) : ViewModel() {
    private val pachliAccountId = MutableSharedFlow<Long>(replay = 1)

    /** Emit in to this flow to reload drafts. */
    private val reload = MutableSharedFlow<Unit>(replay = 1).apply {
        tryEmit(Unit)
    }

    val drafts = combine(pachliAccountId, reload) { pachliAccountId, _ -> pachliAccountId }
        .flatMapLatest { pachliAccountId ->
            draftRepository.getDrafts(pachliAccountId)
                .map {
                    it.map { draft ->
                        val composeOptions = ComposeOptions(
                            draft = draft,
                            kind = ComposeOptions.ComposeKind.EDIT_DRAFT,
                        )

                        if (draft.inReplyToId == null && draft.quotedStatusId == null) {
                            return@map DraftViewData(
                                draft = draft,
                                composeOptions = composeOptions,
                            )
                        }

                        draft.inReplyToId?.let { inReplyToId ->
                            getStatus(inReplyToId)
                                .map { it.body.asModel() }
                                .onSuccess {
                                    return@map DraftViewData(
                                        draft = draft,
                                        composeOptions.copy(
                                            referencingStatus = ComposeOptions.ReferencingStatus.ReplyingTo.from(it)
                                        )
                                    )
                                }
                                .onFailure {
                                    return@map
                                }
                        }
                    }
                }
        }

    /** Flow of user actions received from the UI. */
    private val uiAction = MutableSharedFlow<UiAction>()

    /** Accept UI actions. */
    val accept: (UiAction) -> Unit = { action ->
        viewModelScope.launch { uiAction.emit(action) }
    }

    init {
        viewModelScope.launch {
            uiAction.collect { action ->
                when (action) {
                    UiAction.Reload -> reload.emit(Unit)
                    is UiAction.Load -> pachliAccountId.emit(action.pachliAccountId)
                    is UiAction.DeleteDraft -> deleteDraft(action.pachliAccountId, action.draft)
                }
            }
        }
    }

    private val deletedDrafts: MutableList<Draft> = mutableListOf()

    private fun deleteDraft(pachliAccountId: Long, draft: Draft) {
        // this does not immediately delete media files to avoid unnecessary file operations
        // in case the user decides to restore the draft
        draftRepository.deleteDraft(pachliAccountId, draft.id)
        deletedDrafts.add(draft)
    }

    fun restoreDraft(pachliAccountId: Long, draft: Draft) {
        viewModelScope.launch {
            draftRepository.upsert(pachliAccountId, draft)
            deletedDrafts.remove(draft)
        }
    }

    suspend fun getStatus(statusId: String) = api.status(statusId)

    override fun onCleared() {
        viewModelScope.launch {
            deletedDrafts.forEach {
                draftRepository.deleteAttachments(it.attachments)
            }
        }
    }
}
