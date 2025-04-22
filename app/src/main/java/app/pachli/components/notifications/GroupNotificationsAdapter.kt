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

import android.text.InputFilter
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
import androidx.core.text.htmlEncode
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.pachli.R
import app.pachli.components.notifications.NotificationsViewModel.NotificationGroupViewData
import app.pachli.core.activity.emojify
import app.pachli.core.activity.loadAvatar
import app.pachli.core.common.extensions.visible
import app.pachli.core.common.string.unicodeWrap
import app.pachli.core.common.util.AbsoluteTimeFormatter
import app.pachli.core.common.util.SmartLengthInputFilter
import app.pachli.core.data.model.StatusDisplayOptions
import app.pachli.core.data.model.StatusViewData
import app.pachli.core.database.model.NotificationEntity
import app.pachli.core.designsystem.R as DR
import app.pachli.core.network.model.Emoji
import app.pachli.core.ui.LinkListener
import app.pachli.core.ui.SetStatusContent
import app.pachli.databinding.ItemGroupStatusNotificationBinding
import app.pachli.databinding.ItemUnknownNotificationBinding
import app.pachli.interfaces.AccountActionListener
import app.pachli.interfaces.StatusActionListener
import app.pachli.util.getRelativeTimeSpanString
import app.pachli.viewdata.NotificationViewData
import at.connyduck.sparkbutton.helpers.Utils
import com.bumptech.glide.Glide
import java.util.Date
import timber.log.Timber

/** How to present the group in the UI. */
enum class NotificationGroupViewKind {
    NOTIFICATION,
    UNKNOWN,
    ;

