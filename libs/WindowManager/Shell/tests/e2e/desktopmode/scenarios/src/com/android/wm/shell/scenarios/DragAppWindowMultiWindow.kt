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

import com.android.server.wm.flicker.helpers.DesktopModeAppHelper
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.MailAppHelper
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.window.flags.Flags
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

@Ignore("Test Base Class")
abstract class DragAppWindowMultiWindow : DragAppWindowScenarioTestBase()
{
    private val imeAppHelper = ImeAppHelper(instrumentation)
    private val testApp = DesktopModeAppHelper(SimpleAppHelper(instrumentation))
    private val mailApp = DesktopModeAppHelper(MailAppHelper(instrumentation))
    private val newTasksApp = DesktopModeAppHelper(NewTasksAppHelper(instrumentation))
    private val imeApp = DesktopModeAppHelper(ImeAppHelper(instrumentation))

    @Before
    fun setup() {
        Assume.assumeTrue(Flags.enableDesktopWindowingMode() && tapl.isTablet)
        testApp.enterDesktopWithDrag(wmHelper, device)
        mailApp.launchViaIntent(wmHelper)
        newTasksApp.launchViaIntent(wmHelper)
        imeApp.launchViaIntent(wmHelper)
    }

    @Test
    override fun dragAppWindow() {
        val (startXIme, startYIme) = getWindowDragStartCoordinate(imeAppHelper)

        imeApp.dragWindow(startXIme, startYIme,
            endX = startXIme + 150, endY = startYIme + 150,
            wmHelper, device)
    }

    @After
    fun teardown() {
        testApp.exit(wmHelper)
        mailApp.exit(wmHelper)
        newTasksApp.exit(wmHelper)
        imeApp.exit(wmHelper)
    }
}
