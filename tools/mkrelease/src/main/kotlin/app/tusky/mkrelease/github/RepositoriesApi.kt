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
import retrofit2.http.GET
import retrofit2.http.Path

interface RepositoriesApi {
    @GET("/repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Repo

    data class Repo(
        @Json(name = "id") val id: Int,
        @Json(name = "name") val name: String,
        @Json(name = "full_name") val fullName: String,
        @Json(name = "html_url") val htmlUrl: String,
        @Json(name = "fork") val fork: Boolean,
        @Json(name = "parent") val parent: Repo?
    )
}
