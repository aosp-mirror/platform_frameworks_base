/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("ObjectLiteralToLambda")

package com.google.android.horologist.compose.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState.RotaryMode

/**
 * Default layouts for ScalingLazyColumnState, based on UX guidance.
 */
public object ScalingLazyColumnDefaults {
    /**
     * Layout the first item, directly under the time text.
     * This is positioned from the top of the screen instead of the
     * center.
     */
    @ExperimentalHorologistApi
    public fun belowTimeText(
            rotaryMode: RotaryMode = RotaryMode.Scroll,
            firstItemIsFullWidth: Boolean = false,
            verticalArrangement: Arrangement.Vertical =
                    Arrangement.spacedBy(
                            space = 4.dp,
                            alignment = Alignment.Top,
                    ),
            horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
            contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
            topPaddingDp: Dp = 32.dp + (if (firstItemIsFullWidth) 20.dp else 0.dp),
    ): ScalingLazyColumnState.Factory {
        return object : ScalingLazyColumnState.Factory {
            @Composable
            override fun create(): ScalingLazyColumnState {
                val density = LocalDensity.current
                val configuration = LocalConfiguration.current

                return remember {
                    val screenHeightPx =
                            with(density) { configuration.screenHeightDp.dp.roundToPx() }
                    val topPaddingPx = with(density) { topPaddingDp.roundToPx() }
                    val topScreenOffsetPx = screenHeightPx / 2 - topPaddingPx

                    ScalingLazyColumnState(
                            initialScrollPosition = ScalingLazyColumnState.ScrollPosition(
                                    index = 0,
                                    offsetPx = topScreenOffsetPx,
                            ),
                            anchorType = ScalingLazyListAnchorType.ItemStart,
                            rotaryMode = rotaryMode,
                            verticalArrangement = verticalArrangement,
                            horizontalAlignment = horizontalAlignment,
                            contentPadding = contentPadding,
                    )
                }
            }
        }
    }

    /**
     * Layout the item [initialCenterIndex] at [initialCenterOffset] from the
     * center of the screen.
     */
    @ExperimentalHorologistApi
    public fun scalingLazyColumnDefaults(
            rotaryMode: RotaryMode = RotaryMode.Scroll,
            initialCenterIndex: Int = 1,
            initialCenterOffset: Int = 0,
            verticalArrangement: Arrangement.Vertical =
                    Arrangement.spacedBy(
                            space = 4.dp,
                            alignment = Alignment.Top,
                    ),
            horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
            contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
            autoCentering: AutoCenteringParams? = AutoCenteringParams(
                    initialCenterIndex,
                    initialCenterOffset,
            ),
            anchorType: ScalingLazyListAnchorType = ScalingLazyListAnchorType.ItemCenter,
            hapticsEnabled: Boolean = true,
            reverseLayout: Boolean = false,
    ): ScalingLazyColumnState.Factory {
        return object : ScalingLazyColumnState.Factory {
            @Composable
            override fun create(): ScalingLazyColumnState {
                return remember {
                    ScalingLazyColumnState(
                            initialScrollPosition = ScalingLazyColumnState.ScrollPosition(
                                    index = initialCenterIndex,
                                    offsetPx = initialCenterOffset,
                            ),
                            rotaryMode = rotaryMode,
                            verticalArrangement = verticalArrangement,
                            horizontalAlignment = horizontalAlignment,
                            contentPadding = contentPadding,
                            autoCentering = autoCentering,
                            anchorType = anchorType,
                            hapticsEnabled = hapticsEnabled,
                            reverseLayout = reverseLayout,
                    )
                }
            }
        }
    }
}
