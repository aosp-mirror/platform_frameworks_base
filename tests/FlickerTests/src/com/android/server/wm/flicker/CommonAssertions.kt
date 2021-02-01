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
import com.android.server.wm.flicker.dsl.EventLogAssertionBuilder
import com.android.server.wm.flicker.dsl.EventLogAssertionBuilderLegacy
import com.android.server.wm.flicker.dsl.LayersAssertionBuilder
import com.android.server.wm.flicker.dsl.LayersAssertionBuilderLegacy
import com.android.server.wm.flicker.dsl.WmAssertionBuilder
import com.android.server.wm.flicker.dsl.WmAssertionBuilderLegacy
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.NAV_BAR_LAYER_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.NAV_BAR_WINDOW_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.STATUS_BAR_LAYER_NAME
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper.Companion.STATUS_BAR_WINDOW_NAME

const val APP_PAIR_SPLIT_DIVIDER = "AppPairSplitDivider"
const val DOCKED_STACK_DIVIDER = "DockedStackDivider"
const val WALLPAPER_TITLE = "Wallpaper"

@JvmOverloads
fun WmAssertionBuilder.statusBarWindowIsAlwaysVisible(bugId: Int = 0) {
    all("statusBarWindowIsAlwaysVisible", bugId) {
        this.showsAboveAppWindow(NAV_BAR_LAYER_NAME)
    }
}

@JvmOverloads
fun WmAssertionBuilder.navBarWindowIsAlwaysVisible(bugId: Int = 0) {
    all("navBarWindowIsAlwaysVisible", bugId) {
        this.showsAboveAppWindow(NAV_BAR_LAYER_NAME)
    }
}

fun WmAssertionBuilder.visibleWindowsShownMoreThanOneConsecutiveEntry(
    ignoreWindows: List<String> = emptyList(),
    bugId: Int = 0
) {
    all("visibleWindowsShownMoreThanOneConsecutiveEntry", bugId) {
        this.visibleWindowsShownMoreThanOneConsecutiveEntry(ignoreWindows)
    }
}

fun WmAssertionBuilder.launcherReplacesAppWindowAsTopWindow(testApp: IAppHelper, bugId: Int = 0) {
    all("launcherReplacesAppWindowAsTopWindow", bugId) {
        this.showsAppWindowOnTop(testApp.getPackage())
                .then()
                .showsAppWindowOnTop("Launcher")
    }
}

fun WmAssertionBuilder.wallpaperWindowBecomesVisible(bugId: Int = 0) {
    all("wallpaperWindowBecomesVisible", bugId) {
        this.hidesBelowAppWindow(WALLPAPER_TITLE)
                .then()
                .showsBelowAppWindow(WALLPAPER_TITLE)
    }
}

fun WmAssertionBuilder.wallpaperWindowBecomesInvisible(bugId: Int = 0) {
    all("wallpaperWindowBecomesInvisible", bugId) {
        this.showsBelowAppWindow("Wallpaper")
                .then()
                .hidesBelowAppWindow("Wallpaper")
    }
}

fun WmAssertionBuilder.appWindowAlwaysVisibleOnTop(
    packageName: String,
    bugId: Int = 0
) {
    all("appWindowAlwaysVisibleOnTop", bugId) {
        this.showsAppWindowOnTop(packageName)
    }
}

fun WmAssertionBuilder.appWindowBecomesVisible(appName: String, bugId: Int = 0) {
    all("appWindowBecomesVisible", bugId) {
        this.hidesAppWindow(appName)
                .then()
                .showsAppWindow(appName)
    }
}

fun WmAssertionBuilder.appWindowBecomesInVisible(appName: String, bugId: Int = 0) {
    all("appWindowBecomesInVisible", bugId) {
        this.showsAppWindow(appName)
            .then()
            .hidesAppWindow(appName)
    }
}

@JvmOverloads
fun LayersAssertionBuilder.noUncoveredRegions(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    allStates: Boolean = true,
    bugId: Int = 0
) {
    val startingBounds = WindowUtils.getDisplayBounds(beginRotation)
    val endingBounds = WindowUtils.getDisplayBounds(endRotation)
    if (allStates) {
        all("noUncoveredRegions", bugId) {
            if (startingBounds == endingBounds) {
                this.coversAtLeastRegion(startingBounds)
            } else {
                this.coversAtLeastRegion(startingBounds)
                        .then()
                        .coversAtLeastRegion(endingBounds)
            }
        }
    } else {
        start("noUncoveredRegions_StartingPos") {
            this.coversAtLeastRegion(startingBounds)
        }
        end("noUncoveredRegions_EndingPos") {
            this.coversAtLeastRegion(endingBounds)
        }
    }
}

