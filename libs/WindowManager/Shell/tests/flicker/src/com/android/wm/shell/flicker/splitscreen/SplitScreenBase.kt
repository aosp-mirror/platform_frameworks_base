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

import android.content.Context
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import com.android.server.wm.flicker.helpers.setRotation
import com.android.wm.shell.flicker.BaseBenchmarkTest

abstract class SplitScreenBase(flicker: FlickerTest) : BaseBenchmarkTest(flicker) {
    protected val context: Context = instrumentation.context
    protected val primaryApp = SplitScreenUtils.getPrimary(instrumentation)
    protected val secondaryApp = SplitScreenUtils.getSecondary(instrumentation)

    protected open val defaultSetup: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setEnableRotation(true)
            setRotation(flicker.scenario.startRotation)
            tapl.setExpectedRotation(flicker.scenario.startRotation.value)
            tapl.workspace.switchToOverview().dismissAllTasks()
        }
    }

    protected open val defaultTeardown: FlickerBuilder.() -> Unit = {
        teardown {
            primaryApp.exit(wmHelper)
            secondaryApp.exit(wmHelper)
        }
    }

    protected open val withoutTracing: FlickerBuilder.() -> Unit = {
        withoutLayerTracing()
        withoutWindowManagerTracing()
        withoutTransitionTracing()
        withoutTransactionsTracing()
    }
}
