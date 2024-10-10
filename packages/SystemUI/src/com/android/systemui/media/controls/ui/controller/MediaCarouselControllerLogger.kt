/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.controller

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.MediaCarouselControllerLog
import javax.inject.Inject

/** A debug logger for [MediaCarouselController]. */
@SysUISingleton
class MediaCarouselControllerLogger
@Inject
constructor(@MediaCarouselControllerLog private val buffer: LogBuffer) {
    /**
     * Log that there might be a potential memory leak for the [MediaControlPanel] and/or
     * [MediaViewController] related to [key].
     */
    fun logPotentialMemoryLeak(key: String) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = key },
            {
                "Potential memory leak: " +
                    "Removing control panel for $str1 from map without calling #onDestroy"
            },
        )

    fun logMediaLoaded(key: String, active: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = active
            },
            { "add player $str1, active: $bool1" },
        )

    fun logMediaRemoved(key: String, userInitiated: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = userInitiated
            },
            { "removing player $str1, by user $bool1" },
        )

    fun logRecommendationLoaded(key: String, isActive: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = isActive
            },
            { "add recommendation $str1, active $bool1" },
        )

    fun logRecommendationRemoved(key: String, immediately: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = immediately
            },
            { "removing recommendation $str1, immediate=$bool1" },
        )

    fun logCarouselHidden() = buffer.log(TAG, LogLevel.DEBUG, {}, { "hiding carousel" })

    fun logCarouselVisible() = buffer.log(TAG, LogLevel.DEBUG, {}, { "showing carousel" })

    fun logMediaHostVisibility(location: Int, visible: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = location
                bool1 = visible
            },
            { "media host visibility changed location=$location, visible:$visible" },
        )
    }
}

private const val TAG = "MediaCarouselCtlrLog"
