/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.trust.test.lib

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManagerGlobal
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.google.common.truth.Truth.assertWithMessage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Sets a screen lock on the device for the duration of the test.
 */
class ScreenLockRule : TestRule {
    private val context: Context = getApplicationContext()
    private val uiDevice = UiDevice.getInstance(getInstrumentation())
    private val windowManager = WindowManagerGlobal.getWindowManagerService()
    private val lockPatternUtils = LockPatternUtils(context)
    private var instantLockSavedValue = false

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            verifyNoScreenLockAlreadySet()
            dismissKeyguard()
            setScreenLock()
            setLockOnPowerButton()

            try {
                base.evaluate()
            } finally {
                removeScreenLock()
                revertLockOnPowerButton()
                dismissKeyguard()
            }
        }
    }

    private fun verifyNoScreenLockAlreadySet() {
        assertWithMessage("Screen Lock must not already be set on device")
                .that(lockPatternUtils.isSecure(context.userId))
                .isFalse()
    }

    fun dismissKeyguard() {
        wait("keyguard dismissed") { count ->
            if (!uiDevice.isScreenOn) {
                Log.i(TAG, "Waking device, +500ms")
                uiDevice.wakeUp()
            }

            // Bouncer may be shown due to a race; back dismisses it
            if (count >= 10) {
                Log.i(TAG, "Pressing back to dismiss Bouncer")
                uiDevice.pressKeyCode(KeyEvent.KEYCODE_BACK)
            }

            windowManager.dismissKeyguard(null, null)

            !windowManager.isKeyguardLocked
        }
    }

    private fun setScreenLock() {
        lockPatternUtils.setLockCredential(
                LockscreenCredential.createPin(PIN),
                LockscreenCredential.createNone(),
                context.userId
        )
        wait("screen lock set") { lockPatternUtils.isSecure(context.userId) }
        Log.i(TAG, "Device PIN set to $PIN")
    }

    private fun setLockOnPowerButton() {
        instantLockSavedValue = lockPatternUtils.getPowerButtonInstantlyLocks(context.userId)
        lockPatternUtils.setPowerButtonInstantlyLocks(true, context.userId)
    }

    private fun removeScreenLock() {
        var lockCredentialUnset = lockPatternUtils.setLockCredential(
                LockscreenCredential.createNone(),
                LockscreenCredential.createPin(PIN),
                context.userId)
        Log.i(TAG, "Removing screen lock")
        assertWithMessage("Lock screen credential should be unset")
                .that(lockCredentialUnset)
                .isTrue()

        lockPatternUtils.setLockScreenDisabled(true, context.userId)
        wait("screen lock un-set") {
            lockPatternUtils.isLockScreenDisabled(context.userId)
        }
        wait("screen lock insecure") { !lockPatternUtils.isSecure(context.userId) }
    }

    private fun revertLockOnPowerButton() {
        lockPatternUtils.setPowerButtonInstantlyLocks(instantLockSavedValue, context.userId)
    }

    companion object {
        private const val TAG = "ScreenLockRule"
        private const val PIN = "0000"
    }
}
