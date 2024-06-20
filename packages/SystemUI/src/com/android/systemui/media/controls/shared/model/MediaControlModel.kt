/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media.controls.shared.model

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Process
import com.android.internal.logging.InstanceId

data class MediaControlModel(
    val uid: Int = Process.INVALID_UID,
    val packageName: String,
    val instanceId: InstanceId,
    val token: MediaSession.Token?,
    val appIcon: Icon?,
    val clickIntent: PendingIntent?,
    val appName: String?,
    val songName: CharSequence?,
    val artistName: CharSequence?,
    val showExplicit: Boolean,
    val artwork: Icon?,
    val deviceData: MediaDeviceData?,
    /** [MediaButton] contains [MediaAction] objects which represent specific buttons in the UI */
    val semanticActionButtons: MediaButton?,
    val notificationActionButtons: List<MediaAction>,
    /**
     * List of [notificationActionButtons] indices shown on smaller version of media player. Check
     * [Notification.MediaStyle.setShowActionsInCompactView].
     */
    val actionsToShowInCollapsed: List<Int>,
    val isDismissible: Boolean,
    /** Whether player is in resumption state. */
    val isResume: Boolean,
    /** Track seek bar progress (0 - 1) when [isResume] is true. */
    val resumeProgress: Double?,
)
