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

import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.ImeStateInitializeHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Launch an app that automatically displays the IME
 *
 * To run this test: `atest FlickerTests:LaunchAppShowImeOnStartTest`
 *
 * Actions:
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] that automatically displays IME and wait animation to complete
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
 *        are inherited [CloseAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LaunchAppShowImeOnStartTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)
    private val initializeApp = ImeStateInitializeHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            initializeApp.launchViaIntent(wmHelper)
            this.setRotation(testSpec.startRotation)
        }
        teardown {
            initializeApp.exit(wmHelper)
            testApp.exit(wmHelper)
        }
        transitions {
            testApp.launchViaIntent(wmHelper)
            wmHelper.StateSyncBuilder()
                .withImeShown()
                .waitForAndVerify()
        }
    }

    /**
     * Checks that [ComponentMatcher.IME] window becomes visible during the transition
     */
    @Presubmit
    @Test
    fun imeWindowBecomesVisible() = testSpec.imeWindowBecomesVisible()

    /**
     * Checks that [ComponentMatcher.IME] layer becomes visible during the transition
     */
    @Presubmit
    @Test
    fun imeLayerBecomesVisible() = testSpec.imeLayerBecomesVisible()

    /**
     * Checks that [ComponentMatcher.IME] layer is invisible at the start of the transition
     */
    @Presubmit
    @Test
    fun imeLayerNotExistsStart() {
        testSpec.assertLayersStart {
            this.isInvisible(ComponentNameMatcher.IME)
        }
    }

    /**
     * Checks that [ComponentMatcher.IME] layer is visible at the end of the transition
     */
    @Presubmit
    @Test
    fun imeLayerExistsEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(ComponentNameMatcher.IME)
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
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
