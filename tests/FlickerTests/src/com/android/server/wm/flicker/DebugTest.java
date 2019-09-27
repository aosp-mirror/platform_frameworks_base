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

import android.platform.helpers.IAppHelper;
import android.support.test.uiautomator.UiDevice;
import android.util.Rational;
import android.view.Surface;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.wm.flicker.helpers.ImeAppHelper;
import com.android.server.wm.flicker.helpers.PipAppHelper;

import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Tests to help debug individual transitions, capture video recordings and create test cases.
 */
@LargeTest
@Ignore("Used for debugging transitions used in FlickerTests.")
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DebugTest {
    private IAppHelper testApp = new StandardAppHelper(InstrumentationRegistry.getInstrumentation(),
            "com.android.server.wm.flicker.testapp", "SimpleApp");
    private UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    /**
     * atest FlickerTests:DebugTest#openAppCold
     */
    @Test
    public void openAppCold() {
        CommonTransitions.openAppCold(testApp, uiDevice, Surface.ROTATION_0)
                .recordAllRuns().build().run();
    }

    /**
     * atest FlickerTests:DebugTest#openAppWarm
     */
    @Test
    public void openAppWarm() {
        CommonTransitions.openAppWarm(testApp, uiDevice, Surface.ROTATION_0)
                .recordAllRuns().build().run();
    }

    /**
     * atest FlickerTests:DebugTest#changeOrientationFromNaturalToLeft
     */
    @Test
    public void changeOrientationFromNaturalToLeft() {
        CommonTransitions.changeAppRotation(testApp, uiDevice, Surface.ROTATION_0,
                Surface.ROTATION_270).recordAllRuns().build().run();
    }

    /**
     * atest FlickerTests:DebugTest#closeAppWithBackKey
     */
    @Test
    public void closeAppWithBackKey() {
        CommonTransitions.closeAppWithBackKey(testApp, uiDevice).recordAllRuns().build().run();
    }

    /**
     * atest FlickerTests:DebugTest#closeAppWithHomeKey
     */
    @Test
    public void closeAppWithHomeKey() {
        CommonTransitions.closeAppWithHomeKey(testApp, uiDevice).recordAllRuns().build().run();
    }

    /**
     * atest FlickerTests:DebugTest#openAppToSplitScreen
     */
    @Test
    public void openAppToSplitScreen() {
        CommonTransitions.appToSplitScreen(testApp, uiDevice).includeJankyRuns().recordAllRuns()
                .build().run();
    }

    /**
     * atest FlickerTests:DebugTest#splitScreenToLauncher
     */
    @Test
    public void splitScreenToLauncher() {
        CommonTransitions.splitScreenToLauncher(testApp,
                uiDevice).includeJankyRuns().recordAllRuns()
                .build().run();
    }

    /**
     * atest FlickerTests:DebugTest#resizeSplitScreen
     */
    @Test
    public void resizeSplitScreen() {
        ImeAppHelper bottomApp = new ImeAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.resizeSplitScreen(testApp, bottomApp, uiDevice, Surface.ROTATION_0,
                new Rational(1, 3), new Rational(2, 3))
                .includeJankyRuns().recordEachRun().build().run();
    }

    // IME tests

    /**
     * atest FlickerTests:DebugTest#editTextSetFocus
     */
    @Test
    public void editTextSetFocus() {
        ImeAppHelper testApp = new ImeAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.editTextSetFocus(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns().recordEachRun()
                .build().run();
    }

    /**
     * atest FlickerTests:DebugTest#editTextLoseFocusToHome
     */
    @Test
    public void editTextLoseFocusToHome() {
        ImeAppHelper testApp = new ImeAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.editTextLoseFocusToHome(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns().recordEachRun()
                .build().run();
    }

    /**
     * atest FlickerTests:DebugTest#editTextLoseFocusToApp
     */
    @Test
    public void editTextLoseFocusToApp() {
        ImeAppHelper testApp = new ImeAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.editTextLoseFocusToHome(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns().recordEachRun()
                .build().run();
    }

    // PIP tests

    /**
     * atest FlickerTests:DebugTest#enterPipMode
     */
    @Test
    public void enterPipMode() {
        PipAppHelper testApp = new PipAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.enterPipMode(testApp, uiDevice).includeJankyRuns().recordEachRun()
                .build().run();
    }

    /**
     * atest FlickerTests:DebugTest#exitPipModeToHome
     */
    @Test
    public void exitPipModeToHome() {
        PipAppHelper testApp = new PipAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.exitPipModeToHome(testApp, uiDevice).includeJankyRuns().recordEachRun()
                .build().run();
    }

    /**
     * atest FlickerTests:DebugTest#exitPipModeToApp
     */
    @Test
    public void exitPipModeToApp() {
        PipAppHelper testApp = new PipAppHelper(InstrumentationRegistry.getInstrumentation());
        CommonTransitions.exitPipModeToApp(testApp, uiDevice).includeJankyRuns().recordEachRun()
                .build().run();
    }
}
