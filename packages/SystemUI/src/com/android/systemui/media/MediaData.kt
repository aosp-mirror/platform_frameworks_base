/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.media

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.session.MediaSession

/** State of a media view. */
data class MediaData(
    val userId: Int,
    val initialized: Boolean = false,
    val backgroundColor: Int,
    /**
     * App name that will be displayed on the player.
     */
    val app: String?,
    /**
     * Icon shown on player, close to app name.
     */
    val appIcon: Drawable?,
    /**
     * Artist name.
     */
    val artist: CharSequence?,
    /**
     * Song name.
     */
    val song: CharSequence?,
    /**
     * Album artwork.
     */
    val artwork: Icon?,
    /**
     * List of actions that can be performed on the player: prev, next, play, pause, etc.
     */
    val actions: List<MediaAction>,
    /**
     * Same as above, but shown on smaller versions of the player, like in QQS or keyguard.
     */
    val actionsToShowInCompact: List<Int>,
    /**
     * Package name of the app that's posting the media.
     */
    val packageName: String,
    /**
     * Unique media session identifier.
     */
    val token: MediaSession.Token?,
    /**
     * Action to perform when the player is tapped.
     * This is unrelated to {@link #actions}.
     */
    val clickIntent: PendingIntent?,
    /**
     * Where the media is playing: phone, headphones, ear buds, remote session.
     */
    val device: MediaDeviceData?,
    /**
     * When active, a player will be displayed on keyguard and quick-quick settings.
     * This is unrelated to the stream being playing or not, a player will not be active if
     * timed out, or in resumption mode.
     */
    var active: Boolean,
    /**
     * Action that should be performed to restart a non active session.
     */
    var resumeAction: Runnable?,
    /**
     * Local or remote playback
     */
    var isLocalSession: Boolean = true,
    /**
     * Indicates that this player is a resumption player (ie. It only shows a play actions which
     * will start the app and start playing).
     */
    var resumption: Boolean = false,
    /**
     * Notification key for cancelling a media player after a timeout (when not using resumption.)
     */
    val notificationKey: String? = null,
    var hasCheckedForResume: Boolean = false,

    /**
     * If apps do not report PlaybackState, set as null to imply 'undetermined'
     */
    val isPlaying: Boolean? = null,

    /**
     * Set from the notification and used as fallback when PlaybackState cannot be determined
     */
    val isClearable: Boolean = true
)

/** State of a media action. */
data class MediaAction(
    val drawable: Drawable?,
    val action: Runnable?,
    val contentDescription: CharSequence?
)

/** State of the media device. */
data class MediaDeviceData(
    val enabled: Boolean,
    val icon: Drawable?,
    val name: String?
)
