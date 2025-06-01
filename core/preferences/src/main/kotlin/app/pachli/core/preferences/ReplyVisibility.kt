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

package app.pachli.core.preferences

enum class ReplyVisibility(
    override val displayResource: Int,
    override val value: String? = null,
) : PreferenceEnum {
    /** Use the account's default status visibility. */
    SAME_AS_ACCOUNT_POST_VISIBILITY(R.string.pref_reply_visibility_same_as_account),

    /** Use the same visibility as the status being replied to (default). */
    SAME_AS_PARENT_POST_VISIBILITY(R.string.pref_reply_visibility_same_as_parent),

    /** Always reply in public. */
    PUBLIC(R.string.visibility_public),

    /** Always reply as unlisted. */
    UNLISTED(R.string.visibility_unlisted),

    /** Always reply followers-only. */
    FOLLOWERS_ONLY(R.string.visibility_private),

    /** Always reply by DM. */
    DIRECT(R.string.visibility_direct),
}
