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

import android.platform.helpers.IAppHelper
import android.util.Rational
import android.view.Surface
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.PipAppHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Tests to help debug individual transitions, capture video recordings and create test cases.
 */
@LargeTest
@Ignore("Used for debugging transitions used in FlickerTests.")
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DebugTest {
    private val testApp: IAppHelper = StandardAppHelper(
            InstrumentationRegistry.getInstrumentation(),
            "com.android.server.wm.flicker.testapp", "SimpleApp")
    private val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * atest FlickerTests:DebugTest#openAppCold
     */
    @Test
    fun openAppCold() {
        CommonTransitions.openAppCold(testApp, uiDevice, Surface.ROTATION_0)
                .recordAllRuns().build().run()
    }

    /**
     * atest FlickerTests:DebugTest#openAppWarm
     */
    @Test
    fun openAppWarm() {
        CommonTransitions.openAppWarm(testApp, uiDevice, Surface.ROTATION_0)
                .recordAllRuns().build().run()
    }

    /**
     * atest FlickerTests:DebugTest#changeOrientationFromNaturalToLeft
     */
    @Test
    fun changeOrientationFromNaturalToLeft() {
        CommonTransitions.changeAppRotation(testApp, uiDevice, Surface.ROTATION_0,
                Surface.ROTATION_270).recordAllRuns().build().run()
    }

    /**
     * atest FlickerTests:DebugTest#closeAppWithBackKey
     */
    @Test
    fun closeAppWithBackKey() {
        CommonTransitions.closeAppWithBackKey(testApp, uiDevice, Surface.ROTATION_0)
                .recordAllRuns().build().run()
    }

    /**
     * atest FlickerTests:DebugTest#closeAppWithHomeKey
     */
    @Test
    fun closeAppWithHomeKey() {
        CommonTransitions.closeAppWithHomeKey(testApp, uiDevice, Surface.ROTATION_0)
                .recordAllRuns().build().run()
    }

    /**
     * atest FlickerTests:DebugTest#openAppToSplitScreen
     */
    @Test
    fun openAppToSplitScreen() {
        CommonTransitions.appToSplitScreen(testApp, uiDevice,
                Surface.ROTATION_0).includeJankyRuns().recordAllRuns()
                .build().run()
    }

    /**
     * atest FlickerTests:DebugTest#splitScreenToLauncher
     */
    @Test
    fun splitScreenToLauncher() {
        CommonTransitions.splitScreenToLauncher(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns().recordAllRuns().build().run()
    }

    /**
     * atest FlickerTests:DebugTest#resizeSplitScreen
     */
    @Test
    fun resizeSplitScreen() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val bottomApp = ImeAppHelper(instr)
        CommonTransitions.resizeSplitScreen(instr, testApp, bottomApp, uiDevice, Surface.ROTATION_0,
                Rational(1, 3), Rational(2, 3))
                .includeJankyRuns().build().run()
    }
    // IME tests
    /**
     * atest FlickerTests:DebugTest#editTextSetFocus
     */
    @Test
    fun editTextSetFocus() {
        val testApp = ImeAppHelper(InstrumentationRegistry.getInstrumentation())
        CommonTransitions.editTextSetFocus(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns()
                .build().run()
    }

    /**
     * atest FlickerTests:DebugTest#editTextLoseFocusToHome
     */
    @Test
    fun editTextLoseFocusToHome() {
        val testApp = ImeAppHelper(InstrumentationRegistry.getInstrumentation())
        CommonTransitions.editTextLoseFocusToHome(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns()
                .build().run()
    }

    /**
     * atest FlickerTests:DebugTest#editTextLoseFocusToApp
     */
    @Test
    fun editTextLoseFocusToApp() {
        val testApp = ImeAppHelper(InstrumentationRegistry.getInstrumentation())
        CommonTransitions.editTextLoseFocusToHome(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns()
                .build().run()
    }
    // PIP tests
    /**
     * atest FlickerTests:DebugTest#enterPipMode
     */
    @Test
    fun enterPipMode() {
        val testApp = PipAppHelper(InstrumentationRegistry.getInstrumentation())
        CommonTransitions.enterPipMode(testApp, uiDevice, Surface.ROTATION_0).includeJankyRuns()
                .build().run()
    }

    /**
     * atest FlickerTests:DebugTest#exitPipModeToHome
     */
    @Test
    fun exitPipModeToHome() {
        val testApp = PipAppHelper(InstrumentationRegistry.getInstrumentation())
        CommonTransitions.exitPipModeToHome(testApp, uiDevice, Surface.ROTATION_0)
                .includeJankyRuns()
                .build().run()
    }

    /**
     * atest FlickerTests:DebugTest#exitPipModeToApp
     */
    @Test
    fun exitPipModeToApp() {
        val testApp = PipAppHelper(InstrumentationRegistry.getInstrumentation())
        CommonTransitions.exitPipModeToApp(testApp, uiDevice, Surface.ROTATION_0).includeJankyRuns()
                .build().run()
    }
}
