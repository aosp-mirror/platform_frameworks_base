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
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import javax.inject.Inject

@SysUISingleton
class MediaFlags
@Inject
constructor(
    private val featureFlags: FeatureFlagsClassic,
    private val sceneContainerFlags: SceneContainerFlags
) {
    /**
     * Check whether media control actions should be based on PlaybackState instead of notification
     */
    fun areMediaSessionActionsEnabled(packageName: String, user: UserHandle): Boolean {
        val enabled = StatusBarManager.useMediaSessionActionsForApp(packageName, user)
        // Allow global override with flag
        return enabled || featureFlags.isEnabled(Flags.MEDIA_SESSION_ACTIONS)
    }

    /**
     * If true, keep active media controls for the lifetime of the MediaSession, regardless of
     * whether the underlying notification was dismissed
     */
    fun isRetainingPlayersEnabled() = featureFlags.isEnabled(Flags.MEDIA_RETAIN_SESSIONS)

    /** Check whether to get progress information for resume players */
    fun isResumeProgressEnabled() = featureFlags.isEnabled(Flags.MEDIA_RESUME_PROGRESS)

    /** If true, do not automatically dismiss the recommendation card */
    fun isPersistentSsCardEnabled() = featureFlags.isEnabled(Flags.MEDIA_RETAIN_RECOMMENDATIONS)

    /** Check whether we allow remote media to generate resume controls */
    fun isRemoteResumeAllowed() = featureFlags.isEnabled(Flags.MEDIA_REMOTE_RESUME)

    /** Check whether to use scene framework */
    fun isSceneContainerEnabled() =
        sceneContainerFlags.isEnabled() && MediaInSceneContainerFlag.isEnabled

    /** Check whether to use media refactor code */
    fun isMediaControlsRefactorEnabled() = MediaControlsRefactorFlag.isEnabled
}