@JvmOverloads
fun LayersAssertionBuilder.navBarLayerIsAlwaysVisible(
    rotatesScreen: Boolean = false,
    bugId: Int = 0
) {
    if (rotatesScreen) {
        all("navBarLayerIsAlwaysVisible", bugId) {
            this.showsLayer(NAV_BAR_LAYER_NAME)
                    .then()
                    .hidesLayer(NAV_BAR_LAYER_NAME)
                    .then()
                    .showsLayer(NAV_BAR_LAYER_NAME)
        }
    } else {
        all("navBarLayerIsAlwaysVisible", bugId) {
            this.showsLayer(NAV_BAR_LAYER_NAME)
        }
    }
}

@JvmOverloads
fun LayersAssertionBuilder.statusBarLayerIsAlwaysVisible(
    rotatesScreen: Boolean = false,
    bugId: Int = 0
) {
    if (rotatesScreen) {
        all("statusBarLayerIsAlwaysVisible", bugId) {
            this.showsLayer(STATUS_BAR_WINDOW_NAME)
                    .then()
                    hidesLayer(STATUS_BAR_WINDOW_NAME)
                    .then()
                    .showsLayer(STATUS_BAR_WINDOW_NAME)
        }
    } else {
        all("statusBarLayerIsAlwaysVisible", bugId) {
            this.showsLayer(STATUS_BAR_WINDOW_NAME)
        }
    }
}

@JvmOverloads
fun LayersAssertionBuilder.navBarLayerRotatesAndScales(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    bugId: Int = 0
) {
    val startingPos = WindowUtils.getNavigationBarPosition(beginRotation)
    val endingPos = WindowUtils.getNavigationBarPosition(endRotation)

    start("navBarLayerRotatesAndScales_StartingPos", bugId) {
        this.hasVisibleRegion(NAV_BAR_LAYER_NAME, startingPos)
    }
    end("navBarLayerRotatesAndScales_EndingPost", bugId) {
        this.hasVisibleRegion(NAV_BAR_LAYER_NAME, endingPos)
    }

    /*if (startingPos == endingPos) {
        all("navBarLayerRotatesAndScales", enabled = false, bugId = 167747321) {
            this.hasVisibleRegion(NAVIGATION_BAR_WINDOW_TITLE, startingPos)
        }
    }*/
}

@JvmOverloads
fun LayersAssertionBuilder.statusBarLayerRotatesScales(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    bugId: Int = 0
) {
    val startingPos = WindowUtils.getStatusBarPosition(beginRotation)
    val endingPos = WindowUtils.getStatusBarPosition(endRotation)

    start("statusBarLayerRotatesScales_StartingPos", bugId) {
        this.hasVisibleRegion(STATUS_BAR_WINDOW_NAME, startingPos)
    }
    end("statusBarLayerRotatesScales_EndingPos", bugId) {
        this.hasVisibleRegion(STATUS_BAR_WINDOW_NAME, endingPos)
    }
}

fun LayersAssertionBuilder.visibleLayersShownMoreThanOneConsecutiveEntry(
    ignoreLayers: List<String> = emptyList(),
    bugId: Int = 0
) {
    all("visibleLayersShownMoreThanOneConsecutiveEntry", bugId) {
        this.visibleLayersShownMoreThanOneConsecutiveEntry(ignoreLayers)
    }
}

fun LayersAssertionBuilder.appLayerReplacesWallpaperLayer(appName: String, bugId: Int = 0) {
    all("appLayerReplacesWallpaperLayer", bugId) {
        this.showsLayer("Wallpaper")
                .then()
                .replaceVisibleLayer("Wallpaper", appName)
    }
}

fun LayersAssertionBuilder.wallpaperLayerReplacesAppLayer(testApp: IAppHelper, bugId: Int = 0) {
    all("appLayerReplacesWallpaperLayer", bugId) {
        this.showsLayer(testApp.getPackage())
                .then()
                .replaceVisibleLayer(testApp.getPackage(), WALLPAPER_TITLE)
    }
}

fun LayersAssertionBuilder.layerAlwaysVisible(packageName: String, bugId: Int = 0) {
    all("layerAlwaysVisible", bugId) {
        this.showsLayer(packageName)
    }
}

