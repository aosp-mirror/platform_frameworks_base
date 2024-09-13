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

package com.android.systemui.statusbar.notification.emptyshade.ui.viewmodel

import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.shared.notifications.domain.interactor.NotificationSettingsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterMessageViewModel
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * ViewModel for the empty shade (aka the "No notifications" text shown when there are no
 * notifications.
 */
class EmptyShadeViewModel
@AssistedInject
constructor(
    zenModeInteractor: ZenModeInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    notificationSettingsInteractor: NotificationSettingsInteractor,
    dumpManager: DumpManager,
) : FlowDumperImpl(dumpManager) {
    val areNotificationsHiddenInShade: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            zenModeInteractor.areNotificationsHiddenInShade.dumpWhileCollecting(
                "areNotificationsHiddenInShade"
            )
        }
    }

    val hasFilteredOutSeenNotifications: StateFlow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            MutableStateFlow(false)
        } else {
            seenNotificationsInteractor.hasFilteredOutSeenNotifications.dumpValue(
                "hasFilteredOutSeenNotifications"
            )
        }
    }

    val text: Flow<Int> by lazy {
        if (ModesEmptyShadeFix.isUnexpectedlyInLegacyMode()) {
            flowOf(R.string.empty_shade_text)
        } else {
            areNotificationsHiddenInShade.map { areNotificationsHiddenInShade ->
                if (areNotificationsHiddenInShade) {
                    // TODO(b/366003631): This should reflect the current mode instead of just DND.
                    R.string.dnd_suppressing_shade_text
                } else {
                    R.string.empty_shade_text
                }
            }
        }
    }

    val footer: FooterMessageViewModel by lazy {
        ModesEmptyShadeFix.assertInNewMode()
        FooterMessageViewModel(
            messageId = R.string.unlock_to_see_notif_text,
            iconId = R.drawable.ic_friction_lock_closed,
            isVisible = hasFilteredOutSeenNotifications,
        )
    }

    val tappingShouldLaunchHistory by lazy {
        ModesEmptyShadeFix.assertInNewMode()
        notificationSettingsInteractor.isNotificationHistoryEnabled
    }

    @AssistedFactory
    interface Factory {
        fun create(): EmptyShadeViewModel
    }
}
