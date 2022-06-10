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
package com.android.wm.shell.flicker

import android.view.Surface
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.region.Region

fun FlickerTestParameter.appPairsDividerIsVisibleAtEnd() {
    assertLayersEnd {
        this.isVisible(APP_PAIR_SPLIT_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.appPairsDividerIsInvisibleAtEnd() {
    assertLayersEnd {
        this.notContains(APP_PAIR_SPLIT_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.appPairsDividerBecomesVisible() {
    assertLayers {
        this.isInvisible(DOCKED_STACK_DIVIDER_COMPONENT)
            .then()
            .isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.splitScreenDividerBecomesVisible() {
    layerBecomesVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
}

fun FlickerTestParameter.layerBecomesVisible(
    component: FlickerComponentName
) {
    assertLayers {
        this.isInvisible(component)
            .then()
            .isVisible(component)
    }
}

fun FlickerTestParameter.layerIsVisibleAtEnd(
    component: FlickerComponentName
) {
    assertLayersEnd {
        this.isVisible(component)
    }
}

fun FlickerTestParameter.splitAppLayerBoundsBecomesVisible(
    rotation: Int,
    component: FlickerComponentName,
    splitLeftTop: Boolean
) {
    assertLayers {
        val dividerRegion = this.last().layer(SPLIT_SCREEN_DIVIDER_COMPONENT).visibleRegion.region
        this.isInvisible(component)
            .then()
            .invoke("splitAppLayerBoundsBecomesVisible") {
                it.visibleRegion(component).overlaps(
                    if (splitLeftTop) {
                        getSplitLeftTopRegion(dividerRegion, rotation)
                    } else {
                        getSplitRightBottomRegion(dividerRegion, rotation)
                    }
                )
            }
    }
}

fun FlickerTestParameter.splitAppLayerBoundsIsVisibleAtEnd(
    rotation: Int,
    component: FlickerComponentName,
    splitLeftTop: Boolean
) {
    assertLayersEnd {
        val dividerRegion = layer(SPLIT_SCREEN_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(component).overlaps(
            if (splitLeftTop) {
                getSplitLeftTopRegion(dividerRegion, rotation)
            } else {
                getSplitRightBottomRegion(dividerRegion, rotation)
            }
        )
    }
}

fun FlickerTestParameter.appWindowBecomesVisible(
    component: FlickerComponentName
) {
    assertWm {
        this.isAppWindowInvisible(component)
            .then()
            .isAppWindowVisible(component)
    }
}

fun FlickerTestParameter.appWindowIsVisibleAtEnd(
    component: FlickerComponentName
) {
    assertWmEnd {
        this.isAppWindowVisible(component)
    }
}

fun FlickerTestParameter.dockedStackDividerIsVisibleAtEnd() {
    assertLayersEnd {
        this.isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.dockedStackDividerBecomesVisible() {
    assertLayers {
        this.isInvisible(DOCKED_STACK_DIVIDER_COMPONENT)
            .then()
            .isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.dockedStackDividerBecomesInvisible() {
    assertLayers {
        this.isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
            .then()
            .isInvisible(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.dockedStackDividerNotExistsAtEnd() {
    assertLayersEnd {
        this.notContains(DOCKED_STACK_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.appPairsPrimaryBoundsIsVisibleAtEnd(
    rotation: Int,
    primaryComponent: FlickerComponentName
) {
    assertLayersEnd {
        val dividerRegion = layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(primaryComponent)
            .overlaps(getPrimaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTestParameter.dockedStackPrimaryBoundsIsVisibleAtEnd(
    rotation: Int,
    primaryComponent: FlickerComponentName
) {
    assertLayersEnd {
        val dividerRegion = layer(DOCKED_STACK_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(primaryComponent)
            .overlaps(getPrimaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTestParameter.appPairsSecondaryBoundsIsVisibleAtEnd(
    rotation: Int,
    secondaryComponent: FlickerComponentName
) {
    assertLayersEnd {
        val dividerRegion = layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(secondaryComponent)
            .overlaps(getSecondaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTestParameter.dockedStackSecondaryBoundsIsVisibleAtEnd(
    rotation: Int,
    secondaryComponent: FlickerComponentName
) {
    assertLayersEnd {
        val dividerRegion = layer(DOCKED_STACK_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(secondaryComponent)
            .overlaps(getSecondaryRegion(dividerRegion, rotation))
    }
}

fun getPrimaryRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
        Region.from(
            0, 0, displayBounds.bounds.right,
            dividerRegion.bounds.top + WindowUtils.dockedStackDividerInset
        )
    } else {
        Region.from(
            0, 0, dividerRegion.bounds.left + WindowUtils.dockedStackDividerInset,
            displayBounds.bounds.bottom
        )
    }
}

fun getSecondaryRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
        Region.from(
            0, dividerRegion.bounds.bottom - WindowUtils.dockedStackDividerInset,
            displayBounds.bounds.right, displayBounds.bounds.bottom
        )
    } else {
        Region.from(
            dividerRegion.bounds.right - WindowUtils.dockedStackDividerInset, 0,
            displayBounds.bounds.right, displayBounds.bounds.bottom
        )
    }
}

fun getSplitLeftTopRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (displayBounds.width > displayBounds.height) {
        Region.from(0, 0, dividerRegion.bounds.left, displayBounds.bounds.bottom)
    } else {
        Region.from(0, 0, displayBounds.bounds.right, dividerRegion.bounds.top)
    }
}

fun getSplitRightBottomRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (displayBounds.width > displayBounds.height) {
        Region.from(
            dividerRegion.bounds.right, 0, displayBounds.bounds.right,
            displayBounds.bounds.bottom
        )
    } else {
        Region.from(
            0, dividerRegion.bounds.bottom, displayBounds.bounds.right,
            displayBounds.bounds.bottom
        )
    }
}