    companion object {
        fun from(kind: NotificationEntity.Type?): NotificationGroupViewKind = when (kind) {
            NotificationEntity.Type.UNKNOWN -> UNKNOWN
            NotificationEntity.Type.MENTION -> UNKNOWN
            NotificationEntity.Type.REBLOG -> NOTIFICATION
            NotificationEntity.Type.FAVOURITE -> NOTIFICATION
            NotificationEntity.Type.FOLLOW -> UNKNOWN
            NotificationEntity.Type.FOLLOW_REQUEST -> UNKNOWN
            NotificationEntity.Type.POLL -> UNKNOWN
            NotificationEntity.Type.STATUS -> NOTIFICATION
            NotificationEntity.Type.SIGN_UP -> UNKNOWN
            NotificationEntity.Type.UPDATE -> NOTIFICATION
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
    private val setStatusContent: SetStatusContent,
    private val statusActionListener: StatusActionListener<NotificationViewData>,
    private val notificationActionListener: NotificationActionListener,
    private val accountActionListener: AccountActionListener,
    var statusDisplayOptions: StatusDisplayOptions = StatusDisplayOptions(),
) : PagingDataAdapter<NotificationGroupViewData, RecyclerView.ViewHolder>(groupNotificationDiffCallback) {
    private val absoluteTimeFormatter = AbsoluteTimeFormatter()

    /** View holders in this adapter must implement this interface. */
    interface ViewHolder {
        /** Bind the data from the notification and payloads to the view. */
        fun bind(
            viewData: NotificationGroupViewData,
            payloads: List<*>?,
            statusDisplayOptions: StatusDisplayOptions,
        )
    }

    override fun getItemViewType(position: Int): Int {
        // TODO: Deal with filters: Maybe the group should be split in two at the view model?
        // Is that possible?

        return NotificationGroupViewKind.from(getItem(position)?.type).ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (NotificationGroupViewKind.entries[viewType]) {
            NotificationGroupViewKind.NOTIFICATION -> GroupStatusNotificationViewHolder(
                ItemGroupStatusNotificationBinding.inflate(inflater, parent, false),
                setStatusContent,
                statusActionListener,
                notificationActionListener,
                absoluteTimeFormatter,
            )

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
) : GroupNotificationsAdapter.ViewHolder, RecyclerView.ViewHolder(binding.root) {
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

/**
 * Displays the status the notification references, with metadata about the notification.
 */
private class GroupStatusNotificationViewHolder(
    private val binding: ItemGroupStatusNotificationBinding,
    private val setStatusContent: SetStatusContent,
    private val statusActionListener: StatusActionListener<NotificationViewData>,
    private val notificationActionListener: NotificationActionListener,
    private val absoluteTimeFormatter: AbsoluteTimeFormatter,
) : GroupNotificationsAdapter.ViewHolder, RecyclerView.ViewHolder(binding.root) {
    private val context = itemView.context

    private val avatarRadius48dp = context.resources.getDimensionPixelSize(DR.dimen.avatar_radius_48dp)
    private val avatarRadius36dp = context.resources.getDimensionPixelSize(DR.dimen.avatar_radius_36dp)
    private val avatarRadius24dp = context.resources.getDimensionPixelSize(DR.dimen.avatar_radius_24dp)

    override fun bind(viewData: NotificationGroupViewData, payloads: List<*>?, statusDisplayOptions: StatusDisplayOptions) {
//        Timber.d("viewData: $viewData")
        Timber.d("Got %d notifications in this group", viewData.notifications.size)

        // Hide null statuses. Shouldn't happen according to the spec, but some servers
        // have been seen to do this (https://github.com/tuskyapp/Tusky/issues/2252)
        val notificationViewData = viewData.notifications.firstOrNull()
        if (notificationViewData == null) {
            showNotificationContent(false)
            return
        }

        if (payloads?.isNotEmpty() == true) {
            payloads.forEach {
                // TODO: Complete this
                // if (StatusBaseViewHolder.Key.KEY_CREATED == item && viewData.notifications)
            }
            return
        }

        showNotificationContent(true)

        // Display the status content
        val account = notificationViewData.actionable.account
        val createdAt = notificationViewData.actionable.createdAt

        setDisplayName(account.name, account.emojis, statusDisplayOptions.animateEmojis)
        setUsername(account.username)
        setCreatedAt(createdAt, statusDisplayOptions.useAbsoluteTime)
        if (viewData.type == NotificationEntity.Type.STATUS ||
            viewData.type == NotificationEntity.Type.UPDATE
        ) {
            setAvatar(
                account.avatar,
                account.bot,
                statusDisplayOptions.animateAvatars,
                statusDisplayOptions.showBotOverlay,
            )
        } else {
            setAvatars(
                account.avatar,
                notificationViewData.account.avatar,
                statusDisplayOptions.animateAvatars,
            )
        }

        binding.notificationContainer.setOnClickListener {
            notificationActionListener.onViewThreadForStatus(notificationViewData.status)
        }
        binding.notificationContent.setOnClickListener {
            notificationActionListener.onViewThreadForStatus(notificationViewData.status)
        }
        binding.notificationTopText.setOnClickListener {
            notificationActionListener.onViewAccount(notificationViewData.account.id)
        }

        // Display the top text (notification type, the accounts that created the notification)
        setMessage(
            notificationViewData,
            statusActionListener,
            statusDisplayOptions,
            viewData.notifications.size,
        )
    }

    private fun showNotificationContent(show: Boolean) {
        with(binding) {
            statusDisplayName.visible(show)
            statusUsername.visible(show)
            statusMetaInfo.visible(show)
            notificationContentWarningDescription.visible(show)
            notificationContentWarningButton.visible(show)
            notificationContent.visible(show)
            notificationStatusAvatar.visible(show)
            notificationNotificationAvatar.visible(show)
        }
    }

    private fun setCreatedAt(createdAt: Date?, useAbsoluteTime: Boolean) {
        if (useAbsoluteTime) {
            binding.statusMetaInfo.text = absoluteTimeFormatter.format(createdAt, true)
        } else {
            // This is the visible timestampInfo.
            val readout: String
            /* This one is for screen-readers. Frequently, they would mispronounce timestamps like "17m"
             * as 17 meters instead of minutes. */
            val readoutAloud: CharSequence
            if (createdAt != null) {
                val then = createdAt.time
                val now = System.currentTimeMillis()
                readout = getRelativeTimeSpanString(binding.statusMetaInfo.context, then, now)
                readoutAloud = DateUtils.getRelativeTimeSpanString(
                    then,
                    now,
                    DateUtils.SECOND_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                )
            } else {
                // unknown minutes~
                readout = "?m"
                readoutAloud = "? minutes"
            }
            binding.statusMetaInfo.text = readout
            binding.statusMetaInfo.contentDescription = readoutAloud
        }
    }

    private fun setDisplayName(name: String, emojis: List<Emoji>?, animateEmojis: Boolean) {
        // TODO: Should this be unicode wrapped?
        val emojifiedName = name.emojify(emojis, binding.statusDisplayName, animateEmojis)
        binding.statusDisplayName.text = emojifiedName
    }

    private fun setUsername(name: String) {
        val usernameText = context.getString(DR.string.post_username_format, name)
        binding.statusUsername.text = usernameText
    }

    private fun setAvatar(statusAvatarUrl: String?, isBot: Boolean, animateAvatars: Boolean, showBotOverlay: Boolean) {
        binding.notificationStatusAvatar.setPaddingRelative(0, 0, 0, 0)
        loadAvatar(
            statusAvatarUrl,
            binding.notificationStatusAvatar,
            avatarRadius48dp,
            animateAvatars,
        )
        if (isBot && showBotOverlay) {
            binding.notificationNotificationAvatar.visibility = View.VISIBLE
            Glide.with(binding.notificationNotificationAvatar)
                .load(DR.drawable.bot_badge)
                .into(binding.notificationNotificationAvatar)
        } else {
            binding.notificationNotificationAvatar.visibility = View.GONE
        }
    }

    private fun setAvatars(statusAvatarUrl: String?, notificationAvatarUrl: String?, animateAvatars: Boolean) {
        val padding = Utils.dpToPx(binding.notificationStatusAvatar.context, 12)
        binding.notificationStatusAvatar.setPaddingRelative(0, 0, padding, padding)
        loadAvatar(
            statusAvatarUrl,
            binding.notificationStatusAvatar,
            avatarRadius36dp,
            animateAvatars,
        )
        binding.notificationNotificationAvatar.visibility = View.VISIBLE
        loadAvatar(
            notificationAvatarUrl,
            binding.notificationNotificationAvatar,
            avatarRadius24dp,
            animateAvatars,
        )
    }

    // TODO:
    // This is doing too much. In particular, it will get complicated when figuring
    // out the logic for the top message, since at the moment this is used for
    // notifications that can be grouped and those that can't.
    fun setMessage(
        viewData: NotificationViewData,
        listener: LinkListener,
        statusDisplayOptions: StatusDisplayOptions,
        count: Int,
    ) {
        val statusViewData = viewData.statusViewData
        val displayName = viewData.account.name.unicodeWrap()
        val type = viewData.type
        val icon = type.icon(context)

        val finalName = displayName.htmlEncode().emojify(viewData.account.emojis, binding.notificationTopText, statusDisplayOptions.animateEmojis)

        val topText = when (type) {
            NotificationEntity.Type.FAVOURITE -> {
                context.resources.getQuantityString(
                    R.plurals.notification_favourite_fmt,
                    count,
                    finalName,
                    count,
                )
            }

            NotificationEntity.Type.REBLOG -> {
                context.resources.getQuantityString(
                    R.plurals.notification_reblog_fmt,
                    count,
                    finalName,
                    count,
                )
            }

            NotificationEntity.Type.STATUS -> {
                context.getString(R.string.notification_subscription_format, finalName)
            }

            NotificationEntity.Type.UPDATE -> {
                context.getString(R.string.notification_update_format, finalName)
            }

            else -> {
                context.getString(R.string.notification_favourite_format, finalName)
            }
        }.run { HtmlCompat.fromHtml(this, FROM_HTML_MODE_LEGACY) }

        binding.notificationTopText.setCompoundDrawablesWithIntrinsicBounds(
            icon,
            null,
            null,
            null,
        )
        binding.notificationTopText.text = topText

        statusViewData ?: return

        val hasSpoiler = !TextUtils.isEmpty(statusViewData.status.spoilerText)
        binding.notificationContentWarningDescription.visibility =
            if (hasSpoiler) View.VISIBLE else View.GONE
        binding.notificationContentWarningButton.visibility =
            if (hasSpoiler) View.VISIBLE else View.GONE
        if (statusViewData.isExpanded) {
            binding.notificationContentWarningButton.setText(
                R.string.post_content_warning_show_less,
            )
        } else {
            binding.notificationContentWarningButton.setText(
                R.string.post_content_warning_show_more,
            )
        }
        binding.notificationContentWarningButton.setOnClickListener {
            if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                notificationActionListener.onExpandedChange(
                    viewData,
                    !statusViewData.isExpanded,
                )
            }
            binding.notificationContent.visibility =
                if (statusViewData.isExpanded) View.GONE else View.VISIBLE
        }
        setupContentAndSpoiler(listener, viewData, statusViewData, statusDisplayOptions)
    }

    private fun setupContentAndSpoiler(
        listener: LinkListener,
        viewData: NotificationViewData,
        statusViewData: StatusViewData,
        statusDisplayOptions: StatusDisplayOptions,
        // animateEmojis: Boolean,
    ) {
        val shouldShowContentIfSpoiler = statusViewData.isExpanded
        val hasSpoiler = !TextUtils.isEmpty(statusViewData.status.spoilerText)
        if (!shouldShowContentIfSpoiler && hasSpoiler) {
            binding.notificationContent.visibility = View.GONE
        } else {
            binding.notificationContent.visibility = View.VISIBLE
        }
        val content = statusViewData.content
        val emojis = statusViewData.actionable.emojis
        if (statusViewData.isCollapsible && (statusViewData.isExpanded || !hasSpoiler)) {
            binding.buttonToggleNotificationContent.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notificationActionListener.onNotificationContentCollapsedChange(
                        !statusViewData.isCollapsed,
                        viewData,
                    )
                }
            }
            binding.buttonToggleNotificationContent.visibility = View.VISIBLE
            if (statusViewData.isCollapsed) {
                binding.buttonToggleNotificationContent.setText(
                    R.string.post_content_warning_show_more,
                )
                binding.notificationContent.filters = COLLAPSE_INPUT_FILTER
            } else {
                binding.buttonToggleNotificationContent.setText(
                    R.string.post_content_warning_show_less,
                )
                binding.notificationContent.filters = NO_INPUT_FILTER
            }
        } else {
            binding.buttonToggleNotificationContent.visibility = View.GONE
            binding.notificationContent.filters = NO_INPUT_FILTER
        }
        setStatusContent(
            binding.notificationContent,
            content,
            statusDisplayOptions,
            emojis,
            statusViewData.actionable.mentions,
            statusViewData.actionable.tags,
            listener,
        )

        val emojifiedContentWarning: CharSequence = statusViewData.spoilerText.emojify(
            statusViewData.actionable.emojis,
            binding.notificationContentWarningDescription,
            statusDisplayOptions.animateEmojis,
        )
        binding.notificationContentWarningDescription.text = emojifiedContentWarning
    }

    companion object {
        private val COLLAPSE_INPUT_FILTER = arrayOf<InputFilter>(SmartLengthInputFilter)
        private val NO_INPUT_FILTER = arrayOfNulls<InputFilter>(0)
    }
}
