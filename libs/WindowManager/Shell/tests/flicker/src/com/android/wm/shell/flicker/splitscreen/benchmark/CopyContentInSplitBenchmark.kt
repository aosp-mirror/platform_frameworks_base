/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.flicker.splitscreen.benchmark

import android.platform.test.annotations.PlatinumTest
import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.splitscreen.SplitScreenBase
import com.android.wm.shell.flicker.splitscreen.SplitScreenUtils
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class CopyContentInSplitBenchmark(override val flicker: FlickerTest) :
    SplitScreenBase(flicker) {
    protected val textEditApp = SplitScreenUtils.getIme(instrumentation)
    protected val magnifierLayer = ComponentNameMatcher("", "magnifier surface bbq wrapper#")
    protected val popupWindowLayer = ComponentNameMatcher("", "PopupWindow:")
    protected val thisTransition: FlickerBuilder.() -> Unit
        get() = {
            setup { SplitScreenUtils.enterSplit(wmHelper, tapl, device, primaryApp, textEditApp) }
            transitions {
                SplitScreenUtils.copyContentInSplit(
                    instrumentation,
                    device,
                    primaryApp,
                    textEditApp
                )
            }
        }

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            withoutTracing(this)
            defaultSetup(this)
            defaultTeardown(this)
            thisTransition(this)
        }

    @PlatinumTest(focusArea = "sysui")
    @Presubmit
    @Test
    open fun cujCompleted() {
        // The validation of copied text is already done in SplitScreenUtils.copyContentInSplit()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
