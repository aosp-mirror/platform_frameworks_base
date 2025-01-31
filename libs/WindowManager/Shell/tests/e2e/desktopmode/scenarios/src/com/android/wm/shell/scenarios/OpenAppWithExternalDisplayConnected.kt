/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.tools.NavBar
import android.tools.Rotation
import android.tools.flicker.rules.ChangeDisplayOrientationRule
import android.tools.traces.parsers.WindowManagerStateHelper
import android.util.DisplayMetrics
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.helpers.DesktopModeAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.window.flags.Flags
import com.android.wm.shell.ExtendedDisplaySettingsSession
import com.android.wm.shell.Utils
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Base scenario test for launching an app in desktop mode by default when an external display is
 * connected.
 */
@Ignore("Test Base Class")
abstract class OpenAppWithExternalDisplayConnected
constructor(private val rotation: Rotation = Rotation.ROTATION_0) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tapl = LauncherInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation)
    private val device = UiDevice.getInstance(instrumentation)
    private val testApp = DesktopModeAppHelper(SimpleAppHelper(instrumentation))
    private val displayManager =
        instrumentation.getContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var virtualDisplay: VirtualDisplay? = null

    private val extendedDisplaySettingsSession =
        ExtendedDisplaySettingsSession(instrumentation.context.contentResolver)

    @Rule @JvmField val testSetupRule = Utils.testSetupRule(NavBar.MODE_GESTURAL, rotation)

    @Before
    fun setup() {
        Assume.assumeTrue(Flags.enableDesktopWindowingMode() && tapl.isTablet)
        Assume.assumeTrue(Flags.enableDisplayWindowingModeSwitching())
        tapl.setEnableRotation(true)
        tapl.setExpectedRotation(rotation.value)
        ChangeDisplayOrientationRule.setRotation(rotation)
        extendedDisplaySettingsSession.open()
        virtualDisplay = displayManager.createVirtualDisplay(
            /* displayName= */ DISPLAY_NAME,
            /* width= */ DISPLAY_WIDTH,
            /* height= */ DISPLAY_HEIGHT,
            /* densityDpi= */ DisplayMetrics.DENSITY_DEFAULT,
            /* surface= */ null,
            /* flags= */ DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
        )
    }

    @Test
    open fun openAppWithExternalDisplayConnected() {
        testApp.open()
    }

    @After
    fun teardown() {
        testApp.exit(wmHelper)
        virtualDisplay?.let {
            it.release()
        }
        extendedDisplaySettingsSession.close()
    }

    companion object {
        const val DISPLAY_NAME = "testVirtualDisplay"
        const val DISPLAY_HEIGHT = 600
        const val DISPLAY_WIDTH = 800
    }
}
