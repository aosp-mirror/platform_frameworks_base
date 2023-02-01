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

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.snapshotStartingWindowLayerCoversExactlyOnApp
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window layer will become visible when switching from the fixed orientation activity
 * (e.g. Launcher activity). To run this test: `atest
 * FlickerTests:OpenImeWindowFromFixedOrientationAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class OpenImeWindowFromFixedOrientationAppTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)

            // Launch the activity with expecting IME will be shown.
            imeTestApp.launchViaIntent(wmHelper)

            // Swiping out the IME activity to home.
            tapl.goHome()
            wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
        }
        transitions {
            // Bring the existing IME activity to the front in landscape mode device rotation.
            setRotation(PlatformConsts.Rotation.ROTATION_90)
            imeTestApp.launchViaIntent(wmHelper)
        }
        teardown { imeTestApp.exit(wmHelper) }
    }

    @Presubmit @Test fun imeWindowBecomesVisible() = flicker.imeWindowBecomesVisible()

    @Presubmit @Test fun imeLayerBecomesVisible() = flicker.imeLayerBecomesVisible()

    @FlakyTest(bugId = 240918620)
    @Test
    fun snapshotStartingWindowLayerCoversExactlyOnApp() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        flicker.snapshotStartingWindowLayerCoversExactlyOnApp(imeTestApp)
    }

    @Presubmit
    @Test
    fun snapshotStartingWindowLayerCoversExactlyOnApp_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        flicker.snapshotStartingWindowLayerCoversExactlyOnApp(imeTestApp)
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_90)
            )
        }
    }
}
