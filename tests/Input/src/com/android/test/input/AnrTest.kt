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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.graphics.Rect
import android.os.Build
import android.os.IInputConstants.UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS
import android.os.SystemClock
import android.provider.Settings
import android.provider.Settings.Global.HIDE_ERROR_DIALOGS
import android.testing.PollingCheck
import android.view.InputDevice
import android.view.MotionEvent

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        private const val ALL_PIDS = 0
        private const val NO_MAX = 0
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var hideErrorDialogs = 0
    private lateinit var PACKAGE_NAME: String
    private val DISPATCHING_TIMEOUT = (UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS *
            Build.HW_TIMEOUT_MULTIPLIER)

    @Before
    fun setUp() {
        val contentResolver = instrumentation.targetContext.contentResolver
        hideErrorDialogs = Settings.Global.getInt(contentResolver, HIDE_ERROR_DIALOGS, 0)
        Settings.Global.putInt(contentResolver, HIDE_ERROR_DIALOGS, 0)
        PACKAGE_NAME = UnresponsiveGestureMonitorActivity::class.java.getPackage()!!.getName()
    }

    @After
    fun tearDown() {
        val contentResolver = instrumentation.targetContext.contentResolver
        Settings.Global.putInt(contentResolver, HIDE_ERROR_DIALOGS, hideErrorDialogs)
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
        val uiDevice: UiDevice = UiDevice.getInstance(instrumentation)
        val closeAppButton: UiObject2? =
                uiDevice.wait(Until.findObject(By.res("android:id/aerr_close")), 20000)
        if (closeAppButton == null) {
            fail("Could not find anr dialog")
            return
        }
        val initialReasons = getExitReasons()
        closeAppButton.click()
        /**
         * We must wait for the app to be fully closed before exiting this test. This is because
         * another test may again invoke 'am start' for the same activity.
         * If the 1st process that got ANRd isn't killed by the time second 'am start' runs,
         * the killing logic will apply to the newly launched 'am start' instance, and the second
         * test will fail because the unresponsive activity will never be launched.
         */
        waitForNewExitReason(initialReasons[0].timestamp)
    }

    private fun clickWaitOnAnrDialog() {
        // Find anr dialog and tap on wait
        val uiDevice: UiDevice = UiDevice.getInstance(instrumentation)
        val waitButton: UiObject2? =
                uiDevice.wait(Until.findObject(By.res("android:id/aerr_wait")), 20000)
        if (waitButton == null) {
            fail("Could not find anr dialog/wait button")
            return
        }
        waitButton.click()
    }

    private fun getExitReasons(): List<ApplicationExitInfo> {
        lateinit var infos: List<ApplicationExitInfo>
        instrumentation.runOnMainSync {
            val am = instrumentation.getContext().getSystemService(ActivityManager::class.java)!!
            infos = am.getHistoricalProcessExitReasons(PACKAGE_NAME, ALL_PIDS, NO_MAX)
        }
        return infos
    }

    private fun waitForNewExitReason(previousExitTimestamp: Long) {
        PollingCheck.waitFor {
            getExitReasons()[0].timestamp > previousExitTimestamp
        }
        val reasons = getExitReasons()
        assertTrue(reasons[0].timestamp > previousExitTimestamp)
        assertEquals(ApplicationExitInfo.REASON_ANR, reasons[0].reason)
    }

    private fun triggerAnr() {
        startUnresponsiveActivity()
        val uiDevice: UiDevice = UiDevice.getInstance(instrumentation)
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

        instrumentation.uiAutomation.injectInputEvent(downEvent, false /* sync*/)

        SystemClock.sleep(DISPATCHING_TIMEOUT.toLong()) // default ANR timeout for gesture monitors
    }

    private fun startUnresponsiveActivity() {
        val flags = " -W -n "
        val startCmd = "am start $flags $PACKAGE_NAME/.UnresponsiveGestureMonitorActivity"
        instrumentation.uiAutomation.executeShellCommand(startCmd)
    }
}
