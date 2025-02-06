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

import android.content.Context
import android.view.View
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.notification.domain.interactor.StatusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.ColorsModel.Companion.toCustomColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.domain.model.TopPinnedState
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A view model for status bar chips for promoted ongoing notifications. */
@SysUISingleton
class NotifChipsViewModel
@Inject
constructor(
    @Main private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val notifChipsInteractor: StatusBarNotificationChipsInteractor,
    headsUpNotificationInteractor: HeadsUpNotificationInteractor,
) {
    /**
     * A flow modeling the notification chips that should be shown. Emits an empty list if there are
     * no notifications that should show a status bar chip.
     */
    val chips: Flow<List<OngoingActivityChipModel.Active>> =
        combine(
                notifChipsInteractor.notificationChips,
                headsUpNotificationInteractor.statusBarHeadsUpState,
            ) { notifications, headsUpState ->
                notifications.map { it.toActivityChipModel(headsUpState) }
            }
            .distinctUntilChanged()

    /** Converts the notification to the [OngoingActivityChipModel] object. */
    private fun NotificationChipModel.toActivityChipModel(
        headsUpState: TopPinnedState
    ): OngoingActivityChipModel.Active {
        StatusBarNotifChips.assertInNewMode()
        val contentDescription = getContentDescription(this.appName)
        val icon =
            if (this.statusBarChipIconView != null) {
                StatusBarConnectedDisplays.assertInLegacyMode()
                OngoingActivityChipModel.ChipIcon.StatusBarView(
                    this.statusBarChipIconView,
                    contentDescription,
                )
            } else {
                StatusBarConnectedDisplays.assertInNewMode()
                OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon(
                    this.key,
                    contentDescription,
                )
            }
        val colors = this.promotedContent.toCustomColorsModel()

        val clickListener: () -> Unit = {
            // The notification pipeline needs everything to run on the main thread, so keep
            // this event on the main thread.
            applicationScope.launch {
                // TODO(b/364653005): Move accessibility focus to the HUN when chip is tapped.
                notifChipsInteractor.onPromotedNotificationChipTapped(this@toActivityChipModel.key)
            }
        }
        val onClickListenerLegacy =
            View.OnClickListener {
                StatusBarChipsModernization.assertInLegacyMode()
                clickListener.invoke()
            }
        val clickBehavior =
            OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification({
                StatusBarChipsModernization.assertInNewMode()
                clickListener.invoke()
            })

        val isShowingHeadsUpFromChipTap =
            headsUpState is TopPinnedState.Pinned &&
                headsUpState.status == PinnedStatus.PinnedByUser &&
                headsUpState.key == this.key
        if (isShowingHeadsUpFromChipTap) {
            // If the user tapped this chip to show the HUN, we want to just show the icon because
            // the HUN will show the rest of the information.
            return OngoingActivityChipModel.Active.IconOnly(
                this.key,
                icon,
                colors,
                onClickListenerLegacy,
                clickBehavior,
            )
        }

        if (this.promotedContent.shortCriticalText != null) {
            return OngoingActivityChipModel.Active.Text(
                this.key,
                icon,
                colors,
                this.promotedContent.shortCriticalText,
                onClickListenerLegacy,
                clickBehavior,
            )
        }

        if (
            Flags.promoteNotificationsAutomatically() &&
                this.promotedContent.wasPromotedAutomatically
        ) {
            // When we're promoting notifications automatically, the `when` time set on the
            // notification will likely just be set to the current time, which would cause the chip
            // to always show "now". We don't want early testers to get that experience since it's
            // not what will happen at launch, so just don't show any time.
            return OngoingActivityChipModel.Active.IconOnly(
                this.key,
                icon,
                colors,
                onClickListenerLegacy,
                clickBehavior,
            )
        }

        if (this.promotedContent.time == null) {
            return OngoingActivityChipModel.Active.IconOnly(
                this.key,
                icon,
                colors,
                onClickListenerLegacy,
                clickBehavior,
            )
        }
        when (this.promotedContent.time.mode) {
            PromotedNotificationContentModel.When.Mode.BasicTime -> {
                return OngoingActivityChipModel.Active.ShortTimeDelta(
                    this.key,
                    icon,
                    colors,
                    time = this.promotedContent.time.time,
                    onClickListenerLegacy,
                    clickBehavior,
                )
            }
            PromotedNotificationContentModel.When.Mode.CountUp -> {
                return OngoingActivityChipModel.Active.Timer(
                    this.key,
                    icon,
                    colors,
                    startTimeMs = this.promotedContent.time.time,
                    onClickListenerLegacy,
                    clickBehavior,
                )
            }
            PromotedNotificationContentModel.When.Mode.CountDown -> {
                // TODO(b/364653005): Support CountDown.
                return OngoingActivityChipModel.Active.Timer(
                    this.key,
                    icon,
                    colors,
                    startTimeMs = this.promotedContent.time.time,
                    onClickListenerLegacy,
                    clickBehavior,
                )
            }
        }
    }

    private fun getContentDescription(appName: String): ContentDescription {
        val ongoingDescription =
            context.getString(R.string.ongoing_notification_extra_content_description)
        return ContentDescription.Loaded(
            context.getString(
                R.string.accessibility_desc_notification_icon,
                appName,
                ongoingDescription,
            )
        )
    }
}
