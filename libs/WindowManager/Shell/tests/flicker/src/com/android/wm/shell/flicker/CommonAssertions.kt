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
import com.android.server.wm.flicker.traces.layers.LayersTraceSubject
import com.android.server.wm.traces.common.IComponentMatcher
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

fun FlickerTestParameter.splitScreenDividerBecomesInvisible() {
    assertLayers {
        this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
            .then()
            .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
    }
}

fun FlickerTestParameter.layerBecomesVisible(
    component: IComponentMatcher
) {
    assertLayers {
        this.isInvisible(component)
            .then()
            .isVisible(component)
    }
}

fun FlickerTestParameter.layerBecomesInvisible(
    component: IComponentMatcher
) {
    assertLayers {
        this.isVisible(component)
            .then()
            .isInvisible(component)
    }
}

fun FlickerTestParameter.layerIsVisibleAtEnd(
    component: IComponentMatcher
) {
    assertLayersEnd {
        this.isVisible(component)
    }
}

fun FlickerTestParameter.layerKeepVisible(
    component: IComponentMatcher
) {
    assertLayers {
        this.isVisible(component)
    }
}

fun FlickerTestParameter.splitAppLayerBoundsBecomesVisible(
    component: IComponentMatcher,
    splitLeftTop: Boolean
) {
    assertLayers {
        this.isInvisible(component)
            .then()
            .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
            .isVisible(component)
            .then()
            .isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
            .splitAppLayerBoundsSnapToDivider(component, splitLeftTop, endRotation)
    }
}

fun FlickerTestParameter.splitAppLayerBoundsBecomesInvisible(
    component: IComponentMatcher,
    splitLeftTop: Boolean
) {
    assertLayers {
        this.splitAppLayerBoundsSnapToDivider(component, splitLeftTop, endRotation)
            .then()
            .isVisible(component, true)
            .then()
            .isInvisible(component)
    }
}

fun FlickerTestParameter.splitAppLayerBoundsIsVisibleAtEnd(
    component: IComponentMatcher,
    splitLeftTop: Boolean
) {
    assertLayersEnd {
        val dividerRegion = layer(SPLIT_SCREEN_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(component).coversAtMost(
            if (splitLeftTop) {
                getSplitLeftTopRegion(dividerRegion, endRotation)
            } else {
                getSplitRightBottomRegion(dividerRegion, endRotation)
            }
        )
    }
}

fun FlickerTestParameter.splitAppLayerBoundsKeepVisible(
    component: IComponentMatcher,
    splitLeftTop: Boolean
) {
    assertLayers {
        this.splitAppLayerBoundsSnapToDivider(component, splitLeftTop, endRotation)
    }
}

fun FlickerTestParameter.splitAppLayerBoundsChanges(
    component: IComponentMatcher,
    splitLeftTop: Boolean
) {
    assertLayers {
        if (splitLeftTop) {
            this.splitAppLayerBoundsSnapToDivider(component, splitLeftTop, endRotation)
                .then()
                .isInvisible(component)
                .then()
                .splitAppLayerBoundsSnapToDivider(component, splitLeftTop, endRotation)
        } else {
            this.splitAppLayerBoundsSnapToDivider(component, splitLeftTop, endRotation)
        }
    }
}

fun LayersTraceSubject.splitAppLayerBoundsSnapToDivider(
    component: IComponentMatcher,
    splitLeftTop: Boolean,
    rotation: Int
): LayersTraceSubject {
    return invoke("splitAppLayerBoundsSnapToDivider") {
        val dividerRegion = it.layer(SPLIT_SCREEN_DIVIDER_COMPONENT).visibleRegion.region
        it.visibleRegion(component).coversAtMost(
            if (splitLeftTop) {
                getSplitLeftTopRegion(dividerRegion, rotation)
            } else {
                getSplitRightBottomRegion(dividerRegion, rotation)
            }
        )
    }
}

fun FlickerTestParameter.appWindowBecomesVisible(
    component: IComponentMatcher
) {
    assertWm {
        this.isAppWindowInvisible(component)
            .then()
            .isAppWindowVisible(component)
    }
}

fun FlickerTestParameter.appWindowBecomesInvisible(
    component: IComponentMatcher
) {
    assertWm {
        this.isAppWindowVisible(component)
            .then()
            .isAppWindowInvisible(component)
    }
}

fun FlickerTestParameter.appWindowIsVisibleAtEnd(
    component: IComponentMatcher
) {
    assertWmEnd {
        this.isAppWindowVisible(component)
    }
}

fun FlickerTestParameter.appWindowKeepVisible(
        component: IComponentMatcher
) {
    assertWm {
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
    primaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion = layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(primaryComponent)
            .overlaps(getPrimaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTestParameter.dockedStackPrimaryBoundsIsVisibleAtEnd(
    rotation: Int,
    primaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion = layer(DOCKED_STACK_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(primaryComponent)
            .overlaps(getPrimaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTestParameter.appPairsSecondaryBoundsIsVisibleAtEnd(
    rotation: Int,
    secondaryComponent: IComponentMatcher
) {
    assertLayersEnd {
        val dividerRegion = layer(APP_PAIR_SPLIT_DIVIDER_COMPONENT).visibleRegion.region
        visibleRegion(secondaryComponent)
            .overlaps(getSecondaryRegion(dividerRegion, rotation))
    }
}

fun FlickerTestParameter.dockedStackSecondaryBoundsIsVisibleAtEnd(
    rotation: Int,
    secondaryComponent: IComponentMatcher
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
        Region.from(
            0,
            0,
            (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
            displayBounds.bounds.bottom)
    } else {
        Region.from(
            0,
            0,
            displayBounds.bounds.right,
            (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2)
    }
}

fun getSplitRightBottomRegion(dividerRegion: Region, rotation: Int): Region {
    val displayBounds = WindowUtils.getDisplayBounds(rotation)
    return if (displayBounds.width > displayBounds.height) {
        Region.from(
            (dividerRegion.bounds.left + dividerRegion.bounds.right) / 2,
            0,
            displayBounds.bounds.right,
            displayBounds.bounds.bottom
        )
    } else {
        Region.from(
            0,
            (dividerRegion.bounds.top + dividerRegion.bounds.bottom) / 2,
            displayBounds.bounds.right,
            displayBounds.bounds.bottom
        )
    }
}
