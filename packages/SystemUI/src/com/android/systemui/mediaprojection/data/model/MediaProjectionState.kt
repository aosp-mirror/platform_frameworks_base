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

package com.android.systemui.mediaprojection.data.model

import android.app.ActivityManager.RunningTaskInfo

/** Represents the state of media projection. */
sealed interface MediaProjectionState {
    /** There is no media being projected. */
    data object NotProjecting : MediaProjectionState

    /**
     * Media is currently being projected.
     *
     * @property hostPackage the package name of the app that is receiving the content of the media
     *   projection (aka which app the phone screen contents are being sent to).
     * @property hostDeviceName the name of the other device that's receiving the content of the
     *   media projection. Null if the media projection is going to this same device (e.g. another
     *   app is recording the screen).
     */
    sealed class Projecting(
        open val hostPackage: String,
        open val hostDeviceName: String?,
    ) : MediaProjectionState {
        /** The entire screen is being projected. */
        data class EntireScreen(
            override val hostPackage: String,
            override val hostDeviceName: String? = null,
        ) : Projecting(hostPackage, hostDeviceName)

        /** Only a single task is being projected. */
        data class SingleTask(
            override val hostPackage: String,
            override val hostDeviceName: String?,
            val task: RunningTaskInfo,
        ) : Projecting(hostPackage, hostDeviceName)
    }
}
