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

package com.android.systemui.shade

import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.LOW_PENALTY
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

/**
 * This gestureListener will wake up by tap when the device is dreaming but not dozing, and the
 * selected screensaver is hosted in lockscreen. Tap is gated by the falsing manager.
 *
 * Touches go through the [NotificationShadeWindowViewController].
 */
@SysUISingleton
class LockscreenHostedDreamGestureListener
@Inject
constructor(
    private val falsingManager: FalsingManager,
    private val powerInteractor: PowerInteractor,
    private val statusBarStateController: StatusBarStateController,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val keyguardRepository: KeyguardRepository,
    private val shadeLogger: ShadeLogger,
) : GestureDetector.SimpleOnGestureListener() {
    private val TAG = this::class.simpleName

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (shouldHandleMotionEvent()) {
            if (!falsingManager.isFalseTap(LOW_PENALTY)) {
                shadeLogger.d("$TAG#onSingleTapUp tap handled, requesting wakeUpIfDreaming")
                powerInteractor.wakeUpIfDreaming(
                    "DREAMING_SINGLE_TAP",
                    PowerManager.WAKE_REASON_TAP
                )
            } else {
                shadeLogger.d("$TAG#onSingleTapUp false tap ignored")
            }
            return true
        }
        return false
    }

    private fun shouldHandleMotionEvent(): Boolean {
        return keyguardRepository.isActiveDreamLockscreenHosted.value &&
            statusBarStateController.state == StatusBarState.KEYGUARD &&
            !primaryBouncerInteractor.isBouncerShowing()
    }
}
