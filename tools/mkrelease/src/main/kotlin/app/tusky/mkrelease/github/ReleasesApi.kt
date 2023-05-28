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
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

interface ReleasesApi {
    data class Release(
        @Json(name = "html_url") val htmlUrl: String,
        @Json(name = "upload_url") val uploadUrl: String,
        @Json(name = "id") val id: Int,
        @Json(name = "tag_name") val tagName: String
    )

    @GET("/repos/{owner}/{repo}/releases")
    suspend fun listReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): List<Release>

    @GET("/repos/{owner}/{repo}/releases/tags/{tag}")
    suspend fun getReleaseByTagName(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tag") tag: String
    ): Release

    @POST("/repos/{owner}/{repo}/releases")
    suspend fun createRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreateReleaseRequest
//        @Field("tag_name") tagName: String,
//        @Field("name") name: String,
//        @Field("body") body: String,
//        @Field("draft") draft: Boolean,
//        @Field("prerelease") preRelease: Boolean,
//        @Field("make_latest") makeLatest: Boolean
    ): Release

    enum class MakeLatest {
        TRUE,
        FALSE,
        LEGACY
    }

    data class CreateReleaseRequest(
        @Json(name = "tag_name") val tagName: String,
        @Json(name = "name") val name: String? = null,
        @Json(name = "body") val body: String? = null,
        @Json(name = "draft") val draft: Boolean? = null,
        @Json(name = "prerelease") val preRelease: Boolean? = null,
        @Json(name = "make_latest") val makeLatest: MakeLatest? = null
    )

    @PATCH("/repos/{owner}/{repo}/releases/{release_id}")
    suspend fun updateRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("release_id") releaseId: Int,
        @Body request: UpdateReleaseRequest
    ): Release


    // Like CreateReleaseRequest, but all fields are optional
    data class UpdateReleaseRequest(
        @Json(name = "tag_name") val tagName: String? = null,
        @Json(name = "name") val name: String? = null,
        @Json(name = "body") val body: String? = null,
        @Json(name = "draft") val draft: Boolean? = null,
        @Json(name = "prerelease") val preRelease: Boolean? = null,
        @Json(name = "make_latest") val makeLatest: MakeLatest? = null
    )

//    @Multipart
    @POST
    suspend fun uploadReleaseAsset(
        @Url url: String,
        @Body file: RequestBody
    ): ReleaseAsset

    data class ReleaseAsset(
        @Json(name = "url") val url: String,
        @Json(name = "browser_download_url") val browserDownloadUrl: String,
        @Json(name = "name") val name: String,
        @Json(name = "state") val state: String
    )
}
