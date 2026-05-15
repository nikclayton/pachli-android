/*
 * Copyright (c) 2026 Pachli Association
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

package app.pachli.core.data.extensions

import app.pachli.core.database.model.HashtagEntity
import app.pachli.core.network.model.HashTag
import app.pachli.core.network.model.asModel

fun HashTag.asEntity(pachliAccountId: Long) = HashtagEntity(
    pachliAccountId = pachliAccountId,
    name = name,
    url = url,
    history = history.asModel(),
    following = following == true,
)

fun Iterable<HashTag>.asEntity(pachliAccountId: Long) = map { it.asEntity(pachliAccountId) }
