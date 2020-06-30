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

package com.android.server.wm.flicker

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.os.SystemClock
import android.platform.helpers.IAppHelper
import android.util.Rational
import android.view.Surface
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.AutomationUtils
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.PipAppHelper

/**
 * Collection of common transitions which can be used to test different apps or scenarios.
 */
internal object CommonTransitions {
    private const val ITERATIONS = 1
    private const val APP_LAUNCH_TIMEOUT: Long = 10000
    private fun setRotation(device: UiDevice, rotation: Int) {
        try {
            when (rotation) {
                Surface.ROTATION_270 -> device.setOrientationLeft()
                Surface.ROTATION_90 -> device.setOrientationRight()
                Surface.ROTATION_0 -> device.setOrientationNatural()
                else -> device.setOrientationNatural()
            }
            // Wait for animation to complete
            SystemClock.sleep(1000)
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param rotation Initial screen rotation
     *
     * @return test tag with pattern <NAME>__<APP>__<ROTATION>
    </ROTATION></APP></NAME> */
    private fun buildTestTag(testName: String, app: IAppHelper, rotation: Int): String {
        return buildTestTag(
                testName, app, rotation, rotation, app2 = null, extraInfo = "")
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param beginRotation Initial screen rotation
     * @param endRotation End screen rotation (if any, otherwise use same as initial)
     *
     * @return test tag with pattern <NAME>__<APP>__<BEGIN_ROTATION>-<END_ROTATION>
    </END_ROTATION></BEGIN_ROTATION></APP></NAME> */
    private fun buildTestTag(
        testName: String,
        app: IAppHelper,
        beginRotation: Int,
        endRotation: Int
    ): String {
        return buildTestTag(
                testName, app, beginRotation, endRotation, app2 = null, extraInfo = "")
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param app2 Second app being launched (if any)
     * @param beginRotation Initial screen rotation
     * @param endRotation End screen rotation (if any, otherwise use same as initial)
     * @param extraInfo Additional information to append to the tag
     *
     * @return test tag with pattern <NAME>__<APP></APP>(S)>__<ROTATION></ROTATION>(S)>[__<EXTRA>]
    </EXTRA></NAME> */
    private fun buildTestTag(
        testName: String,
        app: IAppHelper,
        beginRotation: Int,
        endRotation: Int,
        app2: IAppHelper?,
        extraInfo: String
    ): String {
        val testTag = StringBuilder()
        testTag.append(testName)
                .append("__")
                .append(app.launcherName)
        if (app2 != null) {
            testTag.append("-")
                    .append(app2.launcherName)
        }
        testTag.append("__")
                .append(Surface.rotationToString(beginRotation))
        if (endRotation != beginRotation) {
            testTag.append("-")
                    .append(Surface.rotationToString(endRotation))
        }
        if (extraInfo.isNotEmpty()) {
            testTag.append("__")
                    .append(extraInfo)
        }
        return testTag.toString()
    }

    fun openAppWarm(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("openAppWarm", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBeforeAll { setRotation(device, beginRotation) }
                .runBeforeAll { testApp.open() }
                .runBefore { device.pressHome() }
                .runBefore { device.waitForIdle() }
                .runBefore { setRotation(device, beginRotation) }
                .run { testApp.open() }
                .runAfterAll { testApp.exit() }
                .runAfterAll { AutomationUtils.setDefaultWait() }
                .repeat(ITERATIONS)
    }

    fun closeAppWithBackKey(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("closeAppWithBackKey", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { testApp.open() }
                .runBefore { device.waitForIdle() }
                .run { device.pressBack() }
                .run { device.waitForIdle() }
                .runAfterAll { testApp.exit() }
                .runAfterAll { AutomationUtils.setDefaultWait() }
                .repeat(ITERATIONS)
    }

    fun closeAppWithHomeKey(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("closeAppWithHomeKey", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { testApp.open() }
                .runBefore { device.waitForIdle() }
                .run { device.pressHome() }
                .run { device.waitForIdle() }
                .runAfterAll { testApp.exit() }
                .runAfterAll { AutomationUtils.setDefaultWait() }
                .repeat(ITERATIONS)
    }

    fun openAppCold(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("openAppCold", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { device.pressHome() }
                .runBeforeAll { setRotation(device, beginRotation) }
                .runBefore { testApp.exit() }
                .runBefore { device.waitForIdle() }
                .run { testApp.open() }
                .runAfterAll { testApp.exit() }
                .runAfterAll { setRotation(device, Surface.ROTATION_0) }
                .repeat(ITERATIONS)
    }

    fun changeAppRotation(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int,
        endRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("changeAppRotation", testApp, beginRotation, endRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBeforeAll { testApp.open() }
                .runBefore { setRotation(device, beginRotation) }
                .run { setRotation(device, endRotation) }
                .runAfterAll { testApp.exit() }
                .runAfterAll { setRotation(device, Surface.ROTATION_0) }
                .repeat(ITERATIONS)
    }

    fun changeAppRotation(
        intent: Intent,
        intentId: String,
        context: Context,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int,
        endRotation: Int
    ): TransitionRunner.TransitionBuilder {
        val testTag = "changeAppRotation_" + intentId + "_" +
                Surface.rotationToString(beginRotation) + "_" +
                Surface.rotationToString(endRotation)
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(testTag)
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBeforeAll {
                    context.startActivity(intent)
                    device.wait(Until.hasObject(By.pkg(intent.component?.packageName)
                            .depth(0)), APP_LAUNCH_TIMEOUT)
                }
                .runBefore { setRotation(device, beginRotation) }
                .run { setRotation(device, endRotation) }
                .runAfterAll { AutomationUtils.stopPackage(context, intent.component?.packageName) }
                .runAfterAll { setRotation(device, Surface.ROTATION_0) }
                .repeat(ITERATIONS)
    }

    fun appToSplitScreen(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("appToSplitScreen", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBeforeAll { setRotation(device, beginRotation) }
                .runBefore { testApp.open() }
                .runBefore { device.waitForIdle() }
                .runBefore { SystemClock.sleep(500) }
                .run { AutomationUtils.launchSplitScreen(device) }
                .runAfter { AutomationUtils.exitSplitScreen(device) }
                .runAfterAll { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun splitScreenToLauncher(
        testApp: IAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("splitScreenToLauncher", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { testApp.open() }
                .runBefore { device.waitForIdle() }
                .runBefore { AutomationUtils.launchSplitScreen(device) }
                .run { AutomationUtils.exitSplitScreen(device) }
                .runAfterAll { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun editTextSetFocus(
        testApp: ImeAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("editTextSetFocus", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { device.pressHome() }
                .runBefore { setRotation(device, beginRotation) }
                .runBefore { testApp.open() }
                .run { testApp.openIME(device) }
                .runAfterAll { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun resizeSplitScreen(
        testAppTop: IAppHelper,
        testAppBottom: ImeAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int,
        startRatio: Rational,
        stopRatio: Rational
    ): TransitionRunner.TransitionBuilder {
        val description = (startRatio.toString().replace("/", "-") + "_to_" +
                stopRatio.toString().replace("/", "-"))
        val testTag = buildTestTag("resizeSplitScreen", testAppTop, beginRotation,
                beginRotation, testAppBottom, description)
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(testTag)
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBeforeAll { setRotation(device, beginRotation) }
                .runBeforeAll { AutomationUtils.clearRecents(instrumentation) }
                .runBefore { testAppBottom.open() }
                .runBefore { device.pressHome() }
                .runBefore { testAppTop.open() }
                .runBefore { device.waitForIdle() }
                .runBefore { AutomationUtils.launchSplitScreen(device) }
                .runBefore {
                    val snapshot = device.findObject(
                            By.res(device.launcherPackageName, "snapshot"))
                    snapshot.click()
                }
                .runBefore { testAppBottom.openIME(device) }
                .runBefore { device.pressBack() }
                .runBefore { AutomationUtils.resizeSplitScreen(device, startRatio) }
                .run { AutomationUtils.resizeSplitScreen(device, stopRatio) }
                .runAfter { AutomationUtils.exitSplitScreen(device) }
                .runAfter { device.pressHome() }
                .runAfterAll { testAppTop.exit() }
                .runAfterAll { testAppBottom.exit() }
                .repeat(ITERATIONS)
    }

    fun editTextLoseFocusToHome(
        testApp: ImeAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("editTextLoseFocusToHome", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { device.pressHome() }
                .runBefore { setRotation(device, beginRotation) }
                .runBefore { testApp.open() }
                .runBefore { testApp.openIME(device) }
                .run { device.pressHome() }
                .run { device.waitForIdle() }
                .runAfterAll { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun editTextLoseFocusToApp(
            testApp: ImeAppHelper,
            instrumentation: Instrumentation,
            device: UiDevice,
            beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("editTextLoseFocusToApp", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { device.pressHome() }
                .runBefore { setRotation(device, beginRotation) }
                .runBefore { testApp.open() }
                .runBefore { testApp.openIME(device) }
                .run { device.pressBack() }
                .run { device.waitForIdle() }
                .runAfterAll { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun enterPipMode(
            testApp: PipAppHelper,
            instrumentation: Instrumentation,
            device: UiDevice,
            beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("enterPipMode", testApp, beginRotation))
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { device.pressHome() }
                .runBefore { setRotation(device, beginRotation) }
                .runBefore { testApp.open() }
                .run { testApp.clickEnterPipButton(device) }
                .runAfter { testApp.closePipWindow(device) }
                .runAfterAll { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun exitPipModeToHome(
        testApp: PipAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("exitPipModeToHome", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .runBefore { device.pressHome() }
                .runBefore { setRotation(device, beginRotation) }
                .runBefore { testApp.open() }
                .run { testApp.clickEnterPipButton(device) }
                .run { testApp.closePipWindow(device) }
                .run { device.waitForIdle() }
                .run { testApp.exit() }
                .repeat(ITERATIONS)
    }

    fun exitPipModeToApp(
        testApp: PipAppHelper,
        instrumentation: Instrumentation,
        device: UiDevice,
        beginRotation: Int
    ): TransitionRunner.TransitionBuilder {
        return TransitionRunner.TransitionBuilder(instrumentation)
                .withTag(buildTestTag("exitPipModeToApp", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll { AutomationUtils.wakeUpAndGoToHomeScreen() }
                .run { device.pressHome() }
                .run { setRotation(device, beginRotation) }
                .run { testApp.open() }
                .run { testApp.clickEnterPipButton(device) }
                .run { AutomationUtils.expandPipWindow(device) }
                .run { device.waitForIdle() }
                .run { testApp.exit() }
                .repeat(ITERATIONS)
    }
}