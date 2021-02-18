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
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.STATUS_BAR_WINDOW_NAME

const val APP_PAIR_SPLIT_DIVIDER = "AppPairSplitDivider"
const val DOCKED_STACK_DIVIDER = "DockedStackDivider"
const val WALLPAPER_TITLE = "Wallpaper"

fun FlickerTestParameter.statusBarWindowIsAlwaysVisible() {
    assertWm {
        this.showsAboveAppWindow(NAV_BAR_LAYER_NAME)
    }
}

fun FlickerTestParameter.navBarWindowIsAlwaysVisible() {
    assertWm {
        this.showsAboveAppWindow(NAV_BAR_LAYER_NAME)
    }
}

@JvmOverloads
fun FlickerTestParameter.visibleWindowsShownMoreThanOneConsecutiveEntry(
    ignoreWindows: List<String> = emptyList()
) {
    assertWm {
        this.visibleWindowsShownMoreThanOneConsecutiveEntry(ignoreWindows)
    }
}

fun FlickerTestParameter.launcherReplacesAppWindowAsTopWindow(testApp: IAppHelper) {
    assertWm {
        this.showsAppWindowOnTop(testApp.getPackage())
            .then()
            .showsAppWindowOnTop("Launcher")
    }
}

fun FlickerTestParameter.wallpaperWindowBecomesVisible() {
    assertWm {
        this.hidesBelowAppWindow(WALLPAPER_TITLE)
            .then()
            .showsBelowAppWindow(WALLPAPER_TITLE)
    }
}

fun FlickerTestParameter.wallpaperWindowBecomesInvisible() {
    assertWm {
        this.showsBelowAppWindow("Wallpaper")
            .then()
            .hidesBelowAppWindow("Wallpaper")
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
                this.coversAtLeastRegion(startingBounds)
            } else {
                this.coversAtLeastRegion(startingBounds)
                    .then()
                    .coversAtLeastRegion(endingBounds)
            }
        }
    } else {
        assertLayersStart {
            this.coversAtLeastRegion(startingBounds)
        }
        assertLayersEnd {
            this.coversAtLeastRegion(endingBounds)
        }
    }
}

@JvmOverloads
fun FlickerTestParameter.navBarLayerIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertLayers {
            this.showsLayer(NAV_BAR_LAYER_NAME)
                .then()
                .hidesLayer(NAV_BAR_LAYER_NAME)
                .then()
                .showsLayer(NAV_BAR_LAYER_NAME)
        }
    } else {
        assertLayers {
            this.showsLayer(NAV_BAR_LAYER_NAME)
        }
    }
}

@JvmOverloads
fun FlickerTestParameter.statusBarLayerIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertLayers {
            this.showsLayer(STATUS_BAR_WINDOW_NAME)
                .then()
            hidesLayer(STATUS_BAR_WINDOW_NAME)
                .then()
                .showsLayer(STATUS_BAR_WINDOW_NAME)
        }
    } else {
        assertLayers {
            this.showsLayer(STATUS_BAR_WINDOW_NAME)
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
        this.hasVisibleRegion(NAV_BAR_LAYER_NAME, startingPos)
    }
    assertLayersEnd {
        this.hasVisibleRegion(NAV_BAR_LAYER_NAME, endingPos)
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
        this.hasVisibleRegion(STATUS_BAR_WINDOW_NAME, startingPos)
    }
    assertLayersEnd {
        this.hasVisibleRegion(STATUS_BAR_WINDOW_NAME, endingPos)
    }
}

@JvmOverloads
fun FlickerTestParameter.visibleLayersShownMoreThanOneConsecutiveEntry(
    ignoreLayers: List<String> = emptyList()
) {
    assertLayers {
        this.visibleLayersShownMoreThanOneConsecutiveEntry(ignoreLayers)
    }
}

fun FlickerTestParameter.appLayerReplacesWallpaperLayer(appName: String) {
    assertLayers {
        this.showsLayer("Wallpaper")
            .then()
            .replaceVisibleLayer("Wallpaper", appName)
    }
}

fun FlickerTestParameter.wallpaperLayerReplacesAppLayer(testApp: IAppHelper) {
    assertLayers {
        this.showsLayer(testApp.getPackage())
            .then()
            .replaceVisibleLayer(testApp.getPackage(), WALLPAPER_TITLE)
    }
}

fun FlickerTestParameter.layerAlwaysVisible(packageName: String) {
    assertLayers {
        this.showsLayer(packageName)
    }
}

fun FlickerTestParameter.layerBecomesVisible(packageName: String) {
    assertLayers {
        this.hidesLayer(packageName)
            .then()
            .showsLayer(packageName)
    }
}

fun FlickerTestParameter.layerBecomesInvisible(packageName: String) {
    assertLayers {
        this.showsLayer(packageName)
            .then()
            .hidesLayer(packageName)
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