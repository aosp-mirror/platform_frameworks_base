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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.Surface
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
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
@Presubmit
open class SwitchImeWindowsFromGestureNavTest(
    testSpec: FlickerTestParameter
) : BaseTest(testSpec) {
    private val testApp = SimpleAppHelper(instrumentation)
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                tapl.setExpectedRotationCheckEnabled(false)
            }
            eachRun {
                this.setRotation(testSpec.startRotation)
                testApp.launchViaIntent(wmHelper)
                wmHelper.StateSyncBuilder()
                    .withFullScreenApp(testApp)
                    .waitForAndVerify()

                imeTestApp.launchViaIntent(wmHelper)
                wmHelper.StateSyncBuilder()
                    .withFullScreenApp(imeTestApp)
                    .waitForAndVerify()

                imeTestApp.openIME(wmHelper)
            }
        }
        teardown {
            eachRun {
                tapl.goHome()
                wmHelper.StateSyncBuilder()
                    .withHomeActivityVisible()
                    .waitForAndVerify()
                testApp.exit(wmHelper)
                imeTestApp.exit(wmHelper)
            }
        }
        transitions {
            // [Step1]: Swipe right from imeTestApp to testApp task
            createTag(TAG_IME_VISIBLE)
            tapl.launchedAppState.quickSwitchToPreviousApp()
            wmHelper.StateSyncBuilder()
                .withFullScreenApp(testApp)
                .waitForAndVerify()
            createTag(TAG_IME_INVISIBLE)
        }
        transitions {
            // [Step2]: Swipe left to back to imeTestApp task
            tapl.launchedAppState.quickSwitchToPreviousAppSwipeLeft()
            wmHelper.StateSyncBuilder()
                .withFullScreenApp(imeTestApp)
                .waitForAndVerify()
        }
    }

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() =
        super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Test
    fun imeAppWindowVisibility() {
        testSpec.assertWm {
            isAppWindowVisible(imeTestApp)
                .then()
                .isAppSnapshotStartingWindowVisibleFor(testApp, isOptional = true)
                .then()
                .isAppWindowVisible(testApp)
                .then()
                .isAppSnapshotStartingWindowVisibleFor(imeTestApp, isOptional = true)
                .then()
                .isAppWindowVisible(imeTestApp)
        }
    }

    @Test
    open fun imeLayerIsVisibleWhenSwitchingToImeApp() {
        testSpec.assertLayersStart {
            isVisible(ComponentNameMatcher.IME)
        }
        testSpec.assertLayersTag(TAG_IME_VISIBLE) {
            isVisible(ComponentNameMatcher.IME)
        }
        testSpec.assertLayersEnd {
            isVisible(ComponentNameMatcher.IME)
        }
    }

    @Test
    fun imeLayerIsInvisibleWhenSwitchingToTestApp() {
        testSpec.assertLayersTag(TAG_IME_INVISIBLE) {
            isInvisible(ComponentNameMatcher.IME)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                    repetitions = 3,
                    supportedNavigationModes = listOf(
                        WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY
                    ),
                    supportedRotations = listOf(Surface.ROTATION_0)
                )
        }

        private const val TAG_IME_VISIBLE = "imeVisible"
        private const val TAG_IME_INVISIBLE = "imeInVisible"
    }
}
