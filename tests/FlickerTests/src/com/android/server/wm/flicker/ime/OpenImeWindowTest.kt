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
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/** Test IME window opening transitions. To run this test: `atest FlickerTests:OpenImeWindowTest` */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class OpenImeWindowTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val testApp = ImeAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup { testApp.launchViaIntent(wmHelper) }
        transitions { testApp.openIME(wmHelper) }
        teardown {
            testApp.closeIME(wmHelper)
            testApp.exit(wmHelper)
        }
    }

    @Test
    @IwTest(focusArea = "ime")
    override fun cujCompleted() {
        super.cujCompleted()
        imeWindowBecomesVisible()
        appWindowAlwaysVisibleOnTop()
        layerAlwaysVisible()
    }

    @Presubmit @Test fun imeWindowBecomesVisible() = flicker.imeWindowBecomesVisible()

    @Presubmit
    @Test
    fun appWindowAlwaysVisibleOnTop() {
        flicker.assertWm { this.isAppWindowOnTop(testApp) }
    }

    @Presubmit @Test fun imeLayerBecomesVisible() = flicker.imeLayerBecomesVisible()

    @Presubmit
    @Test
    fun layerAlwaysVisible() {
        flicker.assertLayers { this.isVisible(testApp) }
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
