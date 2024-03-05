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

package com.android.systemui.statusbar.notification.stack.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.domain.interactor.PowerInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Interactor exposing states related to the stack's context */
@SysUISingleton
class NotificationStackInteractor
@Inject
constructor(
    keyguardInteractor: KeyguardInteractor,
    powerInteractor: PowerInteractor,
) {
    val isShowingOnLockscreen: Flow<Boolean> =
        combine(
                // Non-notification UI elements of the notification list should not be visible
                // on the lockscreen (incl. AOD and bouncer), except if the shade is opened on
                // top. See b/219680200 for the footer and b/228790482, b/267060171 for the
                // empty shade.
                // TODO(b/323187006): There's a plan to eventually get rid of StatusBarState
                //  entirely, so this will have to be replaced at some point.
                keyguardInteractor.statusBarState.map { it == StatusBarState.KEYGUARD },
                // The StatusBarState is unfortunately not updated quickly enough when the power
                // button is pressed, so this is necessary in addition to the KEYGUARD check to
                // cover the transition to AOD while going to sleep (b/190227875).
                powerInteractor.isAsleep,
            ) { (isOnKeyguard, isAsleep) ->
                isOnKeyguard || isAsleep
            }
            .distinctUntilChanged()
}
