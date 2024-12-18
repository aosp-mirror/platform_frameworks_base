/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.panel.component.volume.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.launch

private enum class VolumeSliderContentComponent {
    Label,
    DisabledMessage,
}

/** Shows label of the [VolumeSlider]. Also shows [disabledMessage] when not [isEnabled]. */
@Composable
fun VolumeSliderContent(
    label: String,
    isEnabled: Boolean,
    disabledMessage: String?,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.animateContentHeight(),
        content = {
            Text(
                modifier = Modifier.layoutId(VolumeSliderContentComponent.Label).basicMarquee(),
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current,
                maxLines = 1,
            )

            disabledMessage?.let { message ->
                AnimatedVisibility(
                    modifier = Modifier.layoutId(VolumeSliderContentComponent.DisabledMessage),
                    visible = !isEnabled,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Text(
                        modifier = Modifier.basicMarquee(),
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current,
                        maxLines = 1,
                    )
                }
            }
        },
        measurePolicy = VolumeSliderContentMeasurePolicy(isEnabled)
    )
}

/**
 * Uses [VolumeSliderContentComponent.Label] width when [isEnabled] and max available width
 * otherwise. This ensures that the slider always have the correct measurement to position the
 * content.
 */
private class VolumeSliderContentMeasurePolicy(private val isEnabled: Boolean) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints
    ): MeasureResult {
        val labelPlaceable =
            measurables
                .fastFirst { it.layoutId == VolumeSliderContentComponent.Label }
                .measure(constraints)
        val layoutWidth: Int =
            if (isEnabled) {
                labelPlaceable.width
            } else {
                constraints.maxWidth
            }
        val fullLayoutWidth: Int =
            if (isEnabled) {
                // PlatformSlider uses half of the available space for the enabled state.
                // This is using it to allow disabled message to take whole space when animating to
                // prevent it from jumping left to right
                constraints.maxWidth * 2
            } else {
                constraints.maxWidth
            }

        val disabledMessagePlaceable =
            measurables
                .fastFirstOrNull { it.layoutId == VolumeSliderContentComponent.DisabledMessage }
                ?.measure(constraints.copy(maxWidth = fullLayoutWidth))

        val layoutHeight = labelPlaceable.height + (disabledMessagePlaceable?.height ?: 0)
        return layout(layoutWidth, layoutHeight) {
            labelPlaceable.placeRelative(0, 0, 0f)
            disabledMessagePlaceable?.placeRelative(0, labelPlaceable.height, 0f)
        }
    }
}

/** Animates composable height changes. */
@Composable
private fun Modifier.animateContentHeight(): Modifier {
    var heightAnimation by remember { mutableStateOf<Animatable<Int, AnimationVector1D>?>(null) }
    val coroutineScope = rememberCoroutineScope()
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val currentAnimation = heightAnimation
        val anim =
            if (currentAnimation == null) {
                Animatable(placeable.height, Int.VectorConverter).also { heightAnimation = it }
            } else {
                coroutineScope.launch { currentAnimation.animateTo(placeable.height) }
                currentAnimation
            }
        layout(placeable.width, anim.value) { placeable.place(0, 0) }
    }
}
