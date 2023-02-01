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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/** Test Pip launch. To run this test: `atest WMShellFlickerTests:PipKeyboardTest` */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class PipKeyboardTest(flicker: FlickerTest) : PipTransition(flicker) {
    private val imeApp = ImeAppHelper(instrumentation)

    @Before
    open fun before() {
        assumeFalse(isShellTransitionsEnabled)
    }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition {
            setup {
                imeApp.launchViaIntent(wmHelper)
                setRotation(flicker.scenario.startRotation)
            }
            teardown { imeApp.exit(wmHelper) }
            transitions {
                // open the soft keyboard
                imeApp.openIME(wmHelper)
                createTag(TAG_IME_VISIBLE)

                // then close it again
                imeApp.closeIME(wmHelper)
            }
        }

    /** Ensure the pip window remains visible throughout any keyboard interactions */
    @Presubmit
    @Test
    open fun pipInVisibleBounds() {
        flicker.assertWmVisibleRegion(pipApp) {
            val displayBounds = WindowUtils.getDisplayBounds(flicker.scenario.startRotation)
            coversAtMost(displayBounds)
        }
    }

    /** Ensure that the pip window does not obscure the keyboard */
    @Presubmit
    @Test
    open fun pipIsAboveAppWindow() {
        flicker.assertWmTag(TAG_IME_VISIBLE) { isAboveWindow(ComponentNameMatcher.IME, pipApp) }
    }

    companion object {
        private const val TAG_IME_VISIBLE = "imeIsVisible"

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_0)
            )
        }
    }
}
