/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.shared

import android.app.PendingIntent
import android.graphics.drawable.Icon
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.stack.PriorityBucket

/**
 * Model for a top-level "entry" in the notification list, either an
 * [individual notification][ActiveNotificationModel], or a [group][ActiveNotificationGroupModel].
 */
sealed class ActiveNotificationEntryModel

/**
 * Model for an individual notification in the notification list. These can appear as either an
 * individual top-level notification, or as a child or summary of a [ActiveNotificationGroupModel].
 */
data class ActiveNotificationModel(
    val key: String,
    /** Notification group key associated with this entry. */
    val groupKey: String?,
    /** When this notification was posted. */
    val whenTime: Long,
    /** Is this entry in the ambient / minimized section (lowest priority)? */
    val isAmbient: Boolean,
    /**
     * Is this entry dismissed? This is `true` when the user has dismissed the notification in the
     * UI, but `NotificationManager` has not yet signalled to us that it has received the dismissal.
     */
    val isRowDismissed: Boolean,
    /** Is this entry in the silent section? */
    val isSilent: Boolean,
    /**
     * Does this entry represent a conversation, the last message of which was from a remote input
     * reply?
     */
    val isLastMessageFromReply: Boolean,
    /** Is this entry suppressed from appearing in the status bar as an icon? */
    val isSuppressedFromStatusBar: Boolean,
    /** Is this entry actively pulsing on AOD or bypassed-keyguard? */
    val isPulsing: Boolean,
    /** Icon to display on AOD. */
    val aodIcon: Icon?,
    /** Icon to display in the notification shelf. */
    val shelfIcon: Icon?,
    /** Icon to display in the status bar. */
    val statusBarIcon: Icon?,
    /** Icon to display in the status bar chip. */
    val statusBarChipIconView: StatusBarIconView?,
    /** The notifying app's [packageName]'s uid. */
    val uid: Int,
    /** The notifying app's packageName. */
    val packageName: String,
    /** The intent to execute if UI related to this notification is clicked. */
    val contentIntent: PendingIntent?,
    /** A small per-notification ID, used for statsd logging. */
    val instanceId: Int?,
    /** If this notification is the group summary for a group of notifications. */
    val isGroupSummary: Boolean,
    /** Indicates in which section the notification is displayed in. @see [PriorityBucket]. */
    @PriorityBucket val bucket: Int,
    /** The call type set on the notification. */
    val callType: CallType,
) : ActiveNotificationEntryModel()

/** Model for a group of notifications. */
data class ActiveNotificationGroupModel(
    val key: String,
    val summary: ActiveNotificationModel,
    val children: List<ActiveNotificationModel>,
) : ActiveNotificationEntryModel()

/** Specifies the call type set on the notification. For most notifications, will be [None]. */
enum class CallType {
    /** This notification isn't a call-type notification. */
    None,
    /** See [android.app.Notification.CallStyle.CALL_TYPE_INCOMING]. */
    Incoming,
    /** See [android.app.Notification.CallStyle.CALL_TYPE_ONGOING]. */
    Ongoing,
    /** See [android.app.Notification.CallStyle.CALL_TYPE_SCREENING]. */
    Screening,
    /** See [android.app.Notification.CallStyle.CALL_TYPE_UNKNOWN]. */
    Unknown,
}
