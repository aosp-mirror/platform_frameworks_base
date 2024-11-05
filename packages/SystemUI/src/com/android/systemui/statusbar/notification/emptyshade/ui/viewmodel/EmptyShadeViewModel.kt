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

import android.content.Context
import android.icu.text.MessageFormat
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.res.R
import com.android.systemui.shared.notifications.domain.interactor.NotificationSettingsInteractor
import com.android.systemui.statusbar.notification.NotificationActivityStarter.SettingsIntent
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterMessageViewModel
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * ViewModel for the empty shade (aka the "No notifications" text shown when there are no
 * notifications.
 */
class EmptyShadeViewModel
@AssistedInject
constructor(
    private val context: Context,
    zenModeInteractor: ZenModeInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    notificationSettingsInteractor: NotificationSettingsInteractor,
    @Background bgDispatcher: CoroutineDispatcher,
    dumpManager: DumpManager,
) : FlowDumperImpl(dumpManager) {
    val areNotificationsHiddenInShade: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else if (ModesEmptyShadeFix.isEnabled) {
            zenModeInteractor.areNotificationsHiddenInShade
                .dumpWhileCollecting("areNotificationsHiddenInShade")
                .flowOn(bgDispatcher)
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

    val text: Flow<String> by lazy {
        if (ModesEmptyShadeFix.isUnexpectedlyInLegacyMode()) {
            flowOf(context.getString(R.string.empty_shade_text))
        } else {
            // Note: Flag modes_ui_empty_shade includes two pieces: refactoring the empty shade to
            // recommended architecture, and making it so it reacts to changes for the new Modes.
            // The former does not depend on the modes flags being on, but the latter does.
            if (ModesUi.isEnabled) {
                    zenModeInteractor.modesHidingNotifications.map { modes ->
                        // Create a string that is either "No notifications" if no modes are
                        // filtering
                        // them out, or something like "Notifications paused by SomeMode" otherwise.
                        val msgFormat =
                            MessageFormat(
                                context.getString(R.string.modes_suppressing_shade_text),
                                Locale.getDefault(),
                            )
                        val count = modes.count()
                        val args: MutableMap<String, Any> = HashMap()
                        args["count"] = count
                        if (count >= 1) {
                            args["mode"] = modes[0].name
                        }
                        msgFormat.format(args)
                    }
                } else {
                    areNotificationsHiddenInShade.map { areNotificationsHiddenInShade ->
                        if (areNotificationsHiddenInShade) {
                            context.getString(R.string.dnd_suppressing_shade_text)
                        } else {
                            context.getString(R.string.empty_shade_text)
                        }
                    }
                }
                .flowOn(bgDispatcher)
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

    val onClick: Flow<SettingsIntent> by lazy {
        ModesEmptyShadeFix.assertInNewMode()
        combine(
                zenModeInteractor.modesHidingNotifications,
                notificationSettingsInteractor.isNotificationHistoryEnabled,
            ) { modes, isNotificationHistoryEnabled ->
                if (modes.isNotEmpty()) {
                    if (modes.size == 1) {
                        SettingsIntent.forModeSettings(modes[0].id)
                    } else {
                        SettingsIntent.forModesSettings()
                    }
                } else {
                    if (isNotificationHistoryEnabled) {
                        SettingsIntent.forNotificationHistory()
                    } else {
                        SettingsIntent.forNotificationSettings()
                    }
                }
            }
            .flowOn(bgDispatcher)
    }

    @AssistedFactory
    interface Factory {
        fun create(): EmptyShadeViewModel
    }
}
