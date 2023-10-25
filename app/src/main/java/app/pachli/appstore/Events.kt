package app.pachli.appstore

import app.pachli.TabData
import app.pachli.entity.Account
import app.pachli.entity.Filter
import app.pachli.entity.Poll
import app.pachli.entity.Status
import app.pachli.network.StatusId

data class FavoriteEvent(val statusId: StatusId, val favourite: Boolean) : Event
data class ReblogEvent(val statusId: StatusId, val reblog: Boolean) : Event
data class BookmarkEvent(val statusId: StatusId, val bookmark: Boolean) : Event
data class MuteConversationEvent(val statusId: StatusId, val mute: Boolean) : Event
data class UnfollowEvent(val accountId: String) : Event
data class BlockEvent(val accountId: String) : Event
data class MuteEvent(val accountId: String) : Event
data class StatusDeletedEvent(val statusId: StatusId) : Event

/** A status the user wrote was successfully sent */
// TODO: Rename, calling it "Composed" does not imply anything about the sent state
data class StatusComposedEvent(val status: Status) : Event
data class StatusScheduledEvent(val status: Status) : Event
data class StatusEditedEvent(val originalId: StatusId, val status: Status) : Event
data class ProfileEditedEvent(val newProfileData: Account) : Event
data class FilterChangedEvent(val filterKind: Filter.Kind) : Event
data class MainTabsChangedEvent(val newTabs: List<TabData>) : Event
data class PollVoteEvent(val statusId: StatusId, val poll: Poll) : Event
data class DomainMuteEvent(val instance: String) : Event
data class AnnouncementReadEvent(val announcementId: String) : Event
data class PinEvent(val statusId: StatusId, val pinned: Boolean) : Event
