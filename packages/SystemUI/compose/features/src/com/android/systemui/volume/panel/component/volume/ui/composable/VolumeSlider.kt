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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import kotlin.math.round
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun VolumeSlider(
    state: SliderState,
    onValueChange: (newValue: Float) -> Unit,
    onIconTapped: () -> Unit,
    sliderColors: PlatformSliderColors,
    modifier: Modifier = Modifier,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory?,
    onValueChangeFinished: (() -> Unit)? = null,
    button: (@Composable () -> Unit)? = null,
) {
    if (!Flags.volumeRedesign()) {
        LegacyVolumeSlider(
            state = state,
            onValueChange = onValueChange,
            onIconTapped = onIconTapped,
            sliderColors = sliderColors,
            onValueChangeFinished = onValueChangeFinished,
            modifier = modifier,
            hapticsViewModelFactory = hapticsViewModelFactory,
        )
        return
    }

    val value by valueState(state)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel? =
        setUpHapticsViewModel(value, state.valueRange, interactionSource, hapticsViewModelFactory)

    Column(modifier = modifier.animateContentSize(), verticalArrangement = Arrangement.Top) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.icon?.let {
                Icon(
                    icon = it,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = state.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            button?.invoke()
        }
        Slider(
            value = value,
            valueRange = state.valueRange,
            onValueChange = { newValue ->
                hapticsViewModel?.addVelocityDataPoint(newValue)
                onValueChange(newValue)
            },
            onValueChangeFinished = {
                hapticsViewModel?.onValueChangeEnded()
                onValueChangeFinished?.invoke()
            },
            enabled = state.isEnabled,
            modifier =
                Modifier.height(40.dp)
                    .padding(top = 4.dp, bottom = 12.dp)
                    .sysuiResTag(state.label)
                    .clearAndSetSemantics {
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
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(state.value, state.valueRange)
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
                                    state.valueRange.endInclusive,
                                )
                            onValueChange(newValue)
                            true
                        }
                    },
        )
        state.disabledMessage?.let { disabledMessage ->
            AnimatedVisibility(visible = !state.isEnabled) {
                Row(
                    modifier = Modifier.padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaterialIcon(
                        painter = painterResource(R.drawable.ic_error_outline),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = disabledMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyVolumeSlider(
    state: SliderState,
    onValueChange: (newValue: Float) -> Unit,
    onIconTapped: () -> Unit,
    sliderColors: PlatformSliderColors,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory?,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val value by valueState(state)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel? =
        setUpHapticsViewModel(value, state.valueRange, interactionSource, hapticsViewModelFactory)

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
                            state.valueRange.endInclusive,
                        )
                    onValueChange(newValue)
                    true
                }
            },
        value = value,
        valueRange = state.valueRange,
        onValueChange = { newValue ->
            hapticsViewModel?.addVelocityDataPoint(newValue)
            onValueChange(newValue)
        },
        onValueChangeFinished = {
            hapticsViewModel?.onValueChangeEnded()
            onValueChangeFinished?.invoke()
        },
        enabled = state.isEnabled,
        icon = {
            state.icon?.let {
                SliderIcon(icon = it, onIconTapped = onIconTapped, isTappable = state.isMutable)
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
        },
        interactionSource = interactionSource,
    )
}

@Composable
private fun valueState(state: SliderState): State<Float> {
    var prevState by remember { mutableStateOf(state) }
    // Don't animate slider value when receive the first value and when changing isEnabled state
    val shouldSkipAnimation =
        prevState is SliderState.Empty || prevState.isEnabled != state.isEnabled
    val value =
        if (shouldSkipAnimation) remember { mutableFloatStateOf(state.value) }
        else animateFloatAsState(targetValue = state.value, label = "VolumeSliderValueAnimation")
    prevState = state
    return value
}

@Composable
private fun SliderIcon(
    icon: Icon,
    onIconTapped: () -> Unit,
    isTappable: Boolean,
    modifier: Modifier = Modifier,
) {
    val boxModifier =
        if (isTappable) {
                modifier.clickable(
                    onClick = onIconTapped,
                    interactionSource = null,
                    indication = null,
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

@Composable
fun setUpHapticsViewModel(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    interactionSource: MutableInteractionSource,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory?,
): SliderHapticsViewModel? {
    return hapticsViewModelFactory?.let {
        rememberViewModel(traceName = "SliderHapticsViewModel") {
                it.create(
                    interactionSource,
                    valueRange,
                    Orientation.Horizontal,
                    VolumeHapticsConfigsProvider.sliderHapticFeedbackConfig(valueRange),
                    VolumeHapticsConfigsProvider.seekableSliderTrackerConfig,
                )
            }
            .also { hapticsViewModel ->
                var lastDiscreteStep by remember { mutableFloatStateOf(round(value)) }
                LaunchedEffect(value) {
                    snapshotFlow { value }
                        .map { round(it) }
                        .filter { it != lastDiscreteStep }
                        .distinctUntilChanged()
                        .collect { discreteStep ->
                            lastDiscreteStep = discreteStep
                            hapticsViewModel.onValueChange(discreteStep)
                        }
                }
            }
    }
}
