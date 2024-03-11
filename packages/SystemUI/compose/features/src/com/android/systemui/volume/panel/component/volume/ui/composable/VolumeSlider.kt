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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformSlider
import com.android.compose.PlatformSliderColors
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState

@Composable
fun VolumeSlider(
    state: SliderState,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    sliderColors: PlatformSliderColors,
) {
    var value by remember(state.value) { mutableFloatStateOf(state.value) }
    PlatformSlider(
        modifier = modifier,
        value = value,
        valueRange = state.valueRange,
        onValueChange = { value = it },
        onValueChangeFinished = { onValueChangeFinished(value) },
        enabled = state.isEnabled,
        icon = { isDragging ->
            if (isDragging) {
                Text(text = value.toInt().toString())
            } else {
                state.icon?.let { Icon(modifier = Modifier.size(24.dp), icon = it) }
            }
        },
        colors = sliderColors,
        label = {
            Column(modifier = Modifier.animateContentSize()) {
                Text(
                    modifier = Modifier.basicMarquee(),
                    text = state.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )

                state.disabledMessage?.let { message ->
                    AnimatedVisibility(
                        !state.isEnabled,
                        enter = expandVertically { it },
                        exit = shrinkVertically { it },
                    ) {
                        Text(
                            modifier = Modifier.basicMarquee(),
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    )
}
