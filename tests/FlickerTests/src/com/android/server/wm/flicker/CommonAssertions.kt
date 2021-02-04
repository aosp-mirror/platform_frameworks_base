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
import com.android.server.wm.flicker.dsl.EventLogAssertionBuilderLegacy
import com.android.server.wm.flicker.dsl.LayersAssertionBuilderLegacy
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
fun WmAssertionBuilderLegacy.statusBarWindowIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("statusBarWindowIsAlwaysVisible", bugId, enabled) {
        this.showsAboveAppWindow(STATUS_BAR_WINDOW_NAME)
    }
}

@JvmOverloads
fun WmAssertionBuilderLegacy.navBarWindowIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("navBarWindowIsAlwaysVisible", bugId, enabled) {
        this.showsAboveAppWindow(NAV_BAR_WINDOW_NAME)
    }
}

fun WmAssertionBuilderLegacy.visibleWindowsShownMoreThanOneConsecutiveEntry(
    ignoreWindows: List<String> = emptyList(),
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("visibleWindowsShownMoreThanOneConsecutiveEntry", bugId, enabled) {
        this.visibleWindowsShownMoreThanOneConsecutiveEntry(ignoreWindows)
    }
}

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

fun WmAssertionBuilderLegacy.appWindowAlwaysVisibleOnTop(
    packageName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("appWindowAlwaysVisibleOnTop", bugId, enabled) {
        this.showsAppWindowOnTop(packageName)
    }
}

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
                    hidesLayer(STATUS_BAR_LAYER_NAME)
                    .then()
                    .showsLayer(STATUS_BAR_LAYER_NAME)
        }
    } else {
        all("statusBarLayerIsAlwaysVisible", bugId, enabled) {
            this.showsLayer(STATUS_BAR_LAYER_NAME)
        }
    }
}

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

fun LayersAssertionBuilderLegacy.visibleLayersShownMoreThanOneConsecutiveEntry(
    ignoreLayers: List<String> = emptyList(),
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("visibleLayersShownMoreThanOneConsecutiveEntry", bugId, enabled) {
        this.visibleLayersShownMoreThanOneConsecutiveEntry(ignoreLayers)
    }
}

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

fun LayersAssertionBuilderLegacy.layerAlwaysVisible(
    packageName: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("layerAlwaysVisible", bugId, enabled) {
        this.showsLayer(packageName)
    }
}

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

fun EventLogAssertionBuilderLegacy.focusChanges(
    vararg windows: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("focusChanges", bugId, enabled) {
        this.focusChanges(windows)
    }
}

fun EventLogAssertionBuilderLegacy.focusDoesNotChange(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("focusDoesNotChange", bugId, enabled) {
        this.focusDoesNotChange()
    }
}