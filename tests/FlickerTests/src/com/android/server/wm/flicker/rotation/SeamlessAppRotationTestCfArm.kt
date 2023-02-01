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

package com.android.server.wm.flicker.rotation

import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/** This test should fail because of b/264518826 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class SeamlessAppRotationTestCfArm(flicker: FlickerTest) : SeamlessAppRotationTest(flicker) {
    companion object {
        /**
         * Creates the test configurations for seamless rotation based on the default rotation tests
         * from [FlickerTestFactory.rotationTests], but adding a flag (
         * [ActivityOptions.SeamlessRotation.EXTRA_STARVE_UI_THREAD]) to indicate if the app should
         * starve the UI thread of not
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.rotationTests().flatMap { sourceConfig ->
                val defaultRun = createConfig(sourceConfig, starveUiThread = false)
                val busyUiRun = createConfig(sourceConfig, starveUiThread = true)
                listOf(defaultRun, busyUiRun)
            }
        }
    }
}
