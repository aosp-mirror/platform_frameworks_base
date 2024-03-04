/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm.flicker.launch

import android.tools.device.apphelpers.CameraAppHelper
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import com.android.server.wm.flicker.launch.common.OpenAppFromLauncherTransition
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app after cold opening camera
 *
 * To run this test: `atest FlickerTests:OpenAppAfterCameraTest`
 *
 * Notes: Some default assertions are inherited [OpenAppTransition]
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppFromIntentColdAfterCameraTest(flicker: LegacyFlickerTest) :
    OpenAppFromLauncherTransition(flicker) {
    private val cameraApp = CameraAppHelper(instrumentation)
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                tapl.setExpectedRotationCheckEnabled(false)
                // 1. Open camera - cold -> close it first
                cameraApp.exit(wmHelper)
                cameraApp.launchViaIntent(wmHelper)
                // Can't use TAPL due to Recents not showing in 3 Button Nav in full screen mode
                device.pressHome()
                tapl.getWorkspace()
                wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
            }
            teardown { testApp.exit(wmHelper) }
            transitions { testApp.launchViaIntent(wmHelper) }
        }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
