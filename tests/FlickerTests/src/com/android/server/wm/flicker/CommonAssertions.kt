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

package com.android.server.wm.flicker

import android.platform.helpers.IAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.NAV_BAR_LAYER_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.NAV_BAR_WINDOW_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.STATUS_BAR_LAYER_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.STATUS_BAR_WINDOW_NAME

val HOME_WINDOW_TITLE = arrayOf("Wallpaper", "Launcher")

fun FlickerTestParameter.statusBarWindowIsAlwaysVisible() {
    assertWm {
        this.showsAboveAppWindow(STATUS_BAR_WINDOW_NAME)
    }
}

fun FlickerTestParameter.navBarWindowIsAlwaysVisible() {
    assertWm {
        this.showsAboveAppWindow(NAV_BAR_WINDOW_NAME)
    }
}

fun FlickerTestParameter.launcherReplacesAppWindowAsTopWindow(testApp: IAppHelper) {
    assertWm {
        this.showsAppWindowOnTop(testApp.getPackage())
            .then()
            .showsAppWindowOnTop(*HOME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.launcherWindowBecomesVisible() {
    assertWm {
        this.hidesBelowAppWindow(*HOME_WINDOW_TITLE)
            .then()
            .showsBelowAppWindow(*HOME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.launcherWindowBecomesInvisible() {
    assertWm {
        this.showsBelowAppWindow(*HOME_WINDOW_TITLE)
            .then()
            .hidesBelowAppWindow(*HOME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.appWindowAlwaysVisibleOnTop(packageName: String) {
    assertWm {
        this.showsAppWindowOnTop(packageName)
    }
}

fun FlickerTestParameter.appWindowBecomesVisible(appName: String) {
    assertWm {
        this.hidesAppWindow(appName)
            .then()
            .showsAppWindow(appName)
    }
}

fun FlickerTestParameter.appWindowBecomesInVisible(appName: String) {
    assertWm {
        this.showsAppWindow(appName)
            .then()
            .hidesAppWindow(appName)
    }
}

@JvmOverloads
fun FlickerTestParameter.noUncoveredRegions(
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

@JvmOverloads
fun FlickerTestParameter.navBarLayerIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertLayers {
            this.isVisible(NAV_BAR_LAYER_NAME)
                .then()
                .isInvisible(NAV_BAR_LAYER_NAME)
                .then()
                .isVisible(NAV_BAR_LAYER_NAME)
        }
    } else {
        assertLayers {
            this.isVisible(NAV_BAR_LAYER_NAME)
        }
    }
}

@JvmOverloads
fun FlickerTestParameter.statusBarLayerIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertLayers {
            this.isVisible(STATUS_BAR_LAYER_NAME)
                .then()
                .isInvisible(STATUS_BAR_LAYER_NAME)
                .then()
                .isVisible(STATUS_BAR_LAYER_NAME)
        }
    } else {
        assertLayers {
            this.isVisible(STATUS_BAR_LAYER_NAME)
        }
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
        this.visibleRegion(NAV_BAR_LAYER_NAME).coversExactly(startingPos)
    }
    assertLayersEnd {
        this.visibleRegion(NAV_BAR_LAYER_NAME).coversExactly(endingPos)
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
        this.visibleRegion(STATUS_BAR_LAYER_NAME).coversExactly(startingPos)
    }
    assertLayersEnd {
        this.visibleRegion(STATUS_BAR_LAYER_NAME).coversExactly(endingPos)
    }
}

fun FlickerTestParameter.appLayerReplacesLauncher(appName: String) {
    assertLayers {
        this.isVisible(*HOME_WINDOW_TITLE)
            .then()
            .isVisible(appName)
    }
}

fun FlickerTestParameter.launcherLayerReplacesApp(testApp: IAppHelper) {
    assertLayers {
        this.isVisible(testApp.getPackage())
            .then()
            .isInvisible(testApp.getPackage())
            .isVisible(*HOME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.layerBecomesVisible(packageName: String) {
    assertLayers {
        this.isInvisible(packageName)
            .then()
            .isVisible(packageName)
    }
}

fun FlickerTestParameter.layerBecomesInvisible(packageName: String) {
    assertLayers {
        this.isVisible(packageName)
            .then()
            .isInvisible(packageName)
    }
}

fun FlickerTestParameter.focusChanges(vararg windows: String) {
    assertEventLog {
        this.focusChanges(windows)
    }
}

fun FlickerTestParameter.focusDoesNotChange() {
    assertEventLog {
        this.focusDoesNotChange()
    }
}