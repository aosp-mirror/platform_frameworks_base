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

package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.content.res.mainResources
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.headsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.statusBarNotificationIconsInteractor
import com.android.systemui.statusbar.phone.domain.interactor.darkIconInteractor

val Kosmos.notificationIconContainerStatusBarViewModel by
    Kosmos.Fixture {
        NotificationIconContainerStatusBarViewModel(
            darkIconInteractor = darkIconInteractor,
            iconsInteractor = statusBarNotificationIconsInteractor,
            headsUpIconInteractor = headsUpNotificationIconInteractor,
            keyguardInteractor = keyguardInteractor,
            notificationsInteractor = activeNotificationsInteractor,
            resources = mainResources,
            shadeInteractor = shadeInteractor,
        )
    }
