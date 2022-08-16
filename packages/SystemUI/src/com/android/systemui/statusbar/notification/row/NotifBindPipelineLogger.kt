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
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.row

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.dagger.NotificationLog
import javax.inject.Inject

class NotifBindPipelineLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logStageSet(stageName: String) {
        buffer.log(TAG, INFO, {
            str1 = stageName
        }, {
            "Stage set: $str1"
        })
    }

    fun logManagedRow(notifKey: String) {
        buffer.log(TAG, INFO, {
            str1 = notifKey
        }, {
            "Row set for notif: $str1"
        })
    }

    fun logRequestPipelineRun(notifKey: String) {
        buffer.log(TAG, INFO, {
            str1 = notifKey
        }, {
            "Request pipeline run for notif: $str1"
        })
    }

    fun logRequestPipelineRowNotSet(notifKey: String) {
        buffer.log(TAG, INFO, {
            str1 = notifKey
        }, {
            "Row is not set so pipeline will not run. notif = $str1"
        })
    }

    fun logStartPipeline(notifKey: String) {
        buffer.log(TAG, INFO, {
            str1 = notifKey
        }, {
            "Start pipeline for notif: $str1"
        })
    }

    fun logFinishedPipeline(notifKey: String, numCallbacks: Int) {
        buffer.log(TAG, INFO, {
            str1 = notifKey
            int1 = numCallbacks
        }, {
            "Finished pipeline for notif $str1 with $int1 callbacks"
        })
    }
}

private const val TAG = "NotifBindPipeline"