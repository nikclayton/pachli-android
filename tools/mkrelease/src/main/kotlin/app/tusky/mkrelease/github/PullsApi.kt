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

import com.squareup.moshi.Json
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PullsApi {
    @FormUrlEncoded
    @POST("/repos/{owner}/{repo}/pulls")
    suspend fun createPullRequest(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Field("title") title: String,
        @Field("head") head: String,
        @Field("base") base: String,
    )

    @GET("/repos/{owner}/{repo}/pulls/{pull_number}")
    suspend fun getPullRequest(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("pull_number") pullNumber: String
    ): PullRequestResponse

    enum class PullRequestState {
        OPEN,
        CLOSED;
    }
    
    data class PullRequestResponse(
        @Json(name = "number") val number: Int,
        @Json(name = "state", ) val state: PullRequestState
    )
}
