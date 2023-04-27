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

import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.INFO
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import javax.inject.Inject

class RowContentBindStageLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logStageParams(entry: NotificationEntry, stageParams: RowContentBindParams) {
        buffer.log(TAG, INFO, {
            str1 = entry.logKey
            str2 = stageParams.toString()
        }, {
            "Invalidated notif $str1 with params: $str2"
        })
    }
}

private const val TAG = "RowContentBindStage"