/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.dialog.sliders.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastFirst
import kotlin.math.min

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
fun VolumeDialogSliderTrack(
    sliderState: SliderState,
    colors: SliderColors,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    thumbTrackGapSize: Dp = 6.dp,
    trackCornerSize: Dp = 12.dp,
    trackInsideCornerSize: Dp = 2.dp,
    trackSize: Dp = 40.dp,
    activeTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    activeTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
) {
    val measurePolicy = remember(sliderState) { TrackMeasurePolicy(sliderState) }
    Layout(
        measurePolicy = measurePolicy,
        content = {
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = colors,
                enabled = isEnabled,
                trackCornerSize = trackCornerSize,
                trackInsideCornerSize = trackInsideCornerSize,
                drawStopIndicator = null,
                thumbTrackGapSize = thumbTrackGapSize,
                drawTick = { _, _ -> },
                modifier = Modifier.width(trackSize).layoutId(Contents.Track),
            )

            TrackIcon(
                icon = activeTrackStartIcon,
                contentsId = Contents.Active.TrackStartIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
            TrackIcon(
                icon = activeTrackEndIcon,
                contentsId = Contents.Active.TrackEndIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
            TrackIcon(
                icon = inactiveTrackStartIcon,
                contentsId = Contents.Inactive.TrackStartIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
            TrackIcon(
                icon = inactiveTrackEndIcon,
                contentsId = Contents.Inactive.TrackEndIcon,
                isEnabled = isEnabled,
                colors = colors,
                state = measurePolicy,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun TrackIcon(
    icon: (@Composable BoxScope.(sliderIconsState: SliderIconsState) -> Unit)?,
    isEnabled: Boolean,
    contentsId: Contents,
    state: SliderIconsState,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    icon ?: return
    Box(modifier = modifier.layoutId(contentsId).fillMaxSize()) {
        CompositionLocalProvider(
            LocalContentColor provides contentsId.getColor(colors, isEnabled)
        ) {
            icon(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class TrackMeasurePolicy(private val sliderState: SliderState) :
    MeasurePolicy, SliderIconsState {

    private val isVisible: Map<Contents, MutableState<Boolean>> =
        mutableMapOf(
            Contents.Active.TrackStartIcon to mutableStateOf(false),
            Contents.Active.TrackEndIcon to mutableStateOf(false),
            Contents.Inactive.TrackStartIcon to mutableStateOf(false),
            Contents.Inactive.TrackEndIcon to mutableStateOf(false),
        )

    override val isActiveTrackStartIconVisible: Boolean
        get() = isVisible.getValue(Contents.Active.TrackStartIcon).value

    override val isActiveTrackEndIconVisible: Boolean
        get() = isVisible.getValue(Contents.Active.TrackEndIcon).value

    override val isInactiveTrackStartIconVisible: Boolean
        get() = isVisible.getValue(Contents.Inactive.TrackStartIcon).value

    override val isInactiveTrackEndIconVisible: Boolean
        get() = isVisible.getValue(Contents.Inactive.TrackEndIcon).value

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val track = measurables.fastFirst { it.layoutId == Contents.Track }.measure(constraints)

        val iconSize = min(track.width, track.height)
        val iconConstraints = constraints.copy(maxWidth = iconSize, maxHeight = iconSize)

        val icons =
            measurables
                .fastFilter { it.layoutId != Contents.Track }
                .associateBy(
                    keySelector = { it.layoutId as Contents },
                    valueTransform = { it.measure(iconConstraints) },
                )

        return layout(track.width, track.height) {
            with(Contents.Track) {
                performPlacing(
                    placeable = track,
                    width = track.width,
                    height = track.height,
                    sliderState = sliderState,
                )
            }

            for (iconLayoutId in icons.keys) {
                with(iconLayoutId) {
                    performPlacing(
                        placeable = icons.getValue(iconLayoutId),
                        width = track.width,
                        height = track.height,
                        sliderState = sliderState,
                    )

                    isVisible.getValue(iconLayoutId).value =
                        isVisible(
                            placeable = icons.getValue(iconLayoutId),
                            width = track.width,
                            height = track.height,
                            sliderState = sliderState,
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private sealed interface Contents {

    data object Track : Contents {
        override fun Placeable.PlacementScope.performPlacing(
            placeable: Placeable,
            width: Int,
            height: Int,
            sliderState: SliderState,
        ) = placeable.place(x = 0, y = 0)

        override fun isVisible(
            placeable: Placeable,
            width: Int,
            height: Int,
            sliderState: SliderState,
        ) = true

        override fun getColor(sliderColors: SliderColors, isEnabled: Boolean): Color =
            error("Unsupported")
    }

    interface Active : Contents {
        override fun getColor(sliderColors: SliderColors, isEnabled: Boolean): Color {
            return if (isEnabled) {
                sliderColors.activeTickColor
            } else {
                sliderColors.disabledActiveTickColor
            }
        }

        data object TrackStartIcon : Active {
            override fun Placeable.PlacementScope.performPlacing(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ) =
                placeable.place(
                    x = 0,
                    y = (height * (1 - sliderState.coercedValueAsFraction)).toInt(),
                )

            override fun isVisible(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ): Boolean = (height * (sliderState.coercedValueAsFraction)).toInt() > placeable.height
        }

        data object TrackEndIcon : Active {
            override fun Placeable.PlacementScope.performPlacing(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ) = placeable.place(x = 0, y = (height - placeable.height))

            override fun isVisible(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ): Boolean = (height * (sliderState.coercedValueAsFraction)).toInt() > placeable.height
        }
    }

    interface Inactive : Contents {

        override fun getColor(sliderColors: SliderColors, isEnabled: Boolean): Color {
            return if (isEnabled) {
                sliderColors.inactiveTickColor
            } else {
                sliderColors.disabledInactiveTickColor
            }
        }

        data object TrackStartIcon : Inactive {
            override fun Placeable.PlacementScope.performPlacing(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ) {
                placeable.place(x = 0, y = 0)
            }

            override fun isVisible(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ): Boolean =
                (height * (1 - sliderState.coercedValueAsFraction)).toInt() > placeable.height
        }

        data object TrackEndIcon : Inactive {
            override fun Placeable.PlacementScope.performPlacing(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ) {
                placeable.place(
                    x = 0,
                    y =
                        (height * (1 - sliderState.coercedValueAsFraction)).toInt() -
                            placeable.height,
                )
            }

            override fun isVisible(
                placeable: Placeable,
                width: Int,
                height: Int,
                sliderState: SliderState,
            ): Boolean =
                (height * (1 - sliderState.coercedValueAsFraction)).toInt() > placeable.height
        }
    }

    fun Placeable.PlacementScope.performPlacing(
        placeable: Placeable,
        width: Int,
        height: Int,
        sliderState: SliderState,
    )

    fun isVisible(placeable: Placeable, width: Int, height: Int, sliderState: SliderState): Boolean

    fun getColor(sliderColors: SliderColors, isEnabled: Boolean): Color
}

/** Provides visibility state for each of the Slider's icons. */
interface SliderIconsState {
    val isActiveTrackStartIconVisible: Boolean
    val isActiveTrackEndIconVisible: Boolean
    val isInactiveTrackStartIconVisible: Boolean
    val isInactiveTrackEndIconVisible: Boolean
}
