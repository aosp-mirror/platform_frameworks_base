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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformSlider
import com.android.compose.PlatformSliderColors
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState

@Composable
fun VolumeSlider(
    state: SliderState,
    onValueChange: (newValue: Float) -> Unit,
    onIconTapped: () -> Unit,
    modifier: Modifier = Modifier,
    sliderColors: PlatformSliderColors,
) {
    val value by
        animateFloatAsState(targetValue = state.value, label = "VolumeSliderValueAnimation")
    PlatformSlider(
        modifier =
            modifier.clearAndSetSemantics {
                if (!state.isEnabled) disabled()
                contentDescription = state.label

                // provide a not animated value to the a11y because it fails to announce the settled
                // value when it changes rapidly.
                progressBarRangeInfo = ProgressBarRangeInfo(state.value, state.valueRange)
                setProgress { targetValue ->
                    val targetDirection =
                        when {
                            targetValue > value -> 1
                            targetValue < value -> -1
                            else -> 0
                        }

                    val newValue =
                        (value + targetDirection * state.a11yStep).coerceIn(
                            state.valueRange.start,
                            state.valueRange.endInclusive
                        )
                    onValueChange(newValue)
                    true
                }
            },
        value = value,
        valueRange = state.valueRange,
        onValueChange = onValueChange,
        enabled = state.isEnabled,
        icon = { isDragging ->
            if (isDragging) {
                Text(text = value.toInt().toString(), color = LocalContentColor.current)
            } else {
                state.icon?.let {
                    IconButton(
                        onClick = onIconTapped,
                        colors =
                            IconButtonColors(
                                contentColor = LocalContentColor.current,
                                containerColor = Color.Transparent,
                                disabledContentColor = LocalContentColor.current,
                                disabledContainerColor = Color.Transparent,
                            )
                    ) {
                        Icon(modifier = Modifier.size(24.dp), icon = it)
                    }
                }
            }
        },
        colors = sliderColors,
        label = {
            Column(modifier = Modifier.animateContentSize()) {
                Text(
                    modifier = Modifier.basicMarquee(),
                    text = state.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalContentColor.current,
                    maxLines = 1,
                )

                state.disabledMessage?.let { message ->
                    AnimatedVisibility(
                        !state.isEnabled,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
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
            }
        }
    )
}
