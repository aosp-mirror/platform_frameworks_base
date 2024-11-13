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

package com.android.systemui.media.controls.shared

import com.android.internal.logging.InstanceId
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.MediaLog
import javax.inject.Inject

/** A buffered log for media loading events. */
@SysUISingleton
class MediaLogger @Inject constructor(@MediaLog private val buffer: LogBuffer) {

    fun logMediaLoaded(instanceId: InstanceId, active: Boolean, reason: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = instanceId.toString()
                bool1 = active
                str2 = reason
            },
            { "add media $str1, active: $bool1, reason: $str2" },
        )
    }

    fun logMediaRemoved(instanceId: InstanceId, reason: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = instanceId.toString()
                str2 = reason
            },
            { "removing media $str1, reason: $str2" },
        )
    }

    fun logRecommendationLoaded(key: String, isActive: Boolean, reason: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = isActive
                str2 = reason
            },
            { "add recommendation $str1, active $bool1, reason: $str2" },
        )
    }

    fun logRecommendationRemoved(key: String, immediately: Boolean, reason: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = key
                bool1 = immediately
                str2 = reason
            },
            { "removing recommendation $str1, immediate=$bool1, reason: $str2" },
        )
    }

    fun logMediaCardAdded(instanceId: InstanceId) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = instanceId.toString() },
            { "adding media card $str1 to carousel" },
        )
    }

    fun logMediaCardRemoved(instanceId: InstanceId) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = instanceId.toString() },
            { "removing media card $str1 from carousel" },
        )
    }

    fun logMediaRecommendationCardAdded(key: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = key },
            { "adding recommendation card $str1 to carousel" },
        )
    }

    fun logMediaRecommendationCardRemoved(key: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = key },
            { "removing recommendation card $str1 from carousel" },
        )
    }

    fun logDuplicateMediaNotification(key: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = key },
            { "duplicate media notification $str1 posted" },
        )
    }

    fun logMediaControlIsBound(
        instanceId: InstanceId,
        artistName: CharSequence,
        title: CharSequence,
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = instanceId.toString()
                str2 = artistName.toString()
                str3 = title.toString()
            },
            { "binding media control, instance id= $str1, artist= $str2, title= $str3" },
        )
    }

    companion object {
        private const val TAG = "MediaLog"
    }
}
