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

import static com.android.server.wm.flicker.AutomationUtils.clearRecents;
import static com.android.server.wm.flicker.AutomationUtils.closePipWindow;
import static com.android.server.wm.flicker.AutomationUtils.exitSplitScreen;
import static com.android.server.wm.flicker.AutomationUtils.expandPipWindow;
import static com.android.server.wm.flicker.AutomationUtils.launchSplitScreen;
import static com.android.server.wm.flicker.AutomationUtils.stopPackage;

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

import androidx.test.InstrumentationRegistry;

import com.android.server.wm.flicker.TransitionRunner.TransitionBuilder;

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
            sleep(3000);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private static void clickEditTextWidget(UiDevice device, IAppHelper testApp) {
        UiObject2 editText = device.findObject(By.res(testApp.getPackage(), "plain_text_input"));
        editText.click();
        sleep(500);
    }

    private static void clickEnterPipButton(UiDevice device, IAppHelper testApp) {
        UiObject2 enterPipButton = device.findObject(By.res(testApp.getPackage(), "enter_pip"));
        enterPipButton.click();
        sleep(500);
    }

    static TransitionBuilder openAppWarm(IAppHelper testApp, UiDevice
            device) {
        return TransitionRunner.newBuilder()
                .withTag("OpenAppWarm_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(testApp::open)
                .runBefore(device::pressHome)
                .runBefore(device::waitForIdle)
                .run(testApp::open)
                .runAfterAll(testApp::exit)
                .runAfterAll(AutomationUtils::setDefaultWait)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder closeAppWithBackKey(IAppHelper testApp, UiDevice
            device) {
        return TransitionRunner.newBuilder()
                .withTag("closeAppWithBackKey_" + testApp.getLauncherName())
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
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .run(device::pressHome)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .runAfterAll(AutomationUtils::setDefaultWait)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder getOpenAppCold(IAppHelper testApp,
            UiDevice device) {
        return TransitionRunner.newBuilder()
                .withTag("OpenAppCold_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::exit)
                .runBefore(device::waitForIdle)
                .run(testApp::open)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder changeAppRotation(IAppHelper testApp, UiDevice
            device, int beginRotation, int endRotation) {
        return TransitionRunner.newBuilder()
                .withTag("changeAppRotation_" + testApp.getLauncherName()
                        + rotationToString(beginRotation) + "_" +
                        rotationToString(endRotation))
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
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(testApp::open)
                .runBefore(device::waitForIdle)
                .runBefore(() -> launchSplitScreen(device))
                .run(() -> exitSplitScreen(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextSetFocus(UiDevice device) {
        IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "ImeApp");
        return TransitionRunner.newBuilder()
                .withTag("editTextSetFocus_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .run(() -> clickEditTextWidget(device, testApp))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder resizeSplitScreen(IAppHelper testAppTop, IAppHelper testAppBottom,
            UiDevice device, Rational startRatio, Rational stopRatio) {
        String testTag = "resizeSplitScreen_" + testAppTop.getLauncherName() + "_" +
                testAppBottom.getLauncherName() + "_" +
                startRatio.toString().replace("/", ":") + "_to_" +
                stopRatio.toString().replace("/", ":");
        return TransitionRunner.newBuilder()
                .withTag(testTag)
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBeforeAll(() -> clearRecents(device))
                .runBefore(testAppBottom::open)
                .runBefore(device::pressHome)
                .runBefore(testAppTop::open)
                .runBefore(device::waitForIdle)
                .runBefore(() -> launchSplitScreen(device))
                .runBefore(() -> {
                    UiObject2 snapshot = device.findObject(
                            By.res("com.google.android.apps.nexuslauncher", "snapshot"));
                    snapshot.click();
                })
                .runBefore(() -> AutomationUtils.resizeSplitScreen(device, startRatio))
                .run(() -> AutomationUtils.resizeSplitScreen(device, stopRatio))
                .runAfter(() -> exitSplitScreen(device))
                .runAfter(device::pressHome)
                .runAfterAll(testAppTop::exit)
                .runAfterAll(testAppBottom::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextLoseFocusToHome(UiDevice device) {
        IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "ImeApp");
        return TransitionRunner.newBuilder()
                .withTag("editTextLoseFocusToHome_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .runBefore(() -> clickEditTextWidget(device, testApp))
                .run(device::pressHome)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder editTextLoseFocusToApp(UiDevice device) {
        IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "ImeApp");
        return TransitionRunner.newBuilder()
                .withTag("editTextLoseFocusToApp_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .runBefore(() -> clickEditTextWidget(device, testApp))
                .run(device::pressBack)
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder enterPipMode(UiDevice device) {
        IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "PipApp");
        return TransitionRunner.newBuilder()
                .withTag("enterPipMode_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .run(() -> clickEnterPipButton(device, testApp))
                .runAfter(() -> closePipWindow(device))
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder exitPipModeToHome(UiDevice device) {
        IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "PipApp");
        return TransitionRunner.newBuilder()
                .withTag("exitPipModeToHome_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .runBefore(() -> clickEnterPipButton(device, testApp))
                .run(() -> closePipWindow(device))
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }

    static TransitionBuilder exitPipModeToApp(UiDevice device) {
        IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
                "com.android.server.wm.flicker.testapp", "PipApp");
        return TransitionRunner.newBuilder()
                .withTag("exitPipModeToApp_" + testApp.getLauncherName())
                .runBeforeAll(AutomationUtils::wakeUpAndGoToHomeScreen)
                .runBefore(device::pressHome)
                .runBefore(testApp::open)
                .runBefore(() -> clickEnterPipButton(device, testApp))
                .run(() -> expandPipWindow(device))
                .run(device::waitForIdle)
                .runAfterAll(testApp::exit)
                .repeat(ITERATIONS);
    }
}