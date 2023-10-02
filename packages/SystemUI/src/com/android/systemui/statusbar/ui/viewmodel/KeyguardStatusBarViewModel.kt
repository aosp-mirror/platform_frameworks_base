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

package com.android.systemui.statusbar.ui.viewmodel

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.statusbar.domain.interactor.KeyguardStatusBarInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * A view model for the status bar displayed on keyguard (lockscreen).
 *
 * Note: This view model is for the status bar view as a whole. Certain icons may have their own
 * individual view models, such as
 * [com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel] or
 * [com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel].
 */
@SysUISingleton
class KeyguardStatusBarViewModel
@Inject
constructor(
    @Application scope: CoroutineScope,
    keyguardInteractor: KeyguardInteractor,
    keyguardStatusBarInteractor: KeyguardStatusBarInteractor,
    batteryController: BatteryController,
) {
    /** True if this view should be visible and false otherwise. */
    val isVisible: StateFlow<Boolean> =
        combine(
                keyguardInteractor.isDozing,
                keyguardInteractor.statusBarState,
            ) { isDozing, statusBarState ->
                !isDozing && statusBarState == StatusBarState.KEYGUARD
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    /** True if the device's battery is currently charging and false otherwise. */
    // Note: Never make this an eagerly-started state flow so that the callback is removed when the
    // keyguard status bar view isn't attached.
    val isBatteryCharging: Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : BatteryStateChangeCallback {
                override fun onBatteryLevelChanged(
                    level: Int,
                    pluggedIn: Boolean,
                    charging: Boolean,
                ) {
                    trySend(charging)
                }
            }
        batteryController.addCallback(callback)
        awaitClose { batteryController.removeCallback(callback) }
    }

    /** True if we can show the user switcher on keyguard and false otherwise. */
    val isKeyguardUserSwitcherEnabled: Flow<Boolean> =
        keyguardStatusBarInteractor.isKeyguardUserSwitcherEnabled
}
