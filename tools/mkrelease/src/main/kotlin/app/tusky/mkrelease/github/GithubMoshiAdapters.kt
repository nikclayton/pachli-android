/*
 * Copyright 2023 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.tusky.mkrelease.github

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.ToJson

object GithubMoshiAdapters {
    @ToJson
    fun pullRequestStateToJson(state: PullsApi.PullRequestState) = state.toString().lowercase()

    @FromJson
    fun pullRequestStateFromJson(state: String) = when (state.uppercase()) {
        "OPEN" -> PullsApi.PullRequestState.OPEN
        "CLOSED" -> PullsApi.PullRequestState.CLOSED
        else -> throw JsonDataException("unknown PullRequestState: $state")
    }

    @ToJson
    fun makeLatestToJson(makeLatest: ReleasesApi.MakeLatest) = makeLatest.toString().lowercase()
}
