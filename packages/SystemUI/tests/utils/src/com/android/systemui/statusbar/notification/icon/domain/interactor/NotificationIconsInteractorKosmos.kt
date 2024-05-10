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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.icon.domain.interactor

import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.statusbar.data.repository.notificationListenerSettingsRepository
import com.android.systemui.statusbar.notification.data.repository.notificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.wm.shell.bubbles.bubblesOptional
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.alwaysOnDisplayNotificationIconsInteractor by Fixture {
    AlwaysOnDisplayNotificationIconsInteractor(
        deviceEntryInteractor = deviceEntryInteractor,
        iconsInteractor = notificationIconsInteractor,
    )
}
val Kosmos.statusBarNotificationIconsInteractor by Fixture {
    StatusBarNotificationIconsInteractor(
        iconsInteractor = notificationIconsInteractor,
        settingsRepository = notificationListenerSettingsRepository,
    )
}
val Kosmos.notificationIconsInteractor by Fixture {
    NotificationIconsInteractor(
        activeNotificationsInteractor = activeNotificationsInteractor,
        bubbles = bubblesOptional,
        keyguardViewStateRepository = notificationsKeyguardViewStateRepository,
    )
}
