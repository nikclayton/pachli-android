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

package app.pachli.components.notifications

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.pachli.core.database.dao.NotificationDao
import app.pachli.core.database.model.NotificationData
import javax.inject.Inject

class GroupedNotificationsPagingSource @Inject constructor(
    val notificationDao: NotificationDao,
) : PagingSource<Int, Map<String, NotificationData>>() {
    override fun getRefreshKey(state: PagingState<Int, Map<String, NotificationData>>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)

            anchorPage?.prevKey?.plus(state.config.pageSize) ?: anchorPage?.nextKey?.minus(state.config.pageSize)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Map<String, NotificationData>> {
        TODO("Not yet implemented")
    }
}
