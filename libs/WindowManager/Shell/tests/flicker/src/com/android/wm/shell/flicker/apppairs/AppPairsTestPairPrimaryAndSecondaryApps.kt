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

import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.APP_PAIR_SPLIT_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appPairsDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.AppPairsHelper.Companion.waitAppsShown
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launch app from launcher.
 * To run this test: `atest WMShellFlickerTests:AppPairsTestPairPrimaryAndSecondaryApps`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class AppPairsTestPairPrimaryAndSecondaryApps(
    testSpec: FlickerTestParameter
) : AppPairsTransition(testSpec) {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            transitions {
                // TODO pair apps through normal UX flow
                executeShellCommand(
                    composePairsCommand(primaryTaskId, secondaryTaskId, pair = true))
                waitAppsShown(primaryApp, secondaryApp)
            }
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
    fun bothAppWindowsVisible() {
        testSpec.assertWmEnd {
            isAppWindowVisible(primaryApp.component)
            isAppWindowVisible(secondaryApp.component)
        }
    }

    @Ignore
    @Test
    fun appsEndingBounds() {
        testSpec.assertLayersEnd {
            val dividerRegion = layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT).visibleRegion.region
            visibleRegion(primaryApp.component)
                .coversExactly(appPairsHelper.getPrimaryBounds(dividerRegion))
            visibleRegion(secondaryApp.component)
                .coversExactly(appPairsHelper.getSecondaryBounds(dividerRegion))
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
