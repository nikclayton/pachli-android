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

package app.pachli.core.data.repository.notifications

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.pachli.core.database.dao.NotificationDao
import app.pachli.core.database.model.NotificationGroup
import javax.inject.Inject

// This exists because the query (loadGroupedNotifications) returns a Map between the group key
// the list of notifications in that group.
//
// I.e., it's item keyed, not row keyed, and Room's Paging implementation wants things to be
// row keyed.

class GroupedNotificationsPagingSource @Inject constructor(
    private val pachliAccountId: Long,
    private val notificationDao: NotificationDao,
) : PagingSource<String, NotificationGroup>() {
    override fun getRefreshKey(state: PagingState<String, NotificationGroup>): String? {
//        return state.anchorPosition?.let { anchorPosition ->
//            val anchorPage = state.closestPageToPosition(anchorPosition)
//
//            anchorPage?.prevKey?.plus(state.config.pageSize) ?: anchorPage?.nextKey?.minus(state.config.pageSize)
//        }
        return null
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, NotificationGroup> {
        val notificationGroups = notificationDao.loadGroupedNotifications(pachliAccountId)

//        Timber.d("notificationGroups:")
//        Timber.d("  size: ${notificationGroups.size}")
//        Timber.d("  keys: ${notificationGroups.keys}")

        val groups = notificationGroups.map { entry ->
            NotificationGroup(
                groupKey = entry.key,
                type = entry.value.first().notification.type,
                notifications = entry.value,
            )
        }

//        Timber.d("Returning groups:")
//        groups.forEach {
//            Timber.d("  groupKey: ${it.groupKey}")
//            Timber.d("  type: ${it.type}")
//            Timber.d("  notifs: ${it.notifications.size}")
//        }

        return LoadResult.Page(
            data = groups,
            prevKey = null,
            nextKey = null,
        )
    }
}
