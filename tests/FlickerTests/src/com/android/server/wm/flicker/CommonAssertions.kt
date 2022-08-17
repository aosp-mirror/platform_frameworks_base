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
import com.android.server.wm.flicker.traces.region.RegionSubject
import com.android.server.wm.traces.common.ComponentMatcher
import com.android.server.wm.traces.common.IComponentMatcher

/**
 * Checks that [ComponentMatcher.STATUS_BAR] window is visible and above the app windows in
 * all WM trace entries
 */
fun FlickerTestParameter.statusBarWindowIsAlwaysVisible() {
    assertWm {
        this.isAboveAppWindowVisible(ComponentMatcher.STATUS_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.NAV_BAR] window is visible and above the app windows in
 * all WM trace entries
 */
fun FlickerTestParameter.navBarWindowIsAlwaysVisible() {
    assertWm {
        this.isAboveAppWindowVisible(ComponentMatcher.NAV_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.NAV_BAR] window is visible and above the app windows at the start
 * and end of the WM trace
 */
fun FlickerTestParameter.navBarWindowIsVisibleAtStartAndEnd() {
    assertWmStart {
        this.isAboveAppWindowVisible(ComponentMatcher.NAV_BAR)
    }
    assertWmEnd {
        this.isAboveAppWindowVisible(ComponentMatcher.NAV_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.TASK_BAR] window is visible and above the app windows in
 * all WM trace entries
 */
fun FlickerTestParameter.taskBarWindowIsAlwaysVisible() {
    assertWm {
        this.isAboveAppWindowVisible(ComponentMatcher.TASK_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.TASK_BAR] window is visible and above the app windows in
 * all WM trace entries
 */
fun FlickerTestParameter.taskBarWindowIsVisibleAtEnd() {
    assertWmEnd {
        this.isAboveAppWindowVisible(ComponentMatcher.TASK_BAR)
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

/**
 * Checks that [ComponentMatcher.NAV_BAR] layer is visible at the start and end of the SF
 * trace
 */
fun FlickerTestParameter.navBarLayerIsVisibleAtStartAndEnd() {
    assertLayersStart {
        this.isVisible(ComponentMatcher.NAV_BAR)
    }
    assertLayersEnd {
        this.isVisible(ComponentMatcher.NAV_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.TASK_BAR] layer is visible at the start and end of the SF
 * trace
 */
fun FlickerTestParameter.taskBarLayerIsVisibleAtStartAndEnd() {
    this.taskBarLayerIsVisibleAtStart()
    this.taskBarLayerIsVisibleAtEnd()
}

/**
 * Checks that [ComponentMatcher.TASK_BAR] layer is visible at the start of the SF
 * trace
 */
fun FlickerTestParameter.taskBarLayerIsVisibleAtStart() {
    assertLayersStart {
        this.isVisible(ComponentMatcher.TASK_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.TASK_BAR] layer is visible at the end of the SF
 * trace
 */
fun FlickerTestParameter.taskBarLayerIsVisibleAtEnd() {
    assertLayersEnd {
        this.isVisible(ComponentMatcher.TASK_BAR)
    }
}

/**
 * Checks that [ComponentMatcher.STATUS_BAR] layer is visible at the start and end of the SF
 * trace
 */
fun FlickerTestParameter.statusBarLayerIsVisibleAtStartAndEnd() {
    assertLayersStart {
        this.isVisible(ComponentMatcher.STATUS_BAR)
    }
    assertLayersEnd {
        this.isVisible(ComponentMatcher.STATUS_BAR)
    }
}

/**
 * Asserts that the [ComponentMatcher.NAV_BAR] layer is at the correct position at the start
 * of the SF trace
 */
fun FlickerTestParameter.navBarLayerPositionAtStart() {
    assertLayersStart {
        val display = this.entry.displays.firstOrNull { !it.isVirtual }
                ?: error("There is no display!")
        this.visibleRegion(ComponentMatcher.NAV_BAR)
            .coversExactly(WindowUtils.getNavigationBarPosition(display, isGesturalNavigation))
    }
}

/**
 * Asserts that the [ComponentMatcher.NAV_BAR] layer is at the correct position at the end
 * of the SF trace
 */
fun FlickerTestParameter.navBarLayerPositionAtEnd() {
    assertLayersEnd {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(ComponentMatcher.NAV_BAR)
            .coversExactly(WindowUtils.getNavigationBarPosition(display, isGesturalNavigation))
    }
}

/**
 * Asserts that the [ComponentMatcher.NAV_BAR] layer is at the correct position at the start
 * and end of the SF trace
 */
fun FlickerTestParameter.navBarLayerPositionAtStartAndEnd() {
    navBarLayerPositionAtStart()
    navBarLayerPositionAtEnd()
}

/**
 * Asserts that the [ComponentMatcher.STATUS_BAR] layer is at the correct position at the start
 * of the SF trace
 */
fun FlickerTestParameter.statusBarLayerPositionAtStart() {
    assertLayersStart {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(ComponentMatcher.STATUS_BAR)
            .coversExactly(WindowUtils.getStatusBarPosition(display))
    }
}

/**
 * Asserts that the [ComponentMatcher.STATUS_BAR] layer is at the correct position at the end
 * of the SF trace
 */
fun FlickerTestParameter.statusBarLayerPositionAtEnd() {
    assertLayersEnd {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(ComponentMatcher.STATUS_BAR)
            .coversExactly(WindowUtils.getStatusBarPosition(display))
    }
}

/**
 * Asserts that the [ComponentMatcher.STATUS_BAR] layer is at the correct position at the start
 * and end of the SF trace
 */
fun FlickerTestParameter.statusBarLayerPositionAtStartAndEnd() {
    statusBarLayerPositionAtStart()
    statusBarLayerPositionAtEnd()
}

/**
 * Asserts that the visibleRegion of the [ComponentMatcher.SNAPSHOT] layer can cover
 * the visibleRegion of the given app component exactly
 */
fun FlickerTestParameter.snapshotStartingWindowLayerCoversExactlyOnApp(
    component: IComponentMatcher
) {
    assertLayers {
        invoke("snapshotStartingWindowLayerCoversExactlyOnApp") {
            val snapshotLayers = it.subjects.filter { subject ->
                subject.name.contains(
                    ComponentMatcher.SNAPSHOT.toLayerName()) && subject.isVisible
            }
            // Verify the size of snapshotRegion covers appVisibleRegion exactly in animation.
            if (snapshotLayers.isNotEmpty()) {
                val visibleAreas = snapshotLayers.mapNotNull { snapshotLayer ->
                    snapshotLayer.layer?.visibleRegion
                }.toTypedArray()
                val snapshotRegion = RegionSubject.assertThat(visibleAreas, this, timestamp)
                val appVisibleRegion = it.visibleRegion(component)
                if (snapshotRegion.region.isNotEmpty) {
                    snapshotRegion.coversExactly(appVisibleRegion.region)
                }
            }
        }
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
 * @param ignoreEntriesWithRotationLayer If entries with a visible rotation layer should be ignored
 *      when checking the transition. If true we will not fail the assertion if a rotation layer is
 *      visible to fill the gap between the [originalLayer] being visible and the [newLayer] being
 *      visible.
 * @param ignoreSnapshot If the snapshot layer should be ignored during the transition
 *     (useful mostly for app launch)
 * @param ignoreSplashscreen If the splashscreen layer should be ignored during the transition.
 *      If true then we will allow for a splashscreen to be shown before the layer is shown,
 *      otherwise we won't and the layer must appear immediately.
 */
fun FlickerTestParameter.replacesLayer(
    originalLayer: IComponentMatcher,
    newLayer: IComponentMatcher,
    ignoreEntriesWithRotationLayer: Boolean = false,
    ignoreSnapshot: Boolean = false,
    ignoreSplashscreen: Boolean = true
) {
    assertLayers {
        val assertion = this.isVisible(originalLayer)

        if (ignoreEntriesWithRotationLayer) {
            assertion.then().isVisible(ComponentMatcher.ROTATION, isOptional = true)
        }
        if (ignoreSnapshot) {
            assertion.then().isVisible(ComponentMatcher.SNAPSHOT, isOptional = true)
        }
        if (ignoreSplashscreen) {
            assertion.then().isSplashScreenVisibleFor(newLayer, isOptional = true)
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
