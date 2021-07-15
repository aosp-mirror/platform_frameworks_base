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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.focusChanges
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.startRotation
import org.junit.Test
import org.junit.runners.Parameterized

abstract class PipCloseTransition(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = buildTransition(eachRun = true) { configuration ->
            setup {
                eachRun {
                    this.setRotation(configuration.startRotation)
                }
            }
            teardown {
                eachRun {
                    this.setRotation(Surface.ROTATION_0)
                }
            }
        }

    @Presubmit
    @Test
    open fun pipWindowBecomesInvisible() {
        testSpec.assertWm {
            this.showsAppWindow(PIP_WINDOW_TITLE)
                .then()
                .hidesAppWindow(PIP_WINDOW_TITLE)
        }
    }

    @Presubmit
    @Test
    open fun pipLayerBecomesInvisible() {
        testSpec.assertLayers {
            this.isVisible(PIP_WINDOW_TITLE)
                .then()
                .isInvisible(PIP_WINDOW_TITLE)
        }
    }

    @FlakyTest(bugId = 151179149)
    @Test
    open fun focusChanges() = testSpec.focusChanges(pipApp.launcherName, "NexusLauncherActivity")

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                    repetitions = 5)
        }
    }
}