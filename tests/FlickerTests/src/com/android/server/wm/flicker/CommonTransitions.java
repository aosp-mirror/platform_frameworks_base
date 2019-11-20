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
import static com.android.server.wm.flicker.helpers.AutomationUtils.closePipWindow;
import static com.android.server.wm.flicker.helpers.AutomationUtils.exitSplitScreen;
import static com.android.server.wm.flicker.helpers.AutomationUtils.expandPipWindow;
import static com.android.server.wm.flicker.helpers.AutomationUtils.launchSplitScreen;
import static com.android.server.wm.flicker.helpers.AutomationUtils.stopPackage;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.platform.helpers.IAppHelper;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Rational;
import android.view.Surface;

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

    static TransitionBuilder openAppWarm(IAppHelper testApp, UiDevice
            device, int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag("OpenAppWarm_" + testApp.getLauncherName()
                        + rotationToString(beginRotation))
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
            device) {
        return TransitionRunner.newBuilder()
                .withTag("closeAppWithBackKey_" + testApp.getLauncherName())
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
            device) {
        return TransitionRunner.newBuilder()
                .withTag("closeAppWithHomeKey_" + testApp.getLauncherName())
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
                .withTag("OpenAppCold_" + testApp.getLauncherName()
                        + rotationToString(beginRotation))
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
                .withTag("changeAppRotation_" + testApp.getLauncherName()
                        + rotationToString(beginRotation) + "_" +
                        rotationToString(endRotation))
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
            UiDevice
                    device, int beginRotation, int endRotation) {
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

    static TransitionBuilder appToSplitScreen(IAppHelper testApp, UiDevice device) {
        return TransitionRunner.newBuilder()
                .withTag("appToSplitScreen_" + testApp.getLauncherName())
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .runBefore(() -> sleep(500))
                .run(() -> launchSplitScreen(device))
                .runAfter(() -> exitSplitScreen(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder splitScreenToLauncher(IAppHelper testApp, UiDevice device) {
        return TransitionRunner.newBuilder()
                .withTag("splitScreenToLauncher_" + testApp.getLauncherName())
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
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
                .withTag("editTextSetFocus_" + testApp.getLauncherName()
                        + rotationToString(beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .run(() -> testApp.clickEditTextWidget(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder resizeSplitScreen(IAppHelper testAppTop, ImeAppHelper testAppBottom,
            UiDevice device, int beginRotation, Rational startRatio, Rational stopRatio) {
        String testTag = "resizeSplitScreen_" + testAppTop.getLauncherName() + "_"
                + testAppBottom.getLauncherName() + "_"
                + startRatio.toString().replace("/", ":") + "_to_"
                + stopRatio.toString().replace("/", ":") + "_"
                + rotationToString(beginRotation);
        return TransitionRunner.newBuilder()
                .withTag(testTag)
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> setRotation(device, beginRotation))
                .runBeforeAll(() -> clearRecents(device))
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
                .runBefore(() -> testAppBottom.clickEditTextWidget(device))
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
                .withTag("editTextLoseFocusToHome_" + testApp.getLauncherName()
                        + rotationToString(beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .runBefore(() -> testApp.clickEditTextWidget(device))
                .run(device::pressHome)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextLoseFocusToApp(ImeAppHelper testApp, UiDevice device,
            int beginRotation) {
        return TransitionRunner.newBuilder()
                .withTag("editTextLoseFocusToApp_" + testApp.getLauncherName()
                        + rotationToString(beginRotation))
                .recordAllRuns()
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(() -> setRotation(device, beginRotation))
                .runBefore(testApp::open)
                .runBefore(() -> testApp.clickEditTextWidget(device))
                .run(device::pressBack)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder enterPipMode(PipAppHelper testApp, UiDevice device) {
        return TransitionRunner.newBuilder()
                .withTag("enterPipMode_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .run(() -> testApp.clickEnterPipButton(device))
                .runAfter(() -> closePipWindow(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder exitPipModeToHome(PipAppHelper testApp, UiDevice device) {
        return TransitionRunner.newBuilder()
                .withTag("exitPipModeToHome_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .runBefore(() -> testApp.clickEnterPipButton(device))
                .run(() -> closePipWindow(device))
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder exitPipModeToApp(PipAppHelper testApp, UiDevice device) {
        return TransitionRunner.newBuilder()
                .withTag("exitPipModeToApp_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .runBefore(() -> testApp.clickEnterPipButton(device))
                .run(() -> expandPipWindow(device))
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }
}
