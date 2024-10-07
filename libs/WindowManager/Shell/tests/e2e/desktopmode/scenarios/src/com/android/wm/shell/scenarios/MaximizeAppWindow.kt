/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.scenarios

import android.app.Instrumentation
import android.tools.NavBar
import android.tools.Rotation
import android.tools.flicker.rules.ChangeDisplayOrientationRule
import android.tools.traces.parsers.WindowManagerStateHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.helpers.DesktopModeAppHelper
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.window.flags.Flags
import com.android.wm.shell.Utils
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

@Ignore("Test Base Class")
abstract class MaximizeAppWindow
constructor(private val rotation: Rotation = Rotation.ROTATION_0, isResizable: Boolean = true) {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tapl = LauncherInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation)
    private val device = UiDevice.getInstance(instrumentation)
    private val testApp = if (isResizable) {
        DesktopModeAppHelper(SimpleAppHelper(instrumentation))
    } else {
        DesktopModeAppHelper(NonResizeableAppHelper(instrumentation))
    }

    @Rule @JvmField val testSetupRule = Utils.testSetupRule(NavBar.MODE_GESTURAL, rotation)

    @Before
    fun setup() {
        Assume.assumeTrue(Flags.enableDesktopWindowingMode() && tapl.isTablet)
        tapl.setEnableRotation(true)
        tapl.setExpectedRotation(rotation.value)
        ChangeDisplayOrientationRule.setRotation(rotation)
        testApp.enterDesktopWithDrag(wmHelper, device)
    }

    @Test
    open fun maximizeAppWindow() {
        testApp.maximiseDesktopApp(wmHelper, device)
    }

    @After
    fun teardown() {
        testApp.exit(wmHelper)
    }
}
