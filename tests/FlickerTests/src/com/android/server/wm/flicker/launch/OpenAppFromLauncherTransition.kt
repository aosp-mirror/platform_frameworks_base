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
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.replacesLayer
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.Test

/**
 * Base class for app launch tests
 */
abstract class OpenAppFromLauncherTransition(testSpec: FlickerTestParameter)
    : OpenAppTransition(testSpec) {

    /**
     * Checks that the focus changes from the launcher to [testApp]
     */
    @Presubmit
    @Test
    open fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges("NexusLauncherActivity", testApp.`package`)
        }
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] layer is visible at the start of the transition, and
     * is replaced by [testApp], which remains visible until the end
     */
    open fun appLayerReplacesLauncher() {
        testSpec.replacesLayer(LAUNCHER_COMPONENT, testApp.component,
                ignoreEntriesWithRotationLayer = true, ignoreSnapshot = true,
                ignoreSplashscreen = true)
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] window is visible at the start of the transition, and
     * is replaced by a snapshot or splash screen (optional), and finally, is replaced by
     * [testApp], which remains visible until the end
     */
    @Presubmit
    @Test
    open fun appWindowReplacesLauncherAsTopWindow() {
        testSpec.assertWm {
            this.isAppWindowOnTop(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowOnTop(testApp.component)
        }
    }
}