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

package com.android.server.wm.flicker.rotation

import android.app.Instrumentation
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation

abstract class RotationTransition(protected val testSpec: FlickerTestParameter) {
    protected abstract val testApp: StandardAppHelper

    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val startingPos get() = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
    protected val endingPos get() = WindowUtils.getDisplayBounds(testSpec.config.endRotation)

    protected open val transition: FlickerBuilder.(Map<String, Any?>) -> Unit = {
        withTestName { testSpec.name }
        repeat { testSpec.config.repetitions }
        setup {
            test {
                device.wakeUpAndGoToHomeScreen()
            }
            eachRun {
                this.setRotation(testSpec.config.startRotation)
            }
        }
        teardown {
            test {
                testApp.exit()
            }
        }
        transitions {
            this.setRotation(testSpec.config.endRotation)
        }
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(testSpec.config)
        }
    }
}