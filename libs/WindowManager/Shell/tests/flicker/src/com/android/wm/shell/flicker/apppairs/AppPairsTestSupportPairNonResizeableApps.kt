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

package com.android.wm.shell.flicker.apppairs

import android.view.Display
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import com.android.wm.shell.flicker.appPairsDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.resetMultiWindowConfig
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.setSupportsNonResizableMultiWindow
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launch app from launcher. When the device supports non-resizable in multi window
 * {@link Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW}, app pairs can pair
 * non-resizable apps.
 *
 * To run this test: `atest WMShellFlickerTests:AppPairsTestSupportPairNonResizeableApps`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class AppPairsTestSupportPairNonResizeableApps(
    testSpec: FlickerTestParameter
) : AppPairsTransition(testSpec) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            transitions {
                nonResizeableApp?.launchViaIntent(wmHelper)
                // TODO pair apps through normal UX flow
                executeShellCommand(
                        composePairsCommand(primaryTaskId, nonResizeableTaskId, pair = true))
                val waitConditions = mutableListOf(
                    WindowManagerConditionsFactory.isWindowVisible(primaryApp.component),
                    WindowManagerConditionsFactory.isLayerVisible(primaryApp.component),
                    WindowManagerConditionsFactory.isAppTransitionIdle(Display.DEFAULT_DISPLAY))

                nonResizeableApp?.let {
                    waitConditions.add(
                        WindowManagerConditionsFactory.isWindowVisible(nonResizeableApp.component))
                    waitConditions.add(
                        WindowManagerConditionsFactory.isLayerVisible(nonResizeableApp.component))
                }
                wmHelper.waitFor(*waitConditions.toTypedArray())
            }
        }

    @Before
    override fun setup() {
        super.setup()
        setSupportsNonResizableMultiWindow(instrumentation, 1)
    }

    @After
    override fun teardown() {
        super.teardown()
        resetMultiWindowConfig(instrumentation)
    }

    @Ignore
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    @Ignore
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @Ignore
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @Ignore
    @Test
    fun appPairsDividerIsVisibleAtEnd() = testSpec.appPairsDividerIsVisibleAtEnd()

    @Ignore
    @Test
    fun bothAppWindowVisible() {
        val nonResizeableApp = nonResizeableApp
        require(nonResizeableApp != null) {
            "Non resizeable app not initialized"
        }
        testSpec.assertWmEnd {
            isAppWindowVisible(nonResizeableApp.component)
            isAppWindowVisible(primaryApp.component)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                    repetitions = AppPairsHelper.TEST_REPETITIONS)
        }
    }
}