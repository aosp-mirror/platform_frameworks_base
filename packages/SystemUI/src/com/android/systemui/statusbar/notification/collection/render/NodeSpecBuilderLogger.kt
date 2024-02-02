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

package com.android.systemui.statusbar.notification.collection.render

import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.util.Compile
import javax.inject.Inject

class NodeSpecBuilderLogger @Inject constructor(
    notifPipelineFlags: NotifPipelineFlags,
    @NotificationLog private val buffer: LogBuffer
) {
    private val devLoggingEnabled by lazy { notifPipelineFlags.isDevLoggingEnabled() }

    fun logBuildNodeSpec(
        oldSections: Set<NotifSection?>,
        newHeaders: Map<NotifSection?, NodeController?>,
        newCounts: Map<NotifSection?, Int>,
        newSectionOrder: List<NotifSection?>
    ) {
        if (!(Compile.IS_DEBUG && devLoggingEnabled))
            return

        buffer.log(TAG, LogLevel.DEBUG, {
            int1 = newSectionOrder.size
        }, { "buildNodeSpec finished with $int1 sections" })

        for (section in newSectionOrder) {
            buffer.log(TAG, LogLevel.DEBUG, {
                str1 = section?.sectioner?.name ?: "(null)"
                str2 = newHeaders[section]?.nodeLabel ?: "(none)"
                int1 = newCounts[section] ?: -1
            }, {
                "  section $str1 has header $str2, $int1 entries"
            })
        }

        for (section in oldSections - newSectionOrder.toSet()) {
            buffer.log(TAG, LogLevel.DEBUG, {
                str1 = section?.sectioner?.name ?: "(null)"
            }, {
                "  section $str1 was removed since last run"
            })
        }
    }
}

private const val TAG = "NodeSpecBuilder"