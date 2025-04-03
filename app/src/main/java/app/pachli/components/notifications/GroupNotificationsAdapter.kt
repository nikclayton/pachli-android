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
import androidx.recyclerview.widget.RecyclerView
import app.pachli.R
import app.pachli.components.notifications.NotificationsViewModel.NotificationGroupViewData
import app.pachli.core.data.model.StatusDisplayOptions
import app.pachli.core.database.model.NotificationEntity
import app.pachli.databinding.ItemUnknownNotificationBinding

/** How to present the group in the UI. */
enum class NotificationGroupViewKind {
    GROUP_REBLOG,
    UNKNOWN,
    ;

    companion object {
        fun from(kind: NotificationEntity.Type?): NotificationGroupViewKind = when (kind) {
            NotificationEntity.Type.UNKNOWN -> UNKNOWN
            NotificationEntity.Type.MENTION -> UNKNOWN
            NotificationEntity.Type.REBLOG -> GROUP_REBLOG
            NotificationEntity.Type.FAVOURITE -> UNKNOWN
            NotificationEntity.Type.FOLLOW -> UNKNOWN
            NotificationEntity.Type.FOLLOW_REQUEST -> UNKNOWN
            NotificationEntity.Type.POLL -> UNKNOWN
            NotificationEntity.Type.STATUS -> UNKNOWN
            NotificationEntity.Type.SIGN_UP -> UNKNOWN
            NotificationEntity.Type.UPDATE -> UNKNOWN
            NotificationEntity.Type.REPORT -> UNKNOWN
            NotificationEntity.Type.SEVERED_RELATIONSHIPS -> UNKNOWN
            null -> UNKNOWN
        }
    }
}

/** View holders in this adapter must implement this interface. */
private interface ViewHolder {
    /** Bind the data from the notification and payloads to the view. */
    fun bind(
        viewData: NotificationGroupViewData,
        payloads: List<*>?,
        statusDisplayOptions: StatusDisplayOptions,
    )
}

class GroupNotificationsAdapter(
    var statusDisplayOptions: StatusDisplayOptions = StatusDisplayOptions(),

) : PagingDataAdapter<NotificationGroupViewData, RecyclerView.ViewHolder>(groupNotificationDiffCallback) {

    override fun getItemViewType(position: Int): Int {
        // TODO: Deal with filters: Maybe the group should be split in two at the view model?
        // Is that possible?

        return NotificationGroupViewKind.from(getItem(position)?.type).ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (NotificationGroupViewKind.entries[viewType]) {
            NotificationGroupViewKind.GROUP_REBLOG -> TODO()
            NotificationGroupViewKind.UNKNOWN -> FallbackNotificationViewHolder(
                ItemUnknownNotificationBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindViewHolder(holder, position, null)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        bindViewHolder(holder, position, payloads)
    }

    private fun bindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<*>?) {
        getItem(position)?.let {
            (holder as ViewHolder).bind(it, payloads, statusDisplayOptions)
        }
    }
}

private val groupNotificationDiffCallback =
    object : DiffUtil.ItemCallback<NotificationGroupViewData>() {
        override fun areItemsTheSame(oldItem: NotificationGroupViewData, newItem: NotificationGroupViewData) = oldItem.groupKey == newItem.groupKey

        override fun areContentsTheSame(oldItem: NotificationGroupViewData, newItem: NotificationGroupViewData) = oldItem == newItem
    }

/**
 * Notification view holder to use if no other type is appropriate. Should never normally
 * be used, but is useful when migrating code.
 */
private class FallbackNotificationViewHolder(
    val binding: ItemUnknownNotificationBinding,
) : ViewHolder, RecyclerView.ViewHolder(binding.root) {
    override fun bind(
        viewData: NotificationGroupViewData,
        payloads: List<*>?,
        statusDisplayOptions: StatusDisplayOptions,
    ) {
        val t = "${viewData.groupKey}: ${viewData.type}: ${viewData.notifications.size}"
        binding.text1.text = t

        binding.text1.text = binding.root.context.getString(R.string.notification_unknown)
    }
}
