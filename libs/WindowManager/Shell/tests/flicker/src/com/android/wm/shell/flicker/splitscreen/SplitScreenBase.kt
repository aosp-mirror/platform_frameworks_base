/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.flicker.splitscreen

import android.app.Instrumentation
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.wm.shell.flicker.helpers.SplitScreenHelper

abstract class SplitScreenBase(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val taplInstrumentation = LauncherInstrumentation()
    protected val context: Context = instrumentation.context
    protected val primaryApp = SplitScreenHelper.getPrimary(instrumentation)
    protected val secondaryApp = SplitScreenHelper.getSecondary(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(this)
        }
    }

    protected open val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                test {
                    taplInstrumentation.setEnableRotation(true)
                    setRotation(testSpec.startRotation)
                    taplInstrumentation.setExpectedRotation(testSpec.startRotation)
                }
            }
            teardown {
                eachRun {
                    primaryApp.exit(wmHelper)
                    secondaryApp.exit(wmHelper)
                }
            }
        }
}
