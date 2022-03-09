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
            verifyKeyguardDismissed()
            setScreenLock()
            setLockOnPowerButton()

            try {
                base.evaluate()
            } finally {
                removeScreenLock()
                revertLockOnPowerButton()
                verifyKeyguardDismissed()
            }
        }
    }

    private fun verifyNoScreenLockAlreadySet() {
        assertWithMessage("Screen Lock must not already be set on device")
                .that(lockPatternUtils.isSecure(context.userId))
                .isFalse()
    }

    private fun verifyKeyguardDismissed() {
        val maxWaits = 30
        var waitCount = 0

        while (windowManager.isKeyguardLocked && waitCount < maxWaits) {
            Log.i(TAG, "Keyguard still showing; attempting to dismiss and wait 50ms ($waitCount)")
            windowManager.dismissKeyguard(null, null)
            Thread.sleep(50)
            waitCount++
        }
        assertWithMessage("Keyguard should be unlocked")
                .that(windowManager.isKeyguardLocked)
                .isFalse()
    }

    private fun setScreenLock() {
        lockPatternUtils.setLockCredential(
                LockscreenCredential.createPin(PIN),
                LockscreenCredential.createNone(),
                context.userId
        )
        assertWithMessage("Screen Lock should now be set")
                .that(lockPatternUtils.isSecure(context.userId))
                .isTrue()
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
        Thread.sleep(100)
        assertWithMessage("Lock screen credential should be unset")
                .that(lockCredentialUnset)
                .isTrue()

        lockPatternUtils.setLockScreenDisabled(true, context.userId)
        Thread.sleep(100)
        assertWithMessage("Lockscreen needs to be disabled")
                .that(lockPatternUtils.isLockScreenDisabled(context.userId))
                .isTrue()

        // this is here because somehow it helps the keyguard not get stuck
        uiDevice.sleep()
        Thread.sleep(500) // delay added to avoid initiating camera by double clicking power
        uiDevice.wakeUp()
    }

    private fun revertLockOnPowerButton() {
        lockPatternUtils.setPowerButtonInstantlyLocks(instantLockSavedValue, context.userId)
    }

    companion object {
        private const val TAG = "ScreenLockRule"
        private const val PIN = "0000"
    }
}
