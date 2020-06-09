/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker;

import static android.os.SystemClock.sleep;
import static android.view.Surface.rotationToString;

import static com.android.server.wm.flicker.helpers.AutomationUtils.clearRecents;
import static com.android.server.wm.flicker.helpers.AutomationUtils.exitSplitScreen;
import static com.android.server.wm.flicker.helpers.AutomationUtils.expandPipWindow;
import static com.android.server.wm.flicker.helpers.AutomationUtils.launchSplitScreen;
import static com.android.server.wm.flicker.helpers.AutomationUtils.stopPackage;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.platform.helpers.IAppHelper;
import android.util.Rational;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.server.wm.flicker.TransitionRunner.TransitionBuilder;
import com.android.server.wm.flicker.helpers.AutomationUtils;
import com.android.server.wm.flicker.helpers.ImeAppHelper;
import com.android.server.wm.flicker.helpers.PipAppHelper;

/**
 * Collection of common transitions which can be used to test different apps or scenarios.
 */
class CommonTransitions {

    public static final int ITERATIONS = 1;
    private static final String TAG = "FLICKER";
    private static final long APP_LAUNCH_TIMEOUT = 10000;

    private static void setRotation(UiDevice device, int rotation) {
        try {
            switch (rotation) {
                case Surface.ROTATION_270:
                    device.setOrientationLeft();
                    break;

                case Surface.ROTATION_90:
                    device.setOrientationRight();
                    break;

                case Surface.ROTATION_0:
                default:
                    device.setOrientationNatural();
            }
            // Wait for animation to complete
            sleep(1000);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param rotation Initial screen rotation
     *
     * @return test tag with pattern <NAME>__<APP>__<ROTATION>
     */
    private static String buildTestTag(String testName, IAppHelper app, int rotation) {
        return buildTestTag(
                testName, app, /* app2 */ null, rotation, rotation, /* description */ "");
    }

    /**
     * Build a test tag for the test
     * @param testName Name of the transition(s) being tested
     * @param app App being launcher
     * @param beginRotation Initial screen rotation
     * @param endRotation End screen rotation (if any, otherwise use same as initial)
     *
     * @return test tag with pattern <NAME>__<APP>__<BEGIN_ROTATION>-<END_ROTATION>
     */
    private static String buildTestTag(String testName, IAppHelper app, int beginRotation,
            int endRotation) {
        return buildTestTag(
                testName, app, /* app2 */ null, beginRotation, endRotation, /* description */ "");
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
     * @return test tag with pattern <NAME>__<APP(S)>__<ROTATION(S)>[__<EXTRA>]
     */
    private static String buildTestTag(String testName, IAppHelper app, @Nullable IAppHelper app2,
            int beginRotation, int endRotation, String extraInfo) {
        StringBuilder testTag = new StringBuilder();
        testTag.append(testName)
                .append("__")
                .append(app.getLauncherName());

        if (app2 != null) {
            testTag.append("-")
                    .append(app2.getLauncherName());
        }

        testTag.append("__")
                .append(rotationToString(beginRotation));

        if (endRotation != beginRotation) {
            testTag.append("-")
                    .append(rotationToString(endRotation));
        }

        if (!extraInfo.isEmpty()) {
            testTag.append("__")
                    .append(extraInfo);
        }

        return testTag.toString();
    }

    static TransitionBuilder openAppWarm(IAppHelper testApp, UiDevice
            device, int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("openAppWarm", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> setRotation(device, beginRotation))
                .runBeforeAll(testApp::open)
                .runBefore(device::pressHome)
                .runBefore(device::waitForIdle)
                .runBefore(() -> setRotation(device, beginRotation))
                .run(testApp::open)
                .runAfterAll(testApp::exit)
                .runAfterAll(AutomationUtils::setDefaultWait)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder closeAppWithBackKey(IAppHelper testApp, UiDevice
            device, int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("closeAppWithBackKey", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .run(device::pressBack)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .runAfterAll(AutomationUtils::setDefaultWait)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder closeAppWithHomeKey(IAppHelper testApp, UiDevice
            device, int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("closeAppWithHomeKey", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .run(device::pressHome)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .runAfterAll(AutomationUtils::setDefaultWait)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder openAppCold(IAppHelper testApp,
            UiDevice device, int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("openAppCold", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBeforeAll(() -> setRotation(device, beginRotation))
                .runBefore(testApp::exit)
                .runBefore(device::waitForIdle)
                .run(testApp::open)
                .runAfterAll(testApp::exit)
                .runAfterAll(() -> setRotation(device, Surface.ROTATION_0))
                .repeat(ITERATIONS);
    }

    static TransitionBuilder changeAppRotation(IAppHelper testApp, UiDevice
            device, int beginRotation, int endRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("changeAppRotation", testApp, beginRotation, endRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(testApp::open)
                .runBefore(() -> setRotation(device, beginRotation))
                .run(() -> setRotation(device, endRotation))
                .runAfterAll(testApp::exit)
                .runAfterAll(() -> setRotation(device, Surface.ROTATION_0))
                .repeat(ITERATIONS);
    }

    static TransitionBuilder changeAppRotation(Intent intent, String intentId, Context context,
            UiDevice device, int beginRotation, int endRotation) {
        final String testTag = "changeAppRotation_" + intentId + "_" +
                rotationToString(beginRotation) + "_" + rotationToString(endRotation);
        return TransitionRunner.newBuilder()
                .withTag(testTag)
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> {
                            context.startActivity(intent);
                            device.wait(Until.hasObject(By.pkg(intent.getComponent()
                                        .getPackageName()).depth(0)), APP_LAUNCH_TIMEOUT);
                        }
                )
                .runBefore(() -> setRotation(device, beginRotation))
                .run(() -> setRotation(device, endRotation))
                .runAfterAll(() -> stopPackage(context, intent.getComponent().getPackageName()))
                .runAfterAll(() -> setRotation(device, Surface.ROTATION_0))
                .repeat(ITERATIONS);
    }

    static TransitionBuilder appToSplitScreen(IAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("appToSplitScreen", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .runBefore(() -> sleep(500))
                .run(() -> launchSplitScreen(device))
                .runAfter(() -> exitSplitScreen(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder splitScreenToLauncher(IAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("splitScreenToLauncher", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .runBefore(() -> launchSplitScreen(device))
                .run(() -> exitSplitScreen(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextSetFocus(ImeAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("editTextSetFocus", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .run(() -> testApp.openIME(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder resizeSplitScreen(Instrumentation instr, IAppHelper testAppTop,
            ImeAppHelper testAppBottom, UiDevice device, int beginRotation, Rational startRatio,
            Rational stopRatio) {
        String description = startRatio.toString().replace("/", "-") + "_to_"
                + stopRatio.toString().replace("/", "-");
        String testTag = buildTestTag("resizeSplitScreen", testAppTop, testAppBottom,
                beginRotation, beginRotation, description);
        return TransitionRunner.newBuilder()
                .withTag(testTag)
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> setRotation(device, beginRotation))
                .runBeforeAll(() -> clearRecents(instr))
                .runBefore(testAppBottom::open)
                .runBefore(device::pressHome)
                .runBefore(testAppTop::open)
                .runBefore(device::waitForIdle)
                .runBefore(() -> launchSplitScreen(device))
                .runBefore(() -> {
                    UiObject2 snapshot = device.findObject(
                            By.res(device.getLauncherPackageName(), "snapshot"));
                    snapshot.click();
                })
                .runBefore(() -> testAppBottom.openIME(device))
                .runBefore(device::pressBack)
                .runBefore(() -> AutomationUtils.resizeSplitScreen(device, startRatio))
                .run(() -> AutomationUtils.resizeSplitScreen(device, stopRatio))
                .runAfter(() -> exitSplitScreen(device))
                .runAfter(device::pressHome)
                .runAfterAll(testAppTop::exit)
                .runAfterAll(testAppBottom::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextLoseFocusToHome(ImeAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("editTextLoseFocusToHome", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .runBefore(() -> testApp.openIME(device))
                .run(device::pressHome)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextLoseFocusToApp(ImeAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("editTextLoseFocusToApp", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .runBefore(() -> testApp.openIME(device))
                .run(device::pressBack)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder enterPipMode(PipAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("enterPipMode", testApp, beginRotation))
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .run(() -> testApp.clickEnterPipButton(device))
                .runAfter(() -> testApp.closePipWindow(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder exitPipModeToHome(PipAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("exitPipModeToHome", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .run(() -> testApp.clickEnterPipButton(device))
                .run(() -> testApp.closePipWindow(device))
                .run(device::waitForIdle)
                .run(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder exitPipModeToApp(PipAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag(buildTestTag("exitPipModeToApp", testApp, beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .run(device::pressHome)
                .run(() -> setRotation(device, beginRotation))
                .run(testApp::open)
                .run(() -> testApp.clickEnterPipButton(device))
                .run(() -> expandPipWindow(device))
                .run(device::waitForIdle)
                .run(testApp::exit)
                .repeat(ITERATIONS);
    }
}
