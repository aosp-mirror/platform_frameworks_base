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

import com.android.server.wm.flicker.dsl.LayersAssertion
import com.android.server.wm.flicker.dsl.WmAssertion
import com.android.server.wm.flicker.helpers.WindowUtils

@JvmOverloads
fun WmAssertion.statusBarWindowIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("statusBarWindowIsAlwaysVisible", enabled, bugId) {
        this.showsAboveAppWindow(FlickerTestBase.STATUS_BAR_WINDOW_TITLE)
    }
}

@JvmOverloads
fun WmAssertion.navBarWindowIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("navBarWindowIsAlwaysVisible", enabled, bugId) {
        this.showsAboveAppWindow(FlickerTestBase.NAVIGATION_BAR_WINDOW_TITLE)
    }
}

@JvmOverloads
fun LayersAssertion.noUncoveredRegions(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    allStates: Boolean = true,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    val startingBounds = WindowUtils.getDisplayBounds(beginRotation)
    val endingBounds = WindowUtils.getDisplayBounds(endRotation)
    if (allStates) {
        all("noUncoveredRegions", enabled, bugId) {
            if (startingBounds == endingBounds) {
                this.coversRegion(startingBounds)
            } else {
                this.coversRegion(startingBounds)
                        .then()
                        .coversRegion(endingBounds)
            }
        }
    } else {
        start("noUncoveredRegions_StartingPos") {
            this.coversRegion(startingBounds)
        }
        end("noUncoveredRegions_EndingPos") {
            this.coversRegion(endingBounds)
        }
    }
}

@JvmOverloads
fun LayersAssertion.navBarLayerIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("navBarLayerIsAlwaysVisible", enabled, bugId) {
        this.showsLayer(FlickerTestBase.NAVIGATION_BAR_WINDOW_TITLE)
    }
}

@JvmOverloads
fun LayersAssertion.statusBarLayerIsAlwaysVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("statusBarLayerIsAlwaysVisible", enabled, bugId) {
        this.showsLayer(FlickerTestBase.STATUS_BAR_WINDOW_TITLE)
    }
}

@JvmOverloads
fun LayersAssertion.navBarLayerRotatesAndScales(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    val startingPos = WindowUtils.getNavigationBarPosition(beginRotation)
    val endingPos = WindowUtils.getNavigationBarPosition(endRotation)

    start("navBarLayerRotatesAndScales_StartingPos", enabled, bugId) {
        this.hasVisibleRegion(FlickerTestBase.NAVIGATION_BAR_WINDOW_TITLE, startingPos)
    }
    end("navBarLayerRotatesAndScales_EndingPost", enabled, bugId) {
        this.hasVisibleRegion(FlickerTestBase.NAVIGATION_BAR_WINDOW_TITLE, endingPos)
    }

    if (startingPos == endingPos) {
        all("navBarLayerRotatesAndScales", enabled, bugId) {
            this.hasVisibleRegion(FlickerTestBase.NAVIGATION_BAR_WINDOW_TITLE, startingPos)
        }
    }
}

@JvmOverloads
fun LayersAssertion.statusBarLayerRotatesScales(
    beginRotation: Int,
    endRotation: Int = beginRotation,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    val startingPos = WindowUtils.getStatusBarPosition(beginRotation)
    val endingPos = WindowUtils.getStatusBarPosition(endRotation)

    start("statusBarLayerRotatesScales_StartingPos", enabled, bugId) {
        this.hasVisibleRegion(FlickerTestBase.STATUS_BAR_WINDOW_TITLE, startingPos)
    }
    end("statusBarLayerRotatesScales_EndingPos", enabled, bugId) {
        this.hasVisibleRegion(FlickerTestBase.STATUS_BAR_WINDOW_TITLE, endingPos)
    }
}