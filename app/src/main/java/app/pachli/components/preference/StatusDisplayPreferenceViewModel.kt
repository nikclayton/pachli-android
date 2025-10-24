/*
 * Copyright (c) 2025 Pachli Association
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

package app.pachli.components.preference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pachli.core.data.model.StatusDisplayOptions
import app.pachli.core.data.model.StatusViewData
import app.pachli.core.data.repository.StatusDisplayOptionsRepository
import app.pachli.core.model.Poll
import app.pachli.core.model.PollOption
import app.pachli.core.model.Status
import app.pachli.core.model.TimelineAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class State(
    val statusDisplayOptions: StatusDisplayOptions,
    val viewData: StatusViewData,
)

@HiltViewModel
class StatusDisplayPreferenceViewModel @Inject constructor(
    statusDisplayOptionsRepository: StatusDisplayOptionsRepository,
) : ViewModel() {
    private val statusDisplayOptions = statusDisplayOptionsRepository.flow

    private val viewData = MutableStateFlow(
        StatusViewData.from(
            1,
            fakeStatus(
//                spoilerText = "Example content warning",
                content = "<p>Lorem ipsum dolor sit amet consectetur adipiscing elit. Quisque faucibus ex sapien vitae pellentesque sem placerat.</p><p>In id cursus mi pretium tellus duis convallis.</p>",

            ),
            isCollapsed = false,
            isExpanded = true,
        ),
    )

    val state = statusDisplayOptions.combine(viewData) { s, v ->
        State(
            statusDisplayOptions = s,
            viewData = v,
        )
    }

    fun setCollapsed(collapsed: Boolean) = viewModelScope.launch {
        viewData.update { it.copy(isCollapsed = collapsed) }
    }

    fun setExpanded(expanded: Boolean) = viewModelScope.launch {
        viewData.update { it.copy(isExpanded = expanded) }
    }
}

private fun fakeStatus(
    id: String = "100",
    inReplyToId: String? = null,
    inReplyToAccountId: String? = null,
    spoilerText: String = "",
    reblogged: Boolean = false,
    favourited: Boolean = true,
    bookmarked: Boolean = true,
    content: String = "Test",
    pollOptions: List<String>? = null,
    attachmentsDescriptions: List<String>? = null,
    makeFakeAccount: () -> TimelineAccount = ::fakeAccount,
) = Status(
    id = id,
    url = "https://mastodon.social/@Pachli/$id",
    account = makeFakeAccount(),
    inReplyToId = inReplyToId,
    inReplyToAccountId = inReplyToAccountId,
    reblog = null,
    content = content,
    createdAt = Date(),
    editedAt = null,
    emojis = emptyList(),
    reblogsCount = 1,
    favouritesCount = 2,
    repliesCount = 3,
    reblogged = reblogged,
    favourited = favourited,
    bookmarked = bookmarked,
    sensitive = spoilerText.isNotBlank(),
    spoilerText = spoilerText,
    visibility = Status.Visibility.PUBLIC,
    attachments = emptyList(),
    mentions = emptyList(),
    tags = emptyList(),
    application = Status.Application("Pachli", "https://pachli.app"),
    pinned = false,
    muted = false,
    poll = pollOptions?.let {
        Poll(
            id = "1234",
            expiresAt = null,
            expired = false,
            multiple = false,
            votesCount = 0,
            votersCount = 0,
            options = it.map {
                PollOption(it, 0)
            },
            voted = false,
            ownVotes = null,
        )
    },
    card = null,
    language = null,
    filtered = null,
)

fun fakeAccount() = TimelineAccount(
    id = "1",
    localUsername = "test",
    username = "test@mastodon.social",
    displayName = "Test account",
    note = "This is their bio",
    url = "https://mastodon.example/@Test",
    avatar = "https://files.mastodon.social/accounts/avatars/110/984/755/537/824/501/original/86d865e0627b9340.png",
    createdAt = null,
    bot = true,
    roles = emptyList(),
)
