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

package com.android.server.wm.flicker.rotation

import androidx.test.filters.FlakyTest
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an app and cycling through app rotations using seamless rotations
 *
 * Currently runs:
 *      0 -> 90 degrees
 *      0 -> 90 degrees (with starved UI thread)
 *      90 -> 0 degrees
 *      90 -> 0 degrees (with starved UI thread)
 *
 * Actions:
 *     Launch an app in fullscreen and supporting seamless rotation (via intent)
 *     Set initial device orientation
 *     Start tracing
 *     Change device orientation
 *     Stop tracing
 *
 * To run this test: `atest FlickerTests:SeamlessAppRotationTest`
 *
 * To run only the presubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Presubmit`
 *
 * To run only the postsubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Postsubmit`
 *
 * To run only the flaky assertions add: `--
 *      --module-arg FlickerTests:include-annotation:androidx.test.filters.FlakyTest`
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [RotationTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
@FlakyTest(bugId = 219689723)
class SeamlessAppRotationTest_ShellTransit(
    testSpec: FlickerTestParameter
) : SeamlessAppRotationTest(testSpec) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }
}