fun LayersAssertionBuilder.layerBecomesVisible(packageName: String, bugId: Int = 0) {
    all("layerBecomesVisible", bugId) {
        this.hidesLayer(packageName)
                .then()
                .showsLayer(packageName)
    }
}

fun LayersAssertionBuilder.layerBecomesInvisible(packageName: String, bugId: Int = 0) {
    all("layerBecomesInvisible", bugId) {
        this.showsLayer(packageName)
                .then()
                .hidesLayer(packageName)
    }
}

fun EventLogAssertionBuilder.focusChanges(vararg windows: String, bugId: Int = 0) {
    all("focusChanges", bugId) {
        this.focusChanges(windows)
    }
}

fun EventLogAssertionBuilder.focusDoesNotChange(bugId: Int = 0) {
    all("focusDoesNotChange", bugId) {
        this.focusDoesNotChange()
    }
}

@JvmOverloads
@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.statusBarWindowIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("statusBarWindowIsAlwaysVisible", bugId, enabled) {
        this.showsAboveAppWindow(STATUS_BAR_WINDOW_NAME)
    }
}

@JvmOverloads
@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.navBarWindowIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("navBarWindowIsAlwaysVisible", bugId, enabled) {
        this.showsAboveAppWindow(NAV_BAR_WINDOW_NAME)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.visibleWindowsShownMoreThanOneConsecutiveEntry(
    ignoreWindows: List<String> = emptyList(),
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("visibleWindowsShownMoreThanOneConsecutiveEntry", bugId, enabled) {
        this.visibleWindowsShownMoreThanOneConsecutiveEntry(ignoreWindows)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.launcherReplacesAppWindowAsTopWindow(
    testApp: IAppHelper,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("launcherReplacesAppWindowAsTopWindow", bugId, enabled) {
        this.showsAppWindowOnTop(testApp.getPackage())
            .then()
            .showsAppWindowOnTop("Launcher")
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.wallpaperWindowBecomesVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("wallpaperWindowBecomesVisible", bugId, enabled) {
        this.hidesBelowAppWindow(WALLPAPER_TITLE)
            .then()
            .showsBelowAppWindow(WALLPAPER_TITLE)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.wallpaperWindowBecomesInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("wallpaperWindowBecomesInvisible", bugId, enabled) {
        this.showsBelowAppWindow("Wallpaper")
            .then()
            .hidesBelowAppWindow("Wallpaper")
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.appWindowAlwaysVisibleOnTop(
    packageName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("appWindowAlwaysVisibleOnTop", bugId, enabled) {
        this.showsAppWindowOnTop(packageName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.appWindowBecomesVisible(
    appName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("appWindowBecomesVisible", bugId, enabled) {
        this.hidesAppWindow(appName)
            .then()
            .showsAppWindow(appName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun WmAssertionBuilderLegacy.appWindowBecomesInVisible(
    appName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("appWindowBecomesInVisible", bugId, enabled) {
        this.showsAppWindow(appName)
            .then()
            .hidesAppWindow(appName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
@JvmOverloads
fun LayersAssertionBuilderLegacy.noUncoveredRegions(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    allStates: Boolean = true,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    val startingBounds = WindowUtils.getDisplayBounds(beginRotation)
    val endingBounds = WindowUtils.getDisplayBounds(endRotation)
    if (allStates) {
        all("noUncoveredRegions", bugId, enabled) {
            if (startingBounds == endingBounds) {
                this.coversAtLeastRegion(startingBounds)
            } else {
                this.coversAtLeastRegion(startingBounds)
                    .then()
                    .coversAtLeastRegion(endingBounds)
            }
        }
    } else {
        start("noUncoveredRegions_StartingPos") {
            this.coversAtLeastRegion(startingBounds)
        }
        end("noUncoveredRegions_EndingPos") {
            this.coversAtLeastRegion(endingBounds)
        }
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
@JvmOverloads
fun LayersAssertionBuilderLegacy.navBarLayerIsAlwaysVisible(
    rotatesScreen: Boolean = false,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    if (rotatesScreen) {
        all("navBarLayerIsAlwaysVisible", bugId, enabled) {
            this.showsLayer(NAV_BAR_LAYER_NAME)
                .then()
                .hidesLayer(NAV_BAR_LAYER_NAME)
                .then()
                .showsLayer(NAV_BAR_LAYER_NAME)
        }
    } else {
        all("navBarLayerIsAlwaysVisible", bugId, enabled) {
            this.showsLayer(NAV_BAR_LAYER_NAME)
        }
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
@JvmOverloads
fun LayersAssertionBuilderLegacy.statusBarLayerIsAlwaysVisible(
    rotatesScreen: Boolean = false,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    if (rotatesScreen) {
        all("statusBarLayerIsAlwaysVisible", bugId, enabled) {
            this.showsLayer(STATUS_BAR_LAYER_NAME)
                .then()
                .hidesLayer(STATUS_BAR_LAYER_NAME)
                .then()
                .showsLayer(STATUS_BAR_LAYER_NAME)
        }
    } else {
        all("statusBarLayerIsAlwaysVisible", bugId, enabled) {
            this.showsLayer(STATUS_BAR_LAYER_NAME)
        }
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
@JvmOverloads
fun LayersAssertionBuilderLegacy.navBarLayerRotatesAndScales(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    val startingPos = WindowUtils.getNavigationBarPosition(beginRotation)
    val endingPos = WindowUtils.getNavigationBarPosition(endRotation)

    start("navBarLayerRotatesAndScales_StartingPos", bugId, enabled) {
        this.hasVisibleRegion(NAV_BAR_LAYER_NAME, startingPos)
    }
    end("navBarLayerRotatesAndScales_EndingPost", bugId, enabled) {
        this.hasVisibleRegion(NAV_BAR_LAYER_NAME, endingPos)
    }

    if (startingPos == endingPos) {
        all("navBarLayerRotatesAndScales", enabled = false, bugId = 167747321) {
            this.hasVisibleRegion(NAV_BAR_LAYER_NAME, startingPos)
        }
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
@JvmOverloads
fun LayersAssertionBuilderLegacy.statusBarLayerRotatesScales(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    val startingPos = WindowUtils.getStatusBarPosition(beginRotation)
    val endingPos = WindowUtils.getStatusBarPosition(endRotation)

    start("statusBarLayerRotatesScales_StartingPos", bugId, enabled) {
        this.hasVisibleRegion(STATUS_BAR_LAYER_NAME, startingPos)
    }
    end("statusBarLayerRotatesScales_EndingPos", bugId, enabled) {
        this.hasVisibleRegion(STATUS_BAR_LAYER_NAME, endingPos)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun LayersAssertionBuilderLegacy.visibleLayersShownMoreThanOneConsecutiveEntry(
    ignoreLayers: List<String> = kotlin.collections.emptyList(),
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("visibleLayersShownMoreThanOneConsecutiveEntry", bugId, enabled) {
        this.visibleLayersShownMoreThanOneConsecutiveEntry(ignoreLayers)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun LayersAssertionBuilderLegacy.appLayerReplacesWallpaperLayer(
    appName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("appLayerReplacesWallpaperLayer", bugId, enabled) {
        this.showsLayer("Wallpaper")
            .then()
            .replaceVisibleLayer("Wallpaper", appName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun LayersAssertionBuilderLegacy.wallpaperLayerReplacesAppLayer(
    testApp: IAppHelper,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("appLayerReplacesWallpaperLayer", bugId, enabled) {
        this.showsLayer(testApp.getPackage())
            .then()
            .replaceVisibleLayer(testApp.getPackage(), WALLPAPER_TITLE)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun LayersAssertionBuilderLegacy.layerAlwaysVisible(
    packageName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("layerAlwaysVisible", bugId, enabled) {
        this.showsLayer(packageName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun LayersAssertionBuilderLegacy.layerBecomesVisible(
    packageName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("layerBecomesVisible", bugId, enabled) {
        this.hidesLayer(packageName)
            .then()
            .showsLayer(packageName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun LayersAssertionBuilderLegacy.layerBecomesInvisible(
    packageName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("layerBecomesInvisible", bugId, enabled) {
        this.showsLayer(packageName)
            .then()
            .hidesLayer(packageName)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun EventLogAssertionBuilderLegacy.focusChanges(
    vararg windows: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("focusChanges", bugId, enabled) {
        this.focusChanges(windows)
    }
}

@Deprecated("Move the assertion into one of the specific blocks (presubmit, postsubmit, flaky)")
fun EventLogAssertionBuilderLegacy.focusDoesNotChange(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("focusDoesNotChange", bugId, enabled) {
        this.focusDoesNotChange()
    }
}