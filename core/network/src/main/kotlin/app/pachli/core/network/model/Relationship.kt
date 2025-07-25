/*
 * Copyright 2017 Andrew Dawson
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

package app.pachli.core.network.model

import app.pachli.core.network.json.Guarded
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Relationship(
    val id: String,
    val following: Boolean,
    @Json(name = "followed_by") val followedBy: Boolean,
    /** True if this account is blocked. */
    val blocking: Boolean,
    /** True if this account is muted. */
    val muting: Boolean,
    @Json(name = "muting_notifications") val mutingNotifications: Boolean,
    val requested: Boolean,
    @Json(name = "showing_reblogs") val showingReblogs: Boolean,
    /**
     * Pleroma extension, same as 'notifying' on Mastodon.
     *
     * Some instances like qoto.org have a custom subscription feature where
     * 'subscribing' is a json object, so we use the custom `@Guarded` annotation
     * to ignore the field if it is not a boolean.
     */
    @Guarded
    val subscribing: Boolean? = null,
    @Json(name = "domain_blocking") val blockingDomain: Boolean,
    // nullable for backward compatibility / feature detection
    val note: String?,
    // since 3.3.0rc
    val notifying: Boolean?,
) {
    fun asModel() = app.pachli.core.model.Relationship(
        id = id,
        following = following,
        followedBy = followedBy,
        blocking = blocking,
        muting = muting,
        mutingNotifications = mutingNotifications,
        requested = requested,
        showingReblogs = showingReblogs,
        subscribing = subscribing,
        blockingDomain = blockingDomain,
        note = note,
        notifying = notifying,
    )
}

fun Iterable<Relationship>.asModel() = map { it.asModel() }
