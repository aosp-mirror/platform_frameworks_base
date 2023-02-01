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

package com.android.server.wm.flicker.ime

import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import org.junit.Assume
import org.junit.Before

import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME windows switching with 2-Buttons or gestural navigation.
 * To run this test: `atest FlickerTests:SwitchImeWindowsFromGestureNavTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
@FlakyTest(bugId = 228012334)
class SwitchImeWindowsFromGestureNavTest_ShellTransit(testSpec: FlickerTestParameter)
    : SwitchImeWindowsFromGestureNavTest(testSpec) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }
}
