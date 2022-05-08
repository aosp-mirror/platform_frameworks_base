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
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.startRotation
import org.junit.Test

/**
 * Base class for exiting pip (closing pip window) without returning to the app
 */
abstract class ExitPipTransition(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
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

    /**
     * Checks that [pipApp] window is pinned and visible at the start and then becomes
     * unpinned and invisible at the same moment, and remains unpinned and invisible
     * until the end of the transition
     */
    @Presubmit
    @Test
    open fun pipWindowBecomesInvisible() {
        testSpec.assertWm {
            this.invoke("hasPipWindow") {
                it.isPinned(pipApp.component).isAppWindowVisible(pipApp.component)
            }.then().invoke("!hasPipWindow") {
                it.isNotPinned(pipApp.component).isAppWindowInvisible(pipApp.component)
            }
        }
    }

    /**
     * Checks that [pipApp] and [LAUNCHER_COMPONENT] layers are visible at the start
     * of the transition. Then [pipApp] layer becomes invisible, and remains invisible
     * until the end of the transition
     */
    @Presubmit
    @Test
    open fun pipLayerBecomesInvisible() {
        testSpec.assertLayers {
            this.isVisible(pipApp.component)
                .isVisible(LAUNCHER_COMPONENT)
                .then()
                .isInvisible(pipApp.component)
                .isVisible(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that the focus changes between the [pipApp] window and the launcher when
     * closing the pip window
     */
    @FlakyTest(bugId = 151179149)
    @Test
    open fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges(pipApp.launcherName, "NexusLauncherActivity")
        }
    }
}