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

import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.traces.common.FlickerComponentName

val LAUNCHER_COMPONENT = FlickerComponentName("com.google.android.apps.nexuslauncher",
        "com.google.android.apps.nexuslauncher.NexusLauncherActivity")

fun FlickerTestParameter.statusBarWindowIsVisible() {
    assertWm {
        this.isAboveAppWindowVisible(FlickerComponentName.STATUS_BAR)
    }
}

fun FlickerTestParameter.navBarWindowIsVisible() {
    assertWm {
        this.isAboveAppWindowVisible(FlickerComponentName.NAV_BAR)
    }
}

/**
 * If [allStates] is true, checks if the stack space of all displays is fully covered
 * by any visible layer, during the whole transitions
 *
 * Otherwise, checks if the stack space of all displays is fully covered
 * by any visible layer, at the start and end of the transition
 *
 * @param allStates if all states should be checked, othersie, just initial and final
 */
@JvmOverloads
fun FlickerTestParameter.entireScreenCovered(allStates: Boolean = true) {
    if (allStates) {
        assertLayers {
            this.invoke("entireScreenCovered") { entry ->
                entry.entry.displays.forEach { display ->
                    entry.visibleRegion().coversAtLeast(display.layerStackSpace)
                }
            }
        }
    } else {
        assertLayersStart {
            this.entry.displays.forEach { display ->
                this.visibleRegion().coversAtLeast(display.layerStackSpace)
            }
        }
        assertLayersEnd {
            this.entry.displays.forEach { display ->
                this.visibleRegion().coversAtLeast(display.layerStackSpace)
            }
        }
    }
}

fun FlickerTestParameter.navBarLayerIsVisible() {
    assertLayersStart {
        this.isVisible(FlickerComponentName.NAV_BAR)
    }
    assertLayersEnd {
        this.isVisible(FlickerComponentName.NAV_BAR)
    }
}

fun FlickerTestParameter.statusBarLayerIsVisible() {
    assertLayersStart {
        this.isVisible(FlickerComponentName.STATUS_BAR)
    }
    assertLayersEnd {
        this.isVisible(FlickerComponentName.STATUS_BAR)
    }
}

fun FlickerTestParameter.navBarLayerRotatesAndScales() {
    assertLayersStart {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.NAV_BAR)
                .coversExactly(WindowUtils.getNavigationBarPosition(display))
    }
    assertLayersEnd {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.NAV_BAR)
                .coversExactly(WindowUtils.getNavigationBarPosition(display))
    }
}

fun FlickerTestParameter.statusBarLayerRotatesScales() {
    assertLayersStart {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.STATUS_BAR)
            .coversExactly(WindowUtils.getStatusBarPosition(display))
    }
    assertLayersEnd {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.STATUS_BAR)
            .coversExactly(WindowUtils.getStatusBarPosition(display))
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
    originalLayer: FlickerComponentName,
    newLayer: FlickerComponentName,
    ignoreSnapshot: Boolean = false
) {
    assertLayers {
        val assertion = this.isVisible(originalLayer)
        if (ignoreSnapshot) {
            assertion.then()
                    .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
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
