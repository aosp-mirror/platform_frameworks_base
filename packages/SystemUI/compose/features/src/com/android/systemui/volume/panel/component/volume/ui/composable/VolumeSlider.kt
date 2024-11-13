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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformSlider
import com.android.compose.PlatformSliderColors
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState

@Composable
fun VolumeSlider(
    state: SliderState,
    onValueChange: (newValue: Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    onIconTapped: () -> Unit,
    modifier: Modifier = Modifier,
    sliderColors: PlatformSliderColors,
) {
    val value by valueState(state)
    PlatformSlider(
        modifier =
            modifier.sysuiResTag(state.label).clearAndSetSemantics {
                if (state.isEnabled) {
                    contentDescription = state.label
                    state.a11yClickDescription?.let {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(it) {
                                    onIconTapped()
                                    true
                                }
                            )
                    }

                    state.a11yStateDescription?.let { stateDescription = it }
                    progressBarRangeInfo = ProgressBarRangeInfo(state.value, state.valueRange)
                } else {
                    disabled()
                    contentDescription =
                        state.disabledMessage?.let { "${state.label}, $it" } ?: state.label
                }
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
        onValueChangeFinished = onValueChangeFinished,
        enabled = state.isEnabled,
        icon = {
            state.icon?.let {
                SliderIcon(
                    icon = it,
                    onIconTapped = onIconTapped,
                    isTappable = state.isMutable,
                )
            }
        },
        colors = sliderColors,
        label = { isDragging ->
            AnimatedVisibility(
                visible = !isDragging,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) {
                VolumeSliderContent(
                    modifier = Modifier,
                    label = state.label,
                    isEnabled = state.isEnabled,
                    disabledMessage = state.disabledMessage,
                )
            }
        }
    )
}

@Composable
private fun valueState(state: SliderState): State<Float> {
    var prevState by remember { mutableStateOf(state) }
    // Don't animate slider value when receive the first value and when changing isEnabled state
    val shouldSkipAnimation =
        prevState is SliderState.Empty || prevState.isEnabled != state.isEnabled
    val value =
        if (shouldSkipAnimation) mutableFloatStateOf(state.value)
        else animateFloatAsState(targetValue = state.value, label = "VolumeSliderValueAnimation")
    prevState = state
    return value
}

@Composable
private fun SliderIcon(
    icon: Icon,
    onIconTapped: () -> Unit,
    isTappable: Boolean,
    modifier: Modifier = Modifier
) {
    val boxModifier =
        if (isTappable) {
                modifier.clickable(
                    onClick = onIconTapped,
                    interactionSource = null,
                    indication = null
                )
            } else {
                modifier
            }
            .fillMaxSize()
    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center,
        content = { Icon(modifier = Modifier.size(24.dp), icon = icon) },
    )
}
