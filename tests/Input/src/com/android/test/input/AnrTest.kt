/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.test.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.MediumTest

import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.provider.Settings.Global.HIDE_ERROR_DIALOGS
import android.support.test.uiautomator.By
import android.support.test.uiautomator.UiDevice
import android.support.test.uiautomator.UiObject2
import android.support.test.uiautomator.Until
import android.view.InputDevice
import android.view.MotionEvent

import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test makes sure that an unresponsive gesture monitor gets an ANR.
 *
 * The gesture monitor must be registered from a different process than the instrumented process.
 * Otherwise, when the test runs, you will get:
 * Test failed to run to completion.
 * Reason: 'Instrumentation run failed due to 'keyDispatchingTimedOut''.
 * Check device logcat for details
 * RUNNER ERROR: Instrumentation run failed due to 'keyDispatchingTimedOut'
 */
@MediumTest
@RunWith(AndroidJUnit4::class)
class AnrTest {
    companion object {
        private const val TAG = "AnrTest"
    }

    val mInstrumentation = InstrumentationRegistry.getInstrumentation()
    var mHideErrorDialogs = 0

    @Before
    fun setUp() {
        val contentResolver = mInstrumentation.targetContext.contentResolver
        mHideErrorDialogs = Settings.Global.getInt(contentResolver, HIDE_ERROR_DIALOGS, 0)
        Settings.Global.putInt(contentResolver, HIDE_ERROR_DIALOGS, 0)
    }

    @After
    fun tearDown() {
        val contentResolver = mInstrumentation.targetContext.contentResolver
        Settings.Global.putInt(contentResolver, HIDE_ERROR_DIALOGS, mHideErrorDialogs)
    }

    @Test
    fun testGestureMonitorAnr_Close() {
        triggerAnr()
        clickCloseAppOnAnrDialog()
    }

    @Test
    fun testGestureMonitorAnr_Wait() {
        triggerAnr()
        clickWaitOnAnrDialog()
        SystemClock.sleep(500) // Wait at least 500ms after tapping on wait
        // ANR dialog should reappear after a delay - find the close button on it to verify
        clickCloseAppOnAnrDialog()
    }

    private fun clickCloseAppOnAnrDialog() {
        // Find anr dialog and kill app
        val uiDevice: UiDevice = UiDevice.getInstance(mInstrumentation)
        val closeAppButton: UiObject2? =
                uiDevice.wait(Until.findObject(By.res("android:id/aerr_close")), 20000)
        if (closeAppButton == null) {
            fail("Could not find anr dialog")
            return
        }
        closeAppButton.click()
    }

    private fun clickWaitOnAnrDialog() {
        // Find anr dialog and tap on wait
        val uiDevice: UiDevice = UiDevice.getInstance(mInstrumentation)
        val waitButton: UiObject2? =
                uiDevice.wait(Until.findObject(By.res("android:id/aerr_wait")), 20000)
        if (waitButton == null) {
            fail("Could not find anr dialog/wait button")
            return
        }
        waitButton.click()
    }

    private fun triggerAnr() {
        startUnresponsiveActivity()
        val uiDevice: UiDevice = UiDevice.getInstance(mInstrumentation)
        val obj: UiObject2? = uiDevice.wait(Until.findObject(
                By.text("Unresponsive gesture monitor")), 10000)

        if (obj == null) {
            fail("Could not find unresponsive activity")
            return
        }

        val rect: Rect = obj.visibleBounds
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, rect.left.toFloat(), rect.top.toFloat(), 0 /* metaState */)
        downEvent.source = InputDevice.SOURCE_TOUCHSCREEN

        mInstrumentation.uiAutomation.injectInputEvent(downEvent, false /* sync*/)

        // Todo: replace using timeout from android.hardware.input.IInputManager
        SystemClock.sleep(5000) // default ANR timeout for gesture monitors
    }

    private fun startUnresponsiveActivity() {
        val flags = " -W -n "
        val startCmd = "am start $flags com.android.test.input/.UnresponsiveGestureMonitorActivity"
        mInstrumentation.uiAutomation.executeShellCommand(startCmd)
    }
}