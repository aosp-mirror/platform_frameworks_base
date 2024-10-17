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

package com.android.systemui.statusbar.chips.notification.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A view model for status bar chips for promoted ongoing notifications. */
@SysUISingleton
class NotifChipsViewModel
@Inject
constructor(activeNotificationsInteractor: ActiveNotificationsInteractor) {
    /**
     * A flow modeling the notification chips that should be shown. Emits an empty list if there are
     * no notifications that should show a status bar chip.
     */
    val chips: Flow<List<OngoingActivityChipModel.Shown>> =
        activeNotificationsInteractor.promotedOngoingNotifications.map { notifications ->
            notifications.mapNotNull { it.toChipModel() }
        }

    /**
     * Converts the notification to the [OngoingActivityChipModel] object. Returns null if the
     * notification has invalid data such that it can't be displayed as a chip.
     */
    private fun ActiveNotificationModel.toChipModel(): OngoingActivityChipModel.Shown? {
        // TODO(b/364653005): Log error if there's no icon view.
        val rawIcon = this.statusBarChipIconView ?: return null
        val icon = OngoingActivityChipModel.ChipIcon.StatusBarView(rawIcon)
        // TODO(b/364653005): Use the notification color if applicable.
        val colors = ColorsModel.Themed
        // TODO(b/364653005): When the chip is clicked, show the HUN.
        val onClickListener = null
        return OngoingActivityChipModel.Shown.ShortTimeDelta(
            icon,
            colors,
            time = this.whenTime,
            onClickListener,
        )
        // TODO(b/364653005): If Notification.showWhen = false, don't show the time delta.
        // TODO(b/364653005): If Notification.whenTime is in the past, show "ago" in the text.
        // TODO(b/364653005): If Notification.shortCriticalText is set, use that instead of `when`.
        // TODO(b/364653005): If the app that posted the notification is in the foreground, don't
        // show that app's chip.
    }
}
