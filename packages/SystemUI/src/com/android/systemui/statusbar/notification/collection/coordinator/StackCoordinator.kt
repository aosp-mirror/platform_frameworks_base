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

import com.android.app.tracing.traceSection
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManagerImpl
import com.android.systemui.statusbar.notification.collection.render.NotifStackController
import com.android.systemui.statusbar.notification.collection.render.NotifStats
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.RenderNotificationListInteractor
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import javax.inject.Inject

/**
 * A small coordinator which updates the notif stack (the view layer which holds notifications) with
 * high-level data after the stack is populated with the final entries.
 */
@CoordinatorScope
class StackCoordinator
@Inject
internal constructor(
    private val groupExpansionManagerImpl: GroupExpansionManagerImpl,
    private val notificationIconAreaController: NotificationIconAreaController,
    private val renderListInteractor: RenderNotificationListInteractor,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
) : Coordinator {

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnAfterRenderListListener(::onAfterRenderList)
        groupExpansionManagerImpl.attach(pipeline)
    }

    fun onAfterRenderList(entries: List<ListEntry>, controller: NotifStackController) =
        traceSection("StackCoordinator.onAfterRenderList") {
            val notifStats = calculateNotifStats(entries)
            if (FooterViewRefactor.isEnabled) {
                activeNotificationsInteractor.setNotifStats(notifStats)
            }
            // TODO(b/293167744): This shouldn't be done if the footer flag is on, once the footer
            //  visibility is handled in the new stack.
            controller.setNotifStats(notifStats)
            if (NotificationIconContainerRefactor.isEnabled || FooterViewRefactor.isEnabled) {
                renderListInteractor.setRenderedList(entries)
            }
            if (!NotificationIconContainerRefactor.isEnabled) {
                notificationIconAreaController.updateNotificationIcons(entries)
            }
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
