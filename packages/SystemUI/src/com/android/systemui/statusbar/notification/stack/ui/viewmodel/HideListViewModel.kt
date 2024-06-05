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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.stack.domain.interactor.HideNotificationsInteractor
import com.android.systemui.statusbar.notification.stack.shared.DisplaySwitchNotificationsHiderFlag
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@SysUISingleton
class HideListViewModel
@Inject
constructor(
    private val hideNotificationsInteractor: Provider<HideNotificationsInteractor>,
) {
    /**
     * Emits `true` whenever we want to hide the notifications list for performance reasons, then it
     * emits 'false' to show notifications back. This is used on foldable devices and emits
     * *nothing* on other devices.
     */
    val shouldHideListForPerformance: Flow<Boolean>
        get() =
            if (DisplaySwitchNotificationsHiderFlag.isEnabled) {
                hideNotificationsInteractor.get().shouldHideNotifications
            } else emptyFlow()
}
