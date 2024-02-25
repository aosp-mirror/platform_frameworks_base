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

package com.android.systemui.statusbar.policy

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.SensitiveNotificationProtectionLog
import javax.inject.Inject

/** Logger for [SensitiveNotificationProtectionController]. */
class SensitiveNotificationProtectionControllerLogger
@Inject
constructor(@SensitiveNotificationProtectionLog private val buffer: LogBuffer) {
    fun logProjectionStart(protectionEnabled: Boolean, pkg: String) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = protectionEnabled
                str1 = pkg
            },
            { "Projection started - protection enabled:$bool1, pkg=$str1" }
        )
    }

    fun logProjectionStop() {
        buffer.log(TAG, LogLevel.DEBUG, {}, { "Projection ended - protection disabled" })
    }
}

private const val TAG = "SNPC"
