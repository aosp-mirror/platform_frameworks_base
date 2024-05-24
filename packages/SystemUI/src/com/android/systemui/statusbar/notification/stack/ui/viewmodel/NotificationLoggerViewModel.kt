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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NotificationLoggerViewModel
@Inject
constructor(
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    keyguardInteractor: KeyguardInteractor,
    windowRootViewVisibilityInteractor: WindowRootViewVisibilityInteractor,
) {
    val activeNotifications: Flow<List<ActiveNotificationModel>> =
        activeNotificationsInteractor.allRepresentativeNotifications.map { it.values.toList() }

    val activeNotificationRanks: Flow<Map<String, Int>> =
        activeNotificationsInteractor.activeNotificationRanks

    val isLockscreenOrShadeInteractive: Flow<Boolean> =
        windowRootViewVisibilityInteractor.isLockscreenOrShadeVisibleAndInteractive

    val isOnLockScreen: Flow<Boolean> = keyguardInteractor.isKeyguardShowing
}
