/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm.flicker.rotation

import android.platform.test.annotations.Presubmit
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.ROTATION_COMPONENT
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Cycle through supported app rotations.
 * To run this test: `atest FlickerTests:ChangeAppRotationTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class ChangeAppRotationTest(
    testSpec: FlickerTestParameter
) : RotationTransition(testSpec) {
    override val testApp = SimpleAppHelper(instrumentation)
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            super.transition(this, it)
            setup {
                test {
                    testApp.launchViaIntent(wmHelper)
                }
            }
        }

    @FlakyTest(bugId = 190185577)
    @Test
    override fun focusDoesNotChange() {
        super.focusDoesNotChange()
    }

    @Presubmit
    @Test
    fun screenshotLayerBecomesInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp.component)
                .then()
                .isVisible(ROTATION_COMPONENT)
                .then()
                .isVisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun statusBarWindowIsVisible() {
        testSpec.statusBarWindowIsVisible()
    }

    @FlakyTest
    @Test
    fun statusBarLayerIsVisible() {
        testSpec.statusBarLayerIsVisible()
    }

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() {
        testSpec.statusBarLayerRotatesScales(
            testSpec.config.startRotation, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    override fun navBarWindowIsVisible() {
        super.navBarWindowIsVisible()
    }

    @FlakyTest
    @Test
    override fun navBarLayerIsVisible() {
        super.navBarLayerIsVisible()
    }

    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() {
        super.navBarLayerRotatesAndScales()
    }

    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigRotationTests(repetitions = 5)
        }
    }
}