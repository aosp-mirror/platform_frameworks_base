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

package com.android.server.wm.flicker.launch

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.appLayerReplacesLauncher
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.focusChanges
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.launcherWindowBecomesInvisible
import org.junit.Test

abstract class OpenAppTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    protected open val transition: FlickerBuilder.(Map<String, Any?>) -> Unit = {
        withTestName { testSpec.name }
        repeat { testSpec.config.repetitions }
        setup {
            test {
                device.wakeUpAndGoToHomeScreen()
                this.setRotation(testSpec.config.startRotation)
            }
        }
        teardown {
            test {
                testApp.exit()
            }
        }
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(testSpec.config)
        }
    }

    @Presubmit
    @Test
    open fun navBarWindowIsAlwaysVisible() {
        testSpec.navBarWindowIsAlwaysVisible()
    }

    @Presubmit
    @Test
    open fun navBarLayerIsAlwaysVisible() {
        testSpec.navBarLayerIsAlwaysVisible(rotatesScreen = testSpec.isRotated)
    }

    @Presubmit
    @Test
    open fun navBarLayerRotatesAndScales() {
        testSpec.navBarLayerRotatesAndScales(Surface.ROTATION_0, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    open fun statusBarWindowIsAlwaysVisible() {
        testSpec.statusBarWindowIsAlwaysVisible()
    }

    @Presubmit
    @Test
    open fun statusBarLayerIsAlwaysVisible() {
        testSpec.statusBarLayerIsAlwaysVisible(rotatesScreen = testSpec.isRotated)
    }

    @Presubmit
    @Test
    open fun statusBarLayerRotatesScales() {
        testSpec.statusBarLayerRotatesScales(Surface.ROTATION_0, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    open fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry()
        }
    }

    @Presubmit
    @Test
    open fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry()
        }
    }

    @Presubmit
    @Test
    // During testing the launcher is always in portrait mode
    open fun noUncoveredRegions() {
        testSpec.noUncoveredRegions(Surface.ROTATION_0, testSpec.config.endRotation)
    }

    @Presubmit
    @Test
    open fun focusChanges() {
        testSpec.focusChanges("NexusLauncherActivity", testApp.`package`)
    }

    @Presubmit
    @Test
    open fun appLayerReplacesLauncher() {
        testSpec.appLayerReplacesLauncher(testApp.`package`)
    }

    @Presubmit
    @Test
    open fun appWindowReplacesLauncherAsTopWindow() {
        testSpec.appWindowReplacesLauncherAsTopWindow(testApp)
    }

    @Presubmit
    @Test
    open fun launcherWindowBecomesInvisible() {
        testSpec.launcherWindowBecomesInvisible()
    }
}