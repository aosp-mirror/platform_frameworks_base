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

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.chips.notification.domain.interactor.StatusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A view model for status bar chips for promoted ongoing notifications. */
@SysUISingleton
class NotifChipsViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val notifChipsInteractor: StatusBarNotificationChipsInteractor,
) {
    /**
     * A flow modeling the notification chips that should be shown. Emits an empty list if there are
     * no notifications that should show a status bar chip.
     */
    val chips: Flow<List<OngoingActivityChipModel.Shown>> =
        notifChipsInteractor.notificationChips.map { notifications ->
            notifications.map { it.toActivityChipModel() }
        }

    /** Converts the notification to the [OngoingActivityChipModel] object. */
    private fun NotificationChipModel.toActivityChipModel(): OngoingActivityChipModel.Shown {
        StatusBarNotifChips.assertInNewMode()
        val icon =
            if (this.statusBarChipIconView != null) {
                StatusBarConnectedDisplays.assertInLegacyMode()
                OngoingActivityChipModel.ChipIcon.StatusBarView(this.statusBarChipIconView)
            } else {
                StatusBarConnectedDisplays.assertInNewMode()
                OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon(this.key)
            }
        // TODO(b/364653005): Use the notification color if applicable.
        val colors = ColorsModel.Themed
        val onClickListener =
            View.OnClickListener {
                // The notification pipeline needs everything to run on the main thread, so keep
                // this event on the main thread.
                applicationScope.launch {
                    notifChipsInteractor.onPromotedNotificationChipTapped(
                        this@toActivityChipModel.key
                    )
                }
            }
        return OngoingActivityChipModel.Shown.ShortTimeDelta(
            icon,
            colors,
            time = this.whenTime,
            onClickListener,
        )
        // TODO(b/364653005): Use Notification.showWhen to determine if we should show the time.
        // TODO(b/364653005): If Notification.shortCriticalText is set, use that instead of `when`.
        // TODO(b/364653005): If the app that posted the notification is in the foreground, don't
        // show that app's chip.
    }
}
