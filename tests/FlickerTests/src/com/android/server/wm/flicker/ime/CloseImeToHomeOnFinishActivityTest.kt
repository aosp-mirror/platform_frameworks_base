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

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.PlatinumTest
import android.platform.test.annotations.Presubmit
import android.tools.common.Rotation
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Unlike {@link OpenImeWindowTest} testing IME window opening transitions, this test also verify
 * there is no flickering when back to the simple activity without requesting IME to show.
 *
 * To run this test: `atest FlickerTests:OpenImeWindowAndCloseTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class CloseImeToHomeOnFinishActivityTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val simpleApp = SimpleAppHelper(instrumentation)
    private val testApp = ImeAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            simpleApp.launchViaIntent(wmHelper)
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
        }
        transitions { testApp.finishActivity(wmHelper) }
        teardown { simpleApp.exit(wmHelper) }
    }

    @Presubmit @Test fun imeWindowBecomesInvisible() = flicker.imeWindowBecomesInvisible()

    @Presubmit @Test fun imeLayerBecomesInvisible() = flicker.imeLayerBecomesInvisible()

    @FlakyTest(bugId = 246284124)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @Presubmit
    @Test
    @PlatinumTest(focusArea = "ime")
    override fun cujCompleted() {
        runAndIgnoreAssumptionViolation { entireScreenCovered() }
        runAndIgnoreAssumptionViolation { statusBarLayerIsVisibleAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { statusBarLayerPositionAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { statusBarWindowIsAlwaysVisible() }
        runAndIgnoreAssumptionViolation { visibleWindowsShownMoreThanOneConsecutiveEntry() }
        runAndIgnoreAssumptionViolation { taskBarLayerIsVisibleAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { taskBarWindowIsAlwaysVisible() }
        runAndIgnoreAssumptionViolation { navBarLayerIsVisibleAtStartAndEnd() }
        runAndIgnoreAssumptionViolation { navBarWindowIsAlwaysVisible() }
        runAndIgnoreAssumptionViolation { navBarWindowIsVisibleAtStartAndEnd() }
        imeLayerBecomesInvisible()
        imeWindowBecomesInvisible()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
        }
    }
}
