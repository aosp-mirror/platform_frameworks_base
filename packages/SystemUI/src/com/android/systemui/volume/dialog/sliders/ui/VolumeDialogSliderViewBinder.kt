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

package com.android.systemui.volume.dialog.sliders.ui

import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.VerticalSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.compose.ui.graphics.painter.DrawablePainter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.compose.VolumeDialogSliderTrack
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import javax.inject.Inject
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@VolumeDialogSliderScope
class VolumeDialogSliderViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSliderViewModel,
    private val overscrollViewModel: VolumeDialogOverscrollViewModel,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    fun bind(view: View) {
        val sliderComposeView: ComposeView = view.requireViewById(R.id.volume_dialog_slider)
        sliderComposeView.setContent {
            PlatformTheme {
                VolumeDialogSlider(
                    viewModel = viewModel,
                    overscrollViewModel = overscrollViewModel,
                    hapticsViewModelFactory =
                        if (com.android.systemui.Flags.hapticsForComposeSliders()) {
                            hapticsViewModelFactory
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeDialogSlider(
    viewModel: VolumeDialogSliderViewModel,
    overscrollViewModel: VolumeDialogOverscrollViewModel,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory?,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val colors =
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            inactiveTickColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    val collectedSliderStateModel by viewModel.state.collectAsStateWithLifecycle(null)
    val sliderStateModel = collectedSliderStateModel ?: return

    val steps = with(sliderStateModel.valueRange) { endInclusive - start - 1 }.toInt()

    var animateJob: Job? = null
    val animatedSliderValue =
        remember(sliderStateModel.value) { Animatable(sliderStateModel.value) }

    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel? =
        hapticsViewModelFactory?.let {
            rememberViewModel(traceName = "SliderHapticsViewModel") {
                it.create(
                    interactionSource,
                    sliderStateModel.valueRange,
                    Orientation.Vertical,
                    VolumeHapticsConfigsProvider.sliderHapticFeedbackConfig(
                        sliderStateModel.valueRange
                    ),
                    VolumeHapticsConfigsProvider.seekableSliderTrackerConfig,
                )
            }
        }

    val sliderState =
        remember(steps, sliderStateModel.valueRange) {
            SliderState(
                    value = sliderStateModel.value,
                    valueRange = sliderStateModel.valueRange,
                    steps = steps,
                )
                .also { sliderState ->
                    sliderState.onValueChangeFinished = {
                        viewModel.onStreamChangeFinished(sliderState.value.roundToInt())
                        hapticsViewModel?.onValueChangeEnded()
                    }
                    sliderState.onValueChange = { newValue ->
                        if (newValue != animatedSliderValue.targetValue) {
                            animateJob?.cancel()
                            animateJob =
                                coroutineScope.launch {
                                    animatedSliderValue.animateTo(newValue) {
                                        sliderState.value = value
                                    }
                                }
                        }

                        hapticsViewModel?.addVelocityDataPoint(newValue)
                        overscrollViewModel.setSlider(
                            value = sliderState.value,
                            min = sliderState.valueRange.start,
                            max = sliderState.valueRange.endInclusive,
                        )
                        viewModel.setStreamVolume(newValue, true)
                    }
                }
        }

    var lastDiscreteStep by remember { mutableFloatStateOf(round(sliderStateModel.value)) }
    LaunchedEffect(sliderStateModel.value) {
        val value = sliderStateModel.value
        launch { animatedSliderValue.animateTo(value) }
        if (value != lastDiscreteStep) {
            lastDiscreteStep = value
            hapticsViewModel?.onValueChange(value)
        }
    }

    VerticalSlider(
        state = sliderState,
        enabled = !sliderStateModel.isDisabled,
        reverseDirection = true,
        colors = colors,
        interactionSource = interactionSource,
        modifier =
            modifier.pointerInput(Unit) {
                coroutineScope {
                    val currentContext = currentCoroutineContext()
                    awaitPointerEventScope {
                        while (currentContext.isActive) {
                            viewModel.onTouchEvent(awaitPointerEvent())
                        }
                    }
                }
            },
        track = {
            VolumeDialogSliderTrack(
                sliderState,
                colors = colors,
                isEnabled = !sliderStateModel.isDisabled,
                activeTrackEndIcon = { iconsState ->
                    VolumeIcon(sliderStateModel.icon, iconsState.isActiveTrackEndIconVisible)
                },
                inactiveTrackEndIcon = { iconsState ->
                    VolumeIcon(sliderStateModel.icon, !iconsState.isActiveTrackEndIconVisible)
                },
            )
        },
    )
}

@Composable
private fun BoxScope.VolumeIcon(
    drawable: Drawable,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(delayMillis = 33, durationMillis = 100)),
        exit = fadeOut(animationSpec = tween(durationMillis = 50)),
        modifier = modifier.align(Alignment.Center).size(40.dp).padding(10.dp),
    ) {
        Icon(painter = DrawablePainter(drawable), contentDescription = null)
    }
}
