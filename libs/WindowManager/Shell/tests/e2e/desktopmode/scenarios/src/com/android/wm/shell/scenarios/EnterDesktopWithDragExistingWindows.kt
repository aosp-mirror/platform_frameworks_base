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

import android.tools.NavBar
import android.tools.Rotation
import android.tools.flicker.rules.ChangeDisplayOrientationRule
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.window.flags.Flags
import com.android.wm.shell.Utils
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

@Ignore("Test Base Class")
abstract class EnterDesktopWithDragExistingWindows
constructor(
    val rotation: Rotation = Rotation.ROTATION_0,
    isResizeable: Boolean = true,
    isLandscapeApp: Boolean = true,
) : DesktopScenarioCustomAppTestBase(isResizeable, isLandscapeApp) {

    @Rule
    @JvmField
    val testSetupRule = Utils.testSetupRule(NavBar.MODE_GESTURAL, rotation)
    private val imeApp = ImeAppHelper(instrumentation)
    private val newTaskApp = NewTasksAppHelper(instrumentation)

    @Before
    fun setup() {
        Assume.assumeTrue(Flags.enableDesktopWindowingMode() && tapl.isTablet)
        tapl.setEnableRotation(true)
        tapl.setExpectedRotation(rotation.value)
        ChangeDisplayOrientationRule.setRotation(rotation)
        tapl.enableTransientTaskbar(false)

        testApp.enterDesktopMode(wmHelper, device)
        imeApp.launchViaIntent(wmHelper)
        newTaskApp.launchViaIntent(wmHelper)
        testApp.launchViaIntent(wmHelper)
        testApp.exitDesktopWithDragToTopDragZone(wmHelper, device)
    }

    @Test
    open fun reenterDesktopWithDrag() {
        // By default this method uses drag to desktop
        testApp.enterDesktopMode(wmHelper, device, shouldUseDragToDesktop = true)
    }

    @After
    fun teardown() {
        testApp.exit(wmHelper)
        newTaskApp.exit(wmHelper)
        imeApp.exit(wmHelper)
    }
}