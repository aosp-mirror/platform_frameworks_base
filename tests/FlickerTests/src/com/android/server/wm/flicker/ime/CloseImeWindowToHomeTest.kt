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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing to home transitions.
 * To run this test: `atest FlickerTests:CloseImeWindowToHomeTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class CloseImeWindowToHomeTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val testApp = ImeAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
        }
        transitions {
            tapl.goHome()
            wmHelper.StateSyncBuilder()
                .withHomeActivityVisible()
                .withImeGone()
                .waitForAndVerify()
        }
        teardown {
            testApp.exit(wmHelper)
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                listOf(
                    ComponentNameMatcher.IME,
                    ComponentNameMatcher.SPLASH_SCREEN,
                    ComponentNameMatcher.SNAPSHOT
                )
            )
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                listOf(
                    ComponentNameMatcher.IME,
                    ComponentNameMatcher.SPLASH_SCREEN
                )
            )
        }
    }

    @Presubmit
    @Test
    fun imeLayerBecomesInvisible() = testSpec.imeLayerBecomesInvisible()

    @Presubmit
    @Test
    fun imeWindowBecomesInvisible() = testSpec.imeWindowBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppWindowBecomesInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(testApp)
                .then()
                .isAppWindowInvisible(testApp)
        }
    }

    @Presubmit
    @Test
    fun imeAppLayerBecomesInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp)
                .then()
                .isInvisible(testApp)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                                        supportedRotations = listOf(Surface.ROTATION_0),
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY,
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    )
                )
        }
    }
}
