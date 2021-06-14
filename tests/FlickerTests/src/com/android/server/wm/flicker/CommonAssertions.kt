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

@file:JvmName("CommonAssertions")
package com.android.server.wm.flicker

import android.content.ComponentName
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

val LAUNCHER_COMPONENT = ComponentName("com.google.android.apps.nexuslauncher",
        "com.google.android.apps.nexuslauncher.NexusLauncherActivity")

fun FlickerTestParameter.statusBarWindowIsVisible() {
    assertWm {
        this.isAboveAppWindowVisible(WindowManagerStateHelper.STATUS_BAR_COMPONENT)
    }
}

fun FlickerTestParameter.navBarWindowIsVisible() {
    assertWm {
        this.isAboveAppWindowVisible(WindowManagerStateHelper.NAV_BAR_COMPONENT)
    }
}

@JvmOverloads
fun FlickerTestParameter.entireScreenCovered(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    allStates: Boolean = true
) {
    val startingBounds = WindowUtils.getDisplayBounds(beginRotation)
    val endingBounds = WindowUtils.getDisplayBounds(endRotation)
    if (allStates) {
        assertLayers {
            if (startingBounds == endingBounds) {
                this.coversAtLeast(startingBounds)
            } else {
                this.coversAtLeast(startingBounds)
                    .then()
                    .coversAtLeast(endingBounds)
            }
        }
    } else {
        assertLayersStart {
            this.visibleRegion().coversAtLeast(startingBounds)
        }
        assertLayersEnd {
            this.visibleRegion().coversAtLeast(endingBounds)
        }
    }
}

fun FlickerTestParameter.navBarLayerIsVisible() {
    assertLayersStart {
        this.isVisible(WindowManagerStateHelper.NAV_BAR_COMPONENT)
    }
    assertLayersEnd {
        this.isVisible(WindowManagerStateHelper.NAV_BAR_COMPONENT)
    }
}

fun FlickerTestParameter.statusBarLayerIsVisible() {
    assertLayersStart {
        this.isVisible(WindowManagerStateHelper.STATUS_BAR_COMPONENT)
    }
    assertLayersEnd {
        this.isVisible(WindowManagerStateHelper.STATUS_BAR_COMPONENT)
    }
}

@JvmOverloads
fun FlickerTestParameter.navBarLayerRotatesAndScales(
    beginRotation: Int,
    endRotation: Int = beginRotation
) {
    val startingPos = WindowUtils.getNavigationBarPosition(beginRotation)
    val endingPos = WindowUtils.getNavigationBarPosition(endRotation)

    assertLayersStart {
        this.visibleRegion(WindowManagerStateHelper.NAV_BAR_COMPONENT).coversExactly(startingPos)
    }
    assertLayersEnd {
        this.visibleRegion(WindowManagerStateHelper.NAV_BAR_COMPONENT).coversExactly(endingPos)
    }
}

@JvmOverloads
fun FlickerTestParameter.statusBarLayerRotatesScales(
    beginRotation: Int,
    endRotation: Int = beginRotation
) {
    val startingPos = WindowUtils.getStatusBarPosition(beginRotation)
    val endingPos = WindowUtils.getStatusBarPosition(endRotation)

    assertLayersStart {
        this.visibleRegion(WindowManagerStateHelper.STATUS_BAR_COMPONENT).coversExactly(startingPos)
    }
    assertLayersEnd {
        this.visibleRegion(WindowManagerStateHelper.STATUS_BAR_COMPONENT).coversExactly(endingPos)
    }
}

/**
 * Asserts that:
 *     [originalLayer] is visible at the start of the trace
 *     [originalLayer] becomes invisible during the trace and (in the same entry) [newLayer]
 *         becomes visible
 *     [newLayer] remains visible until the end of the trace
 *
 * @param originalLayer Layer that should be visible at the start
 * @param newLayer Layer that should be visible at the end
 * @param ignoreSnapshot If the snapshot layer should be ignored during the transition
 *     (useful mostly for app launch)
 */
fun FlickerTestParameter.replacesLayer(
    originalLayer: ComponentName,
    newLayer: ComponentName,
    ignoreSnapshot: Boolean = false
) {
    assertLayers {
        val assertion = this.isVisible(originalLayer)
        if (ignoreSnapshot) {
            assertion.then()
                    .isVisible(WindowManagerStateHelper.SNAPSHOT_COMPONENT, isOptional = true)
        }
        assertion.then().isVisible(newLayer)
    }

    assertLayersStart {
        this.isVisible(originalLayer)
                .isInvisible(newLayer)
    }

    assertLayersEnd {
        this.isInvisible(originalLayer)
                .isVisible(newLayer)
    }
}
