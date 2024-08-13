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

import android.platform.test.annotations.Postsubmit
import android.app.Instrumentation
import android.tools.traces.parsers.WindowManagerStateHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.helpers.DesktopModeAppHelper
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.LetterboxAppHelper
import com.android.server.wm.flicker.helpers.MailAppHelper
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.window.flags.Flags
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
/**
 * Base scenario test for minimizing the least recently used window when a new window is opened
 * above the window limit. For tangor devices, which this test currently runs on, the window limit
 * is 4.
 */
@RunWith(BlockJUnit4ClassRunner::class)
@Postsubmit
open class MinimizeWindowOnAppOpen()
{
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tapl = LauncherInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation)
    private val device = UiDevice.getInstance(instrumentation)

    private val testApp = DesktopModeAppHelper(SimpleAppHelper(instrumentation))
    private val mailApp = DesktopModeAppHelper(MailAppHelper(instrumentation))
    private val newTasksApp = DesktopModeAppHelper(NewTasksAppHelper(instrumentation))
    private val imeApp = DesktopModeAppHelper(ImeAppHelper(instrumentation))
    private val letterboxAppHelper = DesktopModeAppHelper(LetterboxAppHelper(instrumentation))

    @Before
    fun setup() {
        Assume.assumeTrue(Flags.enableDesktopWindowingMode() && tapl.isTablet)
        testApp.enterDesktopWithDrag(wmHelper, device)
        mailApp.launchViaIntent(wmHelper)
        newTasksApp.launchViaIntent(wmHelper)
        imeApp.launchViaIntent(wmHelper)
    }

    @Test
    open fun openAppToMinimizeWindow() {
        // Launch a new app while 4 apps are already open on desktop. This should result in the
        // first app we opened to be minimized.
        letterboxAppHelper.launchViaIntent(wmHelper)
    }

    @After
    fun teardown() {
        testApp.exit(wmHelper)
        mailApp.exit(wmHelper)
        newTasksApp.exit(wmHelper)
        imeApp.exit(wmHelper)
        letterboxAppHelper.exit(wmHelper)
    }
}
