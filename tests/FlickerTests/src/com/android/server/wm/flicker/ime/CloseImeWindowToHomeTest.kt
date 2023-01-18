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

import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window closing to home transitions. To run this test: `atest
 * FlickerTests:CloseImeWindowToHomeTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class CloseImeWindowToHomeTest(flicker: FlickerTest) : BaseTest(flicker) {
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
            wmHelper.StateSyncBuilder().withHomeActivityVisible().withImeGone().waitForAndVerify()
        }
        teardown { testApp.exit(wmHelper) }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        flicker.assertWm {
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
        flicker.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                listOf(ComponentNameMatcher.IME, ComponentNameMatcher.SPLASH_SCREEN)
            )
        }
    }

    @Presubmit @Test fun imeLayerBecomesInvisible() = flicker.imeLayerBecomesInvisible()

    @Presubmit @Test fun imeWindowBecomesInvisible() = flicker.imeWindowBecomesInvisible()

    @Presubmit
    @Test
    fun imeAppWindowBecomesInvisible() {
        flicker.assertWm { this.isAppWindowVisible(testApp).then().isAppWindowInvisible(testApp) }
    }

    @Presubmit
    @Test
    fun imeAppLayerBecomesInvisible() {
        flicker.assertLayers { this.isVisible(testApp).then().isInvisible(testApp) }
    }

    @Test
    @IwTest(focusArea = "ime")
    override fun cujCompleted() {
        super.cujCompleted()
        imeLayerBecomesInvisible()
        imeAppWindowBecomesInvisible()
        imeWindowBecomesInvisible()
        imeLayerBecomesInvisible()
        runAndIgnoreAssumptionViolation { navBarLayerPositionAtStartAndEnd() }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_0)
            )
        }
    }
}
