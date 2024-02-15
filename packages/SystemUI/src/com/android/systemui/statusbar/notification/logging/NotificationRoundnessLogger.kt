/*
 * Copyright (c) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.logging

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.dagger.NotificationRenderLog
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.NotificationSection
import javax.inject.Inject

/** Handles logging for the {NotificationRoundnessManager}. */
class NotificationRoundnessLogger
@Inject
constructor(@NotificationRenderLog val buffer: LogBuffer) {

    /** Called when the {NotificationRoundnessManager} updates the corners if the Notifications. */
    fun onCornersUpdated(
        view: ExpandableView?,
        isFirstInSection: Boolean,
        isLastInSection: Boolean,
        topChanged: Boolean,
        bottomChanged: Boolean
    ) {
        buffer.log(
            TAG_ROUNDNESS,
            INFO,
            {
                str1 = (view as? ExpandableNotificationRow)?.entry?.key
                bool1 = isFirstInSection
                bool2 = isLastInSection
                bool3 = topChanged
                bool4 = bottomChanged
            },
            {
                "onCornersUpdated: " +
                    "entry=$str1 isFirstInSection=$bool1 isLastInSection=$bool2 " +
                    "topChanged=$bool3 bottomChanged=$bool4"
            }
        )
    }

    /** Called when we update the {NotificationRoundnessManager} with new sections. */
    fun onSectionCornersUpdated(sections: Array<NotificationSection?>, anyChanged: Boolean) {
        buffer.log(
            TAG_ROUNDNESS,
            INFO,
            {
                int1 = sections.size
                bool1 = anyChanged
            },
            { "onSectionCornersUpdated: sections size=$int1 anyChanged=$bool1" }
        )
    }
}

private const val TAG_ROUNDNESS = "NotifRoundnessLogger"
