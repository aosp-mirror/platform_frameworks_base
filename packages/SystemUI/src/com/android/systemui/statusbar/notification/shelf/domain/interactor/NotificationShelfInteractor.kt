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

package com.android.systemui.statusbar.notification.shelf.domain.interactor

import android.os.PowerManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.NotificationShelf
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Interactor for the [NotificationShelf] */
@SysUISingleton
class NotificationShelfInteractor
@Inject
constructor(
    private val keyguardRepository: KeyguardRepository,
    private val deviceEntryFaceAuthRepository: DeviceEntryFaceAuthRepository,
    private val powerInteractor: PowerInteractor,
    private val keyguardTransitionController: LockscreenShadeTransitionController,
) {
    /** Is the shelf showing on the keyguard? */
    val isShowingOnKeyguard: Flow<Boolean>
        get() = keyguardRepository.isKeyguardShowing

    /** Is the system in a state where the shelf is just a static display of notification icons? */
    val isShelfStatic: Flow<Boolean>
        get() =
            combine(
                keyguardRepository.isKeyguardShowing,
                deviceEntryFaceAuthRepository.isBypassEnabled,
            ) { isKeyguardShowing, isBypassEnabled ->
                isKeyguardShowing && isBypassEnabled
            }

    /** Transition keyguard to the locked shade, triggered by the shelf. */
    fun goToLockedShadeFromShelf() {
        powerInteractor.wakeUpIfDozing("SHADE_CLICK", PowerManager.WAKE_REASON_GESTURE)
        keyguardTransitionController.goToLockedShade(null)
    }
}
