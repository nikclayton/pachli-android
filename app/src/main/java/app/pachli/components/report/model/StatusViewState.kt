/* Copyright 2019 Joel Pyska
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
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package app.pachli.components.report.model

import app.pachli.network.StatusId

class StatusViewState {
    private val mediaShownState = HashMap<StatusId, Boolean>()
    private val contentShownState = HashMap<StatusId, Boolean>()
    private val longContentCollapsedState = HashMap<StatusId, Boolean>()

    fun isMediaShow(id: StatusId, isSensitive: Boolean): Boolean = isStateEnabled(mediaShownState, id, !isSensitive)
    fun setMediaShow(id: StatusId, isShow: Boolean) = setStateEnabled(mediaShownState, id, isShow)

    fun isContentShow(id: StatusId, isSensitive: Boolean): Boolean = isStateEnabled(contentShownState, id, !isSensitive)
    fun setContentShow(id: StatusId, isShow: Boolean) = setStateEnabled(contentShownState, id, isShow)

    fun isCollapsed(id: StatusId, isCollapsed: Boolean): Boolean = isStateEnabled(longContentCollapsedState, id, isCollapsed)
    fun setCollapsed(id: StatusId, isCollapsed: Boolean) = setStateEnabled(longContentCollapsedState, id, isCollapsed)

    private fun isStateEnabled(map: Map<StatusId, Boolean>, id: StatusId, def: Boolean): Boolean = map[id]
        ?: def

    private fun setStateEnabled(map: HashMap<StatusId, Boolean>, id: StatusId, state: Boolean) = map.put(id, state)
}
