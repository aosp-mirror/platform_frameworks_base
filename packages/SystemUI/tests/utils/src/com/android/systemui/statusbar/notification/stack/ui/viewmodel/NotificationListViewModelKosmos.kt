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

import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.seenNotificationsInteractor
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.footerViewModel
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.notificationShelfViewModel
import com.android.systemui.statusbar.policy.domain.interactor.userSetupInteractor
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import java.util.Optional

val Kosmos.notificationListViewModel by Fixture {
    NotificationListViewModel(
        shelf = notificationShelfViewModel,
        hideListViewModel = hideListViewModel,
        footer = Optional.of(footerViewModel),
        activeNotificationsInteractor = activeNotificationsInteractor,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        seenNotificationsInteractor = seenNotificationsInteractor,
        shadeInteractor = shadeInteractor,
        zenModeInteractor = zenModeInteractor,
    )
}
