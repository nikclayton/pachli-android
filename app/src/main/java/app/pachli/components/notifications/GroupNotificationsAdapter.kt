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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import app.pachli.components.notifications.NotificationsViewModel.GroupNotificationViewData
import app.pachli.core.ui.BindingHolder
import app.pachli.databinding.ItemUnknownNotificationBinding

class GroupNotificationsAdapter() : PagingDataAdapter<GroupNotificationViewData, BindingHolder<ItemUnknownNotificationBinding>>(groupNotificationDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<ItemUnknownNotificationBinding> {
        val inflater = LayoutInflater.from(parent.context)
        return BindingHolder(ItemUnknownNotificationBinding.inflate(inflater))
    }

    override fun onBindViewHolder(holder: BindingHolder<ItemUnknownNotificationBinding>, position: Int) {
        val item = getItem(position) ?: return

        val t = "${item.groupKey}: ${item.type}: ${item.notifications.size}"
        holder.binding.root.text = t
    }
}

private val groupNotificationDiffCallback =
    object : DiffUtil.ItemCallback<GroupNotificationViewData>() {
        override fun areItemsTheSame(oldItem: GroupNotificationViewData, newItem: GroupNotificationViewData) = oldItem.groupKey == newItem.groupKey

        override fun areContentsTheSame(oldItem: GroupNotificationViewData, newItem: GroupNotificationViewData) = oldItem == newItem
    }
