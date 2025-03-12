/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.footer.ui.viewmodel

import android.content.Intent
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.notifications.domain.interactor.NotificationSettingsInteractor
import com.android.systemui.statusbar.notification.NotificationActivityStarter.SettingsIntent
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import dagger.Module
import dagger.Provides
import java.util.Optional
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** ViewModel for [FooterView]. */
class FooterViewModel(
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    notificationSettingsInteractor: NotificationSettingsInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    shadeInteractor: ShadeInteractor,
) {
    /** A message to show instead of the footer buttons. */
    val message: FooterMessageViewModel =
        FooterMessageViewModel(
            messageId = R.string.unlock_to_see_notif_text,
            iconId = R.drawable.ic_friction_lock_closed,
            isVisible = seenNotificationsInteractor.hasFilteredOutSeenNotifications,
        )

    private val clearAllButtonVisible =
        activeNotificationsInteractor.hasClearableNotifications
            .combine(message.isVisible) { hasClearableNotifications, isMessageVisible ->
                if (isMessageVisible) {
                    // If the message is visible, the button never is
                    false
                } else {
                    hasClearableNotifications
                }
            }
            .distinctUntilChanged()

    /** The button for clearing notifications. */
    val clearAllButton: FooterButtonViewModel =
        FooterButtonViewModel(
            labelId = flowOf(R.string.clear_all_notifications_text),
            accessibilityDescriptionId = flowOf(R.string.accessibility_clear_all),
            isVisible =
                clearAllButtonVisible
                    .sample(
                        // TODO(b/322167853): This check is currently duplicated in
                        //  NotificationListViewModel, but instead it should be a field in
                        //  ShadeAnimationInteractor.
                        combine(
                                shadeInteractor.isShadeFullyExpanded,
                                shadeInteractor.isShadeTouchable,
                                ::Pair,
                            )
                            .onStart { emit(Pair(false, false)) }
                    ) { clearAllButtonVisible, (isShadeFullyExpanded, animationsEnabled) ->
                        val shouldAnimate = isShadeFullyExpanded && animationsEnabled
                        AnimatableEvent(clearAllButtonVisible, shouldAnimate)
                    }
                    .toAnimatedValueFlow(),
        )

    // Settings buttons are not visible when the message is.
    val settingsButtonVisible: Flow<Boolean> = message.isVisible.map { !it }
    val historyButtonVisible: Flow<Boolean> = message.isVisible.map { !it }

    val manageButtonShouldLaunchHistory =
        notificationSettingsInteractor.isNotificationHistoryEnabled

    val manageOrHistoryButtonClick: Flow<SettingsIntent> by lazy {
        if (ModesEmptyShadeFix.isUnexpectedlyInLegacyMode()) {
            flowOf(SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_SETTINGS)))
        } else {
            notificationSettingsInteractor.isNotificationHistoryEnabled.map {
                isNotificationHistoryEnabled ->
                if (isNotificationHistoryEnabled) {
                    SettingsIntent.forNotificationHistory(
                        cujType = InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
                    )
                } else {
                    SettingsIntent.forNotificationSettings(
                        cujType = InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
                    )
                }
            }
        }
    }

    private val manageOrHistoryButtonText: Flow<Int> =
        notificationSettingsInteractor.isNotificationHistoryEnabled.map { shouldLaunchHistory ->
            if (shouldLaunchHistory) R.string.manage_notifications_history_text
            else R.string.manage_notifications_text
        }

    /**
     * The button for managing notification settings or opening notification history. This is
     * replaced by two separate buttons in the redesign. These are currently static, and therefore
     * not modeled here, but if that changes we can also add them as FooterButtonViewModels.
     */
    val manageOrHistoryButton: FooterButtonViewModel =
        FooterButtonViewModel(
            labelId = manageOrHistoryButtonText,
            accessibilityDescriptionId = manageOrHistoryButtonText,
            isVisible =
                // Hide the manage button if the message is visible
                message.isVisible.map { messageVisible ->
                    AnimatedValue.NotAnimating(!messageVisible)
                },
        )
}

@Module
object FooterViewModelModule {
    @Provides
    @SysUISingleton
    fun provideOptional(
        activeNotificationsInteractor: Provider<ActiveNotificationsInteractor>,
        notificationSettingsInteractor: Provider<NotificationSettingsInteractor>,
        seenNotificationsInteractor: Provider<SeenNotificationsInteractor>,
        shadeInteractor: Provider<ShadeInteractor>,
    ): Optional<FooterViewModel> {
        return if (FooterViewRefactor.isEnabled) {
            Optional.of(
                FooterViewModel(
                    activeNotificationsInteractor.get(),
                    notificationSettingsInteractor.get(),
                    seenNotificationsInteractor.get(),
                    shadeInteractor.get(),
                )
            )
        } else {
            Optional.empty()
        }
    }
}
