/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.snapshotStartingWindowLayerCoversExactlyOnApp
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will become visible when switching from the fixed orientation activity
 * (e.g. Launcher activity).
 * To run this test: `atest FlickerTests:OpenImeWindowFromFixedOrientationAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class OpenImeWindowFromFixedOrientationAppTest(
    testSpec: FlickerTestParameter
) : BaseTest(testSpec) {
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)

            // Launch the activity with expecting IME will be shown.
            imeTestApp.launchViaIntent(wmHelper)

            // Swiping out the IME activity to home.
            tapl.goHome()
            wmHelper.StateSyncBuilder()
                .withHomeActivityVisible()
                .waitForAndVerify()
        }
        transitions {
            // Bring the exist IME activity to the front in landscape mode device rotation.
            setRotation(Surface.ROTATION_90)
            imeTestApp.launchViaIntent(wmHelper)
        }
        teardown {
            imeTestApp.exit(wmHelper)
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    @Presubmit
    @Test
    fun imeWindowBecomesVisible() = testSpec.imeWindowBecomesVisible()

    @Presubmit
    @Test
    fun imeLayerBecomesVisible() = testSpec.imeLayerBecomesVisible()

    @Postsubmit
    @Test
    fun snapshotStartingWindowLayerCoversExactlyOnApp() {
        testSpec.snapshotStartingWindowLayerCoversExactlyOnApp(imeTestApp)
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
                                        supportedRotations = listOf(Surface.ROTATION_90),
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    )
                )
        }
    }
}
