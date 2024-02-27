/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.service.quickswitch.scenarios

import android.app.Instrumentation
import android.tools.NavBar
import android.tools.Rotation
import android.tools.flicker.rules.ChangeDisplayOrientationRule
import android.tools.traces.parsers.WindowManagerStateHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.helpers.NonResizeableAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.service.Utils
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

@Ignore("Base Test Class")
abstract class QuickSwitchBetweenTwoAppsBack(val rotation: Rotation = Rotation.ROTATION_0) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tapl = LauncherInstrumentation()
    private val wmHelper = WindowManagerStateHelper(instrumentation)
    private val testApp1 = SimpleAppHelper(instrumentation)
    private val testApp2 = NonResizeableAppHelper(instrumentation)

    @Rule @JvmField val testSetupRule = Utils.testSetupRule(NavBar.MODE_GESTURAL, rotation)

    @Before
    fun setup() {
        tapl.setExpectedRotation(rotation.value)
        tapl.setIgnoreTaskbarVisibility(true)
        testApp1.launchViaIntent(wmHelper)
        ChangeDisplayOrientationRule.setRotation(rotation)
        testApp2.launchViaIntent(wmHelper)
        ChangeDisplayOrientationRule.setRotation(rotation)
    }

    @Test
    open fun quickSwitchBetweenTwoAppsBack() {
        tapl.launchedAppState.quickSwitchToPreviousApp()
        wmHelper
            .StateSyncBuilder()
            .withFullScreenApp(testApp1)
            .withNavOrTaskBarVisible()
            .withStatusBarVisible()
            .waitForAndVerify()
    }

    @After
    fun teardown() {
        testApp1.exit(wmHelper)
        testApp2.exit(wmHelper)
    }
}
