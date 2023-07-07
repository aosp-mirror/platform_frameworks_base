/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.controls.util

import android.app.StatusBarManager
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

@SysUISingleton
class MediaFlags @Inject constructor(private val featureFlags: FeatureFlags) {
    /**
     * Check whether media control actions should be based on PlaybackState instead of notification
     */
    fun areMediaSessionActionsEnabled(packageName: String, user: UserHandle): Boolean {
        val enabled = StatusBarManager.useMediaSessionActionsForApp(packageName, user)
        // Allow global override with flag
        return enabled || featureFlags.isEnabled(Flags.MEDIA_SESSION_ACTIONS)
    }

    /** Check whether we support displaying information about mute await connections. */
    fun areMuteAwaitConnectionsEnabled() = featureFlags.isEnabled(Flags.MEDIA_MUTE_AWAIT)

    /**
     * Check whether we enable support for nearby media devices. See
     * [android.app.StatusBarManager.registerNearbyMediaDevicesProvider] for more information.
     */
    fun areNearbyMediaDevicesEnabled() = featureFlags.isEnabled(Flags.MEDIA_NEARBY_DEVICES)

    /** Check whether we show explicit indicator on UMO */
    fun isExplicitIndicatorEnabled() = featureFlags.isEnabled(Flags.MEDIA_EXPLICIT_INDICATOR)

    /**
     * If true, keep active media controls for the lifetime of the MediaSession, regardless of
     * whether the underlying notification was dismissed
     */
    fun isRetainingPlayersEnabled() = featureFlags.isEnabled(Flags.MEDIA_RETAIN_SESSIONS)

    /** Check whether we show the updated recommendation card. */
    fun isRecommendationCardUpdateEnabled() =
        featureFlags.isEnabled(Flags.MEDIA_RECOMMENDATION_CARD_UPDATE)

    /** Check whether to get progress information for resume players */
    fun isResumeProgressEnabled() = featureFlags.isEnabled(Flags.MEDIA_RESUME_PROGRESS)

    /** If true, do not automatically dismiss the recommendation card */
    fun isPersistentSsCardEnabled() = featureFlags.isEnabled(Flags.MEDIA_RETAIN_RECOMMENDATIONS)

    /** Check whether we allow remote media to generate resume controls */
    fun isRemoteResumeAllowed() = featureFlags.isEnabled(Flags.MEDIA_REMOTE_RESUME)
}
