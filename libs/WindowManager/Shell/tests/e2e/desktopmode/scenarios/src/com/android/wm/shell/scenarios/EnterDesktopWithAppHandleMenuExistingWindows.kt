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

import android.platform.test.annotations.Postsubmit
import android.app.Instrumentation
import android.tools.traces.parsers.WindowManagerStateHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.helpers.DesktopModeAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.window.flags.Flags
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

@RunWith(BlockJUnit4ClassRunner::class)
@Postsubmit
open class EnterDesktopWithAppHandleMenuExistingWindows {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tapl = LauncherInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation)
    private val device = UiDevice.getInstance(instrumentation)
    private val imeApp = ImeAppHelper(instrumentation)
    private val newTaskApp = NewTasksAppHelper(instrumentation)
    private val testApp = DesktopModeAppHelper(SimpleAppHelper(instrumentation))

    @Before
    fun setup() {
        Assume.assumeTrue(Flags.enableDesktopWindowingMode() && tapl.isTablet)
        testApp.enterDesktopMode(wmHelper, device)
        imeApp.launchViaIntent(wmHelper)
        newTaskApp.launchViaIntent(wmHelper)
        testApp.launchViaIntent(wmHelper)
        testApp.exitDesktopWithDragToTopDragZone(wmHelper, device)
    }

    @Test
    open fun reenterDesktopWithAppHandleMenu() {
        testApp.enterDesktopModeFromAppHandleMenu(wmHelper, device)
    }

    @After
    fun teardown() {
        testApp.exit(wmHelper)
        newTaskApp.exit(wmHelper)
        imeApp.exit(wmHelper)
    }
}