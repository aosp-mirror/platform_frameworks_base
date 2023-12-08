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

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.android.systemui.back.domain.interactor.BackActionInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyevent.domain.interactor.KeyEventInteractor.Companion.handleAction
import com.android.systemui.media.controls.util.MediaSessionLegacyHelperWrapper
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import javax.inject.Inject

/** Handles key events arriving when the keyguard is showing or device is dozing. */
@SysUISingleton
class KeyguardKeyEventInteractor
@Inject
constructor(
    private val context: Context,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardInteractor: KeyguardInteractor,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val shadeController: ShadeController,
    private val mediaSessionLegacyHelperWrapper: MediaSessionLegacyHelperWrapper,
    private val backActionInteractor: BackActionInteractor,
) {

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (statusBarStateController.isDozing) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP -> return dispatchVolumeKeyEvent(event)
            }
        }

        if (event.handleAction()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> return dispatchMenuKeyEvent()
                KeyEvent.KEYCODE_SPACE -> return dispatchSpaceEvent()
            }
        }
        return false
    }

    /**
     * While IME is active and a BACK event is detected, check with {@link
     * StatusBarKeyguardViewManager#dispatchBackKeyEventPreIme()} to see if the event should be
     * handled before routing to IME, in order to prevent the user from having to hit back twice to
     * exit bouncer.
     */
    fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK ->
                if (
                    statusBarStateController.state == StatusBarState.KEYGUARD &&
                        statusBarKeyguardViewManager.dispatchBackKeyEventPreIme()
                ) {
                    return backActionInteractor.onBackRequested()
                }
        }
        return false
    }

    fun interceptMediaKey(event: KeyEvent): Boolean {
        return statusBarStateController.state == StatusBarState.KEYGUARD &&
            statusBarKeyguardViewManager.interceptMediaKey(event)
    }

    private fun dispatchMenuKeyEvent(): Boolean {
        val shouldUnlockOnMenuPressed =
            isDeviceInteractive() &&
                (statusBarStateController.state != StatusBarState.SHADE) &&
                statusBarKeyguardViewManager.shouldDismissOnMenuPressed()
        if (shouldUnlockOnMenuPressed) {
            shadeController.animateCollapseShadeForced()
            return true
        }
        return false
    }

    private fun dispatchSpaceEvent(): Boolean {
        if (isDeviceInteractive() && statusBarStateController.state != StatusBarState.SHADE) {
            shadeController.animateCollapseShadeForced()
            return true
        }
        return false
    }

    private fun dispatchVolumeKeyEvent(event: KeyEvent): Boolean {
        mediaSessionLegacyHelperWrapper
            .getHelper(context)
            .sendVolumeKeyEvent(event, AudioManager.USE_DEFAULT_STREAM_TYPE, true)
        return true
    }

    private fun isDeviceInteractive(): Boolean {
        return keyguardInteractor.wakefulnessModel.value.isDeviceInteractive()
    }
}
