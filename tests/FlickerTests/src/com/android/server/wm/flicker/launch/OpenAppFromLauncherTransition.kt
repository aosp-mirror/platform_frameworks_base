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

import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.legacy.LegacyFlickerTest
import com.android.server.wm.flicker.replacesLayer
import org.junit.Test

/** Base class for app launch tests */
abstract class OpenAppFromLauncherTransition(flicker: LegacyFlickerTest) :
    OpenAppTransition(flicker) {

    /** Checks that the focus changes from the [ComponentNameMatcher.LAUNCHER] to [testApp] */
    @Presubmit
    @Test
    open fun focusChanges() {
        flicker.assertEventLog { this.focusChanges("NexusLauncherActivity", testApp.`package`) }
    }

    /**
     * Checks that [ComponentNameMatcher.LAUNCHER] layer is visible at the start of the transition,
     * and is replaced by [testApp], which remains visible until the end
     */
    open fun appLayerReplacesLauncher() {
        flicker.replacesLayer(
            ComponentNameMatcher.LAUNCHER,
            testApp,
            ignoreEntriesWithRotationLayer = true,
            ignoreSnapshot = true,
            ignoreSplashscreen = true
        )
    }

    /**
     * Checks that [ComponentNameMatcher.LAUNCHER] window is the top window at the start of the
     * transition, and is replaced by a [ComponentNameMatcher.SNAPSHOT] or
     * [ComponentNameMatcher.SPLASH_SCREEN], or [testApp], which remains visible until the end
     */
    @Presubmit
    @Test
    open fun appWindowReplacesLauncherAsTopWindow() {
        flicker.assertWm {
            this.isAppWindowOnTop(ComponentNameMatcher.LAUNCHER)
                .then()
                .isAppWindowOnTop(
                    testApp.or(ComponentNameMatcher.SNAPSHOT).or(ComponentNameMatcher.SPLASH_SCREEN)
                )
        }
    }

    /** Checks that [testApp] window is the top window at the en dof the trace */
    @Presubmit
    @Test
    open fun appWindowAsTopWindowAtEnd() {
        flicker.assertWmEnd { this.isAppWindowOnTop(testApp) }
    }
}
