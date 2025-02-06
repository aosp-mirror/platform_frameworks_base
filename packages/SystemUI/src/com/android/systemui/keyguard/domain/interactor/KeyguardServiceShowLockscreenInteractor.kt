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

package com.android.systemui.keyguard.domain.interactor

import android.annotation.SuppressLint
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Logic around requests by [KeyguardService] to show keyguard right now, even though the device is
 * awake and not going to sleep.
 *
 * This can happen if WM#lockNow() is called, if KeyguardService#showDismissibleKeyguard is called
 * because we're folding with "continue using apps on fold" set to "swipe up to continue", or if the
 * screen is forced to stay awake but the lock timeout elapses.
 *
 * This is not the only way for the device to lock while the screen is on. The other cases, which do
 * not directly involve [KeyguardService], are handled in [KeyguardShowWhileAwakeInteractor].
 */
@SysUISingleton
class KeyguardServiceShowLockscreenInteractor
@Inject
constructor(@Background val backgroundScope: CoroutineScope) {

    /**
     * Emits whenever [KeyguardService] receives a call that indicates we should show the lockscreen
     * right now, even though the device is awake and not going to sleep.
     *
     * WARNING: This is only one of multiple reasons the keyguard might need to show while not going
     * to sleep. Unless you're dealing with keyguard internals that specifically need to know that
     * we're locking due to a call to doKeyguardTimeout or showDismissibleKeyguard, use
     * [KeyguardShowWhileAwakeInteractor.showWhileAwakeEvents].
     *
     * This is fundamentally an event flow, hence the SharedFlow.
     */
    @SuppressLint("SharedFlowCreation")
    val showNowEvents: MutableSharedFlow<ShowWhileAwakeReason> = MutableSharedFlow()

    /**
     * Called by [KeyguardService] when it receives a doKeyguardTimeout() call. This indicates that
     * the device locked while the screen was on.
     */
    fun onKeyguardServiceDoKeyguardTimeout() {
        backgroundScope.launch {
            showNowEvents.emit(ShowWhileAwakeReason.KEYGUARD_TIMEOUT_WHILE_SCREEN_ON)
        }
    }

    /**
     * Called by [KeyguardService] when it receives a showDismissibleKeyguard() call. This indicates
     * that the device was folded with settings configured to show a dismissible keyguard on the
     * outer display.
     */
    fun onKeyguardServiceShowDismissibleKeyguard() {
        backgroundScope.launch {
            showNowEvents.emit(ShowWhileAwakeReason.FOLDED_WITH_SWIPE_UP_TO_CONTINUE)
        }
    }
}
