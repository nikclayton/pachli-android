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

package app.pachli.core.model

import android.net.Uri
import android.os.Parcelable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.Date
import kotlinx.parcelize.Parcelize

@Parcelize
sealed interface Draft : Parcelable {
    val id: Long
    val contentWarning: String?
    val content: String?
    val sensitive: Boolean
    val visibility: Status.Visibility

    // val attachments: List<DraftAttachment>
    val poll: NewPoll?
    val failedToSend: Boolean
    val failedToSendNew: Boolean
    val scheduledAt: Date?
    val language: String?
    val quotePolicy: AccountSource.QuotePolicy?

    val inReplyToId: String?
    val quotedStatusId: String?

    val statusId: String?

    @Parcelize
    sealed interface New : Draft {
        val attachments: List<DraftAttachment>
    }

    /** New draft */
    data class NewDraft(
        override val id: Long = 0,
        override val contentWarning: String?,
        override val content: String?,
        override val sensitive: Boolean,
        override val visibility: Status.Visibility,
        override val attachments: List<DraftAttachment> = emptyList(),
        override val poll: NewPoll? = null,
        override val failedToSend: Boolean = false,
        override val failedToSendNew: Boolean = false,
        override val scheduledAt: Date? = null,
        override val language: String?,
        override val quotePolicy: AccountSource.QuotePolicy?,
        override val inReplyToId: String? = null,
        override val quotedStatusId: String? = null,
        override val statusId: String? = null,
    ) : New

    /** Draft that is editing an existing status. */
    @Parcelize
    sealed interface Edit : Draft {
        val attachments: List<Attachment>
        override val statusId: String
    }

    /** Draft that edits an existing status */
    data class NewEdit(
        override val id: Long = 0,
        override val contentWarning: String?,
        override val content: String?,
        override val sensitive: Boolean,
        override val visibility: Status.Visibility,
        override val attachments: List<Attachment> = emptyList(),
        override val poll: NewPoll? = null,
        override val failedToSend: Boolean = false,
        override val failedToSendNew: Boolean = false,
        override val scheduledAt: Date? = null,
        override val language: String?,
        override val quotePolicy: AccountSource.QuotePolicy?,
        override val statusId: String,
        override val inReplyToId: String? = null,
        override val quotedStatusId: String? = null,
    ) : Edit

    companion object
}

@Parcelize
@JsonClass(generateAdapter = true)
data class DraftAttachment(
    @Json(name = "uriString") val uri: Uri,
    val description: String?,
    val focus: Attachment.Focus?,
    val type: Type,
) : Parcelable {
    enum class Type {
        IMAGE,
        VIDEO,
        AUDIO,
    }
}
