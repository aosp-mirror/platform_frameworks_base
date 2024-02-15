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

import android.tools.common.PlatformConsts
import android.tools.common.Position
import android.tools.common.flicker.subject.layers.LayerTraceEntrySubject
import android.tools.common.flicker.subject.region.RegionSubject
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.IComponentNameMatcher
import android.tools.common.traces.wm.WindowManagerTrace
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.helpers.WindowUtils

/**
 * Checks that [ComponentNameMatcher.STATUS_BAR] window is visible and above the app windows in all
 * WM trace entries
 */
fun LegacyFlickerTest.statusBarWindowIsAlwaysVisible() {
    assertWm { this.isAboveAppWindowVisible(ComponentNameMatcher.STATUS_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.NAV_BAR] window is visible and above the app windows in all WM
 * trace entries
 */
fun LegacyFlickerTest.navBarWindowIsAlwaysVisible() {
    assertWm { this.isAboveAppWindowVisible(ComponentNameMatcher.NAV_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.NAV_BAR] window is visible and above the app windows at the
 * start and end of the WM trace
 */
fun LegacyFlickerTest.navBarWindowIsVisibleAtStartAndEnd() {
    this.navBarWindowIsVisibleAtStart()
    this.navBarWindowIsVisibleAtEnd()
}

/**
 * Checks that [ComponentNameMatcher.NAV_BAR] window is visible and above the app windows at the
 * start of the WM trace
 */
fun LegacyFlickerTest.navBarWindowIsVisibleAtStart() {
    assertWmStart { this.isAboveAppWindowVisible(ComponentNameMatcher.NAV_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.NAV_BAR] window is visible and above the app windows at the end
 * of the WM trace
 */
fun LegacyFlickerTest.navBarWindowIsVisibleAtEnd() {
    assertWmEnd { this.isAboveAppWindowVisible(ComponentNameMatcher.NAV_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.TASK_BAR] window is visible and above the app windows in all WM
 * trace entries
 */
fun LegacyFlickerTest.taskBarWindowIsAlwaysVisible() {
    assertWm { this.isAboveAppWindowVisible(ComponentNameMatcher.TASK_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.TASK_BAR] window is visible and above the app windows in all WM
 * trace entries
 */
fun LegacyFlickerTest.taskBarWindowIsVisibleAtEnd() {
    assertWmEnd { this.isAboveAppWindowVisible(ComponentNameMatcher.TASK_BAR) }
}

/**
 * If [allStates] is true, checks if the stack space of all displays is fully covered by any visible
 * layer, during the whole transitions
 *
 * Otherwise, checks if the stack space of all displays is fully covered by any visible layer, at
 * the start and end of the transition
 *
 * @param allStates if all states should be checked, othersie, just initial and final
 */
@JvmOverloads
fun LegacyFlickerTest.entireScreenCovered(allStates: Boolean = true) {
    if (allStates) {
        assertLayers {
            this.invoke("entireScreenCovered") { entry ->
                entry.entry.displays
                    .filter { it.isOn }
                    .forEach { display ->
                        entry.visibleRegion().coversAtLeast(display.layerStackSpace)
                    }
            }
        }
    } else {
        assertLayersStart {
            this.entry.displays
                .filter { it.isOn }
                .forEach { display -> this.visibleRegion().coversAtLeast(display.layerStackSpace) }
        }
        assertLayersEnd {
            this.entry.displays
                .filter { it.isOn }
                .forEach { display -> this.visibleRegion().coversAtLeast(display.layerStackSpace) }
        }
    }
}

/** Checks that [ComponentNameMatcher.NAV_BAR] layer is visible at the start of the SF trace */
fun LegacyFlickerTest.navBarLayerIsVisibleAtStart() {
    assertLayersStart { this.isVisible(ComponentNameMatcher.NAV_BAR) }
}

/** Checks that [ComponentNameMatcher.NAV_BAR] layer is visible at the end of the SF trace */
fun LegacyFlickerTest.navBarLayerIsVisibleAtEnd() {
    assertLayersEnd { this.isVisible(ComponentNameMatcher.NAV_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.NAV_BAR] layer is visible at the start and end of the SF trace
 */
fun LegacyFlickerTest.navBarLayerIsVisibleAtStartAndEnd() {
    this.navBarLayerIsVisibleAtStart()
    this.navBarLayerIsVisibleAtEnd()
}

/**
 * Checks that [ComponentNameMatcher.TASK_BAR] layer is visible at the start and end of the SF trace
 */
fun LegacyFlickerTest.taskBarLayerIsVisibleAtStartAndEnd() {
    this.taskBarLayerIsVisibleAtStart()
    this.taskBarLayerIsVisibleAtEnd()
}

/** Checks that [ComponentNameMatcher.TASK_BAR] layer is visible at the start of the SF trace */
fun LegacyFlickerTest.taskBarLayerIsVisibleAtStart() {
    assertLayersStart { this.isVisible(ComponentNameMatcher.TASK_BAR) }
}

/** Checks that [ComponentNameMatcher.TASK_BAR] layer is visible at the end of the SF trace */
fun LegacyFlickerTest.taskBarLayerIsVisibleAtEnd() {
    assertLayersEnd { this.isVisible(ComponentNameMatcher.TASK_BAR) }
}

/**
 * Checks that [ComponentNameMatcher.STATUS_BAR] layer is visible at the start and end of the SF
 * trace
 */
fun LegacyFlickerTest.statusBarLayerIsVisibleAtStartAndEnd() {
    assertLayersStart { this.isVisible(ComponentNameMatcher.STATUS_BAR) }
    assertLayersEnd { this.isVisible(ComponentNameMatcher.STATUS_BAR) }
}

/**
 * Asserts that the [ComponentNameMatcher.NAV_BAR] layer is at the correct position at the start of
 * the SF trace
 */
fun LegacyFlickerTest.navBarLayerPositionAtStart() {
    assertLayersStart { assertNavBarPosition(this, scenario.isGesturalNavigation) }
}

/**
 * Asserts that the [ComponentNameMatcher.NAV_BAR] layer is at the correct position at the end of
 * the SF trace
 */
fun LegacyFlickerTest.navBarLayerPositionAtEnd() {
    assertLayersEnd { assertNavBarPosition(this, scenario.isGesturalNavigation) }
}

private fun assertNavBarPosition(sfState: LayerTraceEntrySubject, isGesturalNavigation: Boolean) {
    val display =
        sfState.entry.displays.filterNot { it.isOff }.minByOrNull { it.id }
            ?: error("There is no display!")
    val displayArea = display.layerStackSpace
    val navBarPosition = display.navBarPosition(isGesturalNavigation)
    val navBarRegion = sfState.visibleRegion(ComponentNameMatcher.NAV_BAR)

    when (navBarPosition) {
        Position.TOP ->
            navBarRegion
                .hasSameTopPosition(displayArea)
                .hasSameLeftPosition(displayArea)
                .hasSameRightPosition(displayArea)
        Position.BOTTOM ->
            navBarRegion
                .hasSameBottomPosition(displayArea)
                .hasSameLeftPosition(displayArea)
                .hasSameRightPosition(displayArea)
        Position.LEFT ->
            navBarRegion
                .hasSameLeftPosition(displayArea)
                .hasSameTopPosition(displayArea)
                .hasSameBottomPosition(displayArea)
        Position.RIGHT ->
            navBarRegion
                .hasSameRightPosition(displayArea)
                .hasSameTopPosition(displayArea)
                .hasSameBottomPosition(displayArea)
        else -> error("Unknown position $navBarPosition")
    }
}

/**
 * Asserts that the [ComponentNameMatcher.NAV_BAR] layer is at the correct position at the start and
 * end of the SF trace
 */
fun LegacyFlickerTest.navBarLayerPositionAtStartAndEnd() {
    navBarLayerPositionAtStart()
    navBarLayerPositionAtEnd()
}

/**
 * Asserts that the [ComponentNameMatcher.STATUS_BAR] layer is at the correct position at the start
 * of the SF trace
 */
fun LegacyFlickerTest.statusBarLayerPositionAtStart(
    wmTrace: WindowManagerTrace? = this.reader.readWmTrace()
) {
    // collect navbar position for the equivalent WM state
    val state = wmTrace?.entries?.firstOrNull() ?: error("WM state missing in $this")
    val display = state.getDisplay(PlatformConsts.DEFAULT_DISPLAY) ?: error("Display not found")
    val navBarPosition = WindowUtils.getExpectedStatusBarPosition(display)
    assertLayersStart {
        this.visibleRegion(ComponentNameMatcher.STATUS_BAR).coversExactly(navBarPosition)
    }
}

/**
 * Asserts that the [ComponentNameMatcher.STATUS_BAR] layer is at the correct position at the end of
 * the SF trace
 */
fun LegacyFlickerTest.statusBarLayerPositionAtEnd(
    wmTrace: WindowManagerTrace? = this.reader.readWmTrace()
) {
    // collect navbar position for the equivalent WM state
    val state = wmTrace?.entries?.lastOrNull() ?: error("WM state missing in $this")
    val display = state.getDisplay(PlatformConsts.DEFAULT_DISPLAY) ?: error("Display not found")
    val navBarPosition = WindowUtils.getExpectedStatusBarPosition(display)
    assertLayersEnd {
        this.visibleRegion(ComponentNameMatcher.STATUS_BAR).coversExactly(navBarPosition)
    }
}

/**
 * Asserts that the [ComponentNameMatcher.STATUS_BAR] layer is at the correct position at the start
 * and end of the SF trace
 */
fun LegacyFlickerTest.statusBarLayerPositionAtStartAndEnd() {
    statusBarLayerPositionAtStart()
    statusBarLayerPositionAtEnd()
}

/**
 * Asserts that the visibleRegion of the [ComponentNameMatcher.SNAPSHOT] layer can cover the
 * visibleRegion of the given app component exactly
 */
fun LegacyFlickerTest.snapshotStartingWindowLayerCoversExactlyOnApp(
    component: IComponentNameMatcher
) {
    assertLayers {
        invoke("snapshotStartingWindowLayerCoversExactlyOnApp") {
            val snapshotLayers =
                it.subjects.filter { subject ->
                    ComponentNameMatcher.SNAPSHOT.layerMatchesAnyOf(subject.layer) &&
                        subject.isVisible
                }
            val visibleAreas =
                snapshotLayers
                    .mapNotNull { snapshotLayer -> snapshotLayer.layer.visibleRegion }
                    .toTypedArray()
            val snapshotRegion = RegionSubject(visibleAreas, it.timestamp)
            val appVisibleRegion = it.visibleRegion(component)
            // Verify the size of snapshotRegion covers appVisibleRegion exactly in animation.
            if (snapshotRegion.region.isNotEmpty && appVisibleRegion.region.isNotEmpty) {
                snapshotRegion.coversExactly(appVisibleRegion.region)
            }
        }
    }
}

/**
 * Asserts that:
 * ```
 *     [originalLayer] is visible at the start of the trace
 *     [originalLayer] becomes invisible during the trace and (in the same entry) [newLayer]
 *         becomes visible
 *     [newLayer] remains visible until the end of the trace
 *
 * @param originalLayer
 * ```
 *
 * Layer that should be visible at the start
 *
 * @param newLayer Layer that should be visible at the end
 * @param ignoreEntriesWithRotationLayer If entries with a visible rotation layer should be ignored
 *
 * ```
 *      when checking the transition. If true we will not fail the assertion if a rotation layer is
 *      visible to fill the gap between the [originalLayer] being visible and the [newLayer] being
 *      visible.
 * @param ignoreSnapshot
 * ```
 *
 * If the snapshot layer should be ignored during the transition
 *
 * ```
 *     (useful mostly for app launch)
 * @param ignoreSplashscreen
 * ```
 *
 * If the splashscreen layer should be ignored during the transition.
 *
 * ```
 *      If true then we will allow for a splashscreen to be shown before the layer is shown,
 *      otherwise we won't and the layer must appear immediately.
 * ```
 */
fun LegacyFlickerTest.replacesLayer(
    originalLayer: IComponentNameMatcher,
    newLayer: IComponentNameMatcher,
    ignoreEntriesWithRotationLayer: Boolean = false,
    ignoreSnapshot: Boolean = false,
    ignoreSplashscreen: Boolean = true
) {
    assertLayers {
        val assertion = this.isVisible(originalLayer)

        if (ignoreEntriesWithRotationLayer) {
            assertion.then().isVisible(ComponentNameMatcher.ROTATION, isOptional = true)
        }
        if (ignoreSnapshot) {
            assertion.then().isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
        }
        if (ignoreSplashscreen) {
            assertion.then().isSplashScreenVisibleFor(newLayer, isOptional = true)
        }

        assertion.then().isVisible(newLayer)
    }

    assertLayersStart { this.isVisible(originalLayer).isInvisible(newLayer) }

    assertLayersEnd { this.isInvisible(originalLayer).isVisible(newLayer) }
}
