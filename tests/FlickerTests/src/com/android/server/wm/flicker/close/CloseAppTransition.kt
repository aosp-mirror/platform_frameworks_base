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

package com.android.server.wm.flicker.close

import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher.Companion.LAUNCHER
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.replacesLayer
import org.junit.Test

/** Base test class for transitions that close an app back to the launcher screen */
abstract class CloseAppTransition(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    protected open val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotation(flicker.scenario.startRotation.value)
            testApp.launchViaIntent(wmHelper)
            this.setRotation(flicker.scenario.startRotation)
        }
        teardown { testApp.exit(wmHelper) }
    }

    /**
     * Checks that [testApp] is the top visible app window at the start of the transition and that
     * it is replaced by [LAUNCHER] during the transition
     */
    @Presubmit
    @Test
    open fun launcherReplacesAppWindowAsTopWindow() {
        flicker.assertWm { this.isAppWindowOnTop(testApp).then().isAppWindowOnTop(LAUNCHER) }
    }

    /**
     * Checks that [LAUNCHER] is invisible at the start of the transition and that it becomes
     * visible during the transition
     */
    @Presubmit
    @Test
    open fun launcherWindowBecomesVisible() {
        flicker.assertWm { this.isAppWindowNotOnTop(LAUNCHER).then().isAppWindowOnTop(LAUNCHER) }
    }

    /** Checks that [LAUNCHER] layer becomes visible when [testApp] becomes invisible */
    @Presubmit
    @Test
    open fun launcherLayerReplacesApp() {
        flicker.replacesLayer(
            testApp,
            LAUNCHER,
            ignoreEntriesWithRotationLayer = flicker.scenario.isLandscapeOrSeascapeAtStart
        )
    }
}
