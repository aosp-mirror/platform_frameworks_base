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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.render.NotifStackController
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.util.traceSection
import javax.inject.Inject

/**
 * A small coordinator which updates the notif stack (the view layer which holds notifications)
 * with high-level data after the stack is populated with the final entries.
 */
@CoordinatorScope
class StackCoordinator @Inject internal constructor(
    private val notificationIconAreaController: NotificationIconAreaController
) : Coordinator {

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnAfterRenderListListener(::onAfterRenderList)
    }

    fun onAfterRenderList(entries: List<ListEntry>, controller: NotifStackController) =
        traceSection("StackCoordinator.onAfterRenderList") {
            controller.setNotifStats(calculateNotifStats(entries))
            notificationIconAreaController.updateNotificationIcons(entries)
        }

    private fun calculateNotifStats(entries: List<ListEntry>): NotifStats {
        var hasNonClearableAlertingNotifs = false
        var hasClearableAlertingNotifs = false
        var hasNonClearableSilentNotifs = false
        var hasClearableSilentNotifs = false
        entries.forEach {
            val section = checkNotNull(it.section) { "Null section for ${it.key}" }
            val entry = checkNotNull(it.representativeEntry) { "Null notif entry for ${it.key}" }
            val isSilent = section.bucket == BUCKET_SILENT
            // NOTE: NotificationEntry.isClearable will internally check group children to ensure
            //  the group itself definitively clearable.
            val isClearable = entry.isClearable
            when {
                isSilent && isClearable -> hasClearableSilentNotifs = true
                isSilent && !isClearable -> hasNonClearableSilentNotifs = true
                !isSilent && isClearable -> hasClearableAlertingNotifs = true
                !isSilent && !isClearable -> hasNonClearableAlertingNotifs = true
            }
        }
        return NotifStats(
            numActiveNotifs = entries.size,
            hasNonClearableAlertingNotifs = hasNonClearableAlertingNotifs,
            hasClearableAlertingNotifs = hasClearableAlertingNotifs,
            hasNonClearableSilentNotifs = hasNonClearableSilentNotifs,
            hasClearableSilentNotifs = hasClearableSilentNotifs
        )
    }
}