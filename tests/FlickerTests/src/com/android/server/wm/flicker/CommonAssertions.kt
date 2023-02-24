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
import com.android.server.wm.traces.common.FlickerComponentName

val LAUNCHER_COMPONENT = FlickerComponentName(
    "com.google.android.apps.nexuslauncher",
    "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
)

/**
 * Checks that [FlickerComponentName.STATUS_BAR] window is visible and above the app windows in
 * all WM trace entries
 */
fun FlickerTestParameter.statusBarWindowIsVisible() {
    assertWm {
        this.isAboveAppWindowVisible(FlickerComponentName.STATUS_BAR)
    }
}

/**
 * Checks that [FlickerComponentName.NAV_BAR] window is visible and above the app windows in
 * all WM trace entries
 */
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

/**
 * Checks that [FlickerComponentName.NAV_BAR] layer is visible at the start and end of the SF
 * trace
 */
fun FlickerTestParameter.navBarLayerIsVisible() {
    assertLayersStart {
        this.isVisible(FlickerComponentName.NAV_BAR)
    }
    assertLayersEnd {
        this.isVisible(FlickerComponentName.NAV_BAR)
    }
}

/**
 * Checks that [FlickerComponentName.STATUS_BAR] layer is visible at the start and end of the SF
 * trace
 */
fun FlickerTestParameter.statusBarLayerIsVisible() {
    assertLayersStart {
        this.isVisible(FlickerComponentName.STATUS_BAR)
    }
    assertLayersEnd {
        this.isVisible(FlickerComponentName.STATUS_BAR)
    }
}

/**
 * Asserts that the [FlickerComponentName.NAV_BAR] layer is at the correct position at the start
 * of the SF trace
 */
fun FlickerTestParameter.navBarLayerPositionStart() {
    assertLayersStart {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.NAV_BAR)
            .coversExactly(WindowUtils.getNavigationBarPosition(display, isGesturalNavigation))
    }
}

/**
 * Asserts that the [FlickerComponentName.NAV_BAR] layer is at the correct position at the end
 * of the SF trace
 */
fun FlickerTestParameter.navBarLayerPositionEnd() {
    assertLayersEnd {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.NAV_BAR)
            .coversExactly(WindowUtils.getNavigationBarPosition(display, isGesturalNavigation))
    }
}

/**
 * Asserts that the [FlickerComponentName.NAV_BAR] layer is at the correct position at the start
 * and end of the SF trace
 */
fun FlickerTestParameter.navBarLayerRotatesAndScales() {
    navBarLayerPositionStart()
    navBarLayerPositionEnd()
}

/**
 * Asserts that the [FlickerComponentName.STATUS_BAR] layer is at the correct position at the start
 * of the SF trace
 */
fun FlickerTestParameter.statusBarLayerPositionStart() {
    assertLayersStart {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.STATUS_BAR)
            .coversExactly(WindowUtils.getStatusBarPosition(display))
    }
}

/**
 * Asserts that the [FlickerComponentName.STATUS_BAR] layer is at the correct position at the end
 * of the SF trace
 */
fun FlickerTestParameter.statusBarLayerPositionEnd() {
    assertLayersEnd {
        val display = this.entry.displays.minByOrNull { it.id }
            ?: throw RuntimeException("There is no display!")
        this.visibleRegion(FlickerComponentName.STATUS_BAR)
            .coversExactly(WindowUtils.getStatusBarPosition(display))
    }
}

/**
 * Asserts that the [FlickerComponentName.STATUS_BAR] layer is at the correct position at the start
 * and end of the SF trace
 */
fun FlickerTestParameter.statusBarLayerRotatesScales() {
    statusBarLayerPositionStart()
    statusBarLayerPositionEnd()
}

/**
 * Asserts that the visibleRegion of the [FlickerComponentName.SNAPSHOT] layer can cover
 * the visibleRegion of the given app component exactly
 */
fun FlickerTestParameter.snapshotStartingWindowLayerCoversExactlyOnApp(
        component: FlickerComponentName) {
    assertLayers {
        invoke("snapshotStartingWindowLayerCoversExactlyOnApp") {
            val snapshotLayers = it.subjects.filter { subject ->
                subject.name.contains(
                        FlickerComponentName.SNAPSHOT.toLayerName()) && subject.isVisible
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
    originalLayer: FlickerComponentName,
    newLayer: FlickerComponentName,
    ignoreEntriesWithRotationLayer: Boolean = false,
    ignoreSnapshot: Boolean = false,
    ignoreSplashscreen: Boolean = true
) {
    assertLayers {
        val assertion = this.isVisible(originalLayer)

        if (ignoreEntriesWithRotationLayer) {
            assertion.then().isVisible(FlickerComponentName.ROTATION, isOptional = true)
        }
        if (ignoreSnapshot) {
            assertion.then().isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
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
