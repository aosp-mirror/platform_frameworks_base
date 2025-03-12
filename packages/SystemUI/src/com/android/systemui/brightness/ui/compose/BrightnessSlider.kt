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

package com.android.systemui.brightness.ui.compose

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.PlatformSlider
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.Flags
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction

@Composable
private fun BrightnessSlider(
    viewModel: BrightnessSliderViewModel,
    gammaValue: Int,
    valueRange: IntRange,
    label: Text.Resource,
    icon: Icon,
    restriction: PolicyRestriction,
    onRestrictedClick: (PolicyRestriction.Restricted) -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
    formatter: (Int) -> String = { "$it" },
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = remember(restriction) { restriction is PolicyRestriction.Restricted }
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel? =
        if (Flags.hapticsForComposeSliders()) {
            rememberViewModel(traceName = "SliderHapticsViewModel") {
                hapticsViewModelFactory.create(
                    interactionSource,
                    floatValueRange,
                    Orientation.Horizontal,
                    SliderHapticFeedbackConfig(
                        maxVelocityToScale = 1f /* slider progress(from 0 to 1) per sec */
                    ),
                    SeekableSliderTrackerConfig(),
                )
            }
        } else {
            null
        }

    val overriddenByAppState by
        if (Flags.showToastWhenAppControlBrightness()) {
            viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
        } else {
            remember { mutableStateOf(false) }
        }

    PlatformSlider(
        value = animatedValue,
        valueRange = floatValueRange,
        enabled = !isRestricted,
        onValueChange = {
            if (!isRestricted) {
                if (!overriddenByAppState) {
                    hapticsViewModel?.onValueChange(it)
                    value = it.toInt()
                    onDrag(value)
                }
            }
        },
        onValueChangeFinished = {
            if (!isRestricted) {
                if (!overriddenByAppState) {
                    hapticsViewModel?.onValueChangeEnded()
                    onStop(value)
                }
            }
        },
        modifier =
            modifier.sysuiResTag("slider").clickable(enabled = isRestricted) {
                if (restriction is PolicyRestriction.Restricted) {
                    onRestrictedClick(restriction)
                }
            },
        icon = { isDragging ->
            if (isDragging) {
                Text(text = formatter(value))
            } else {
                Icon(modifier = Modifier.size(24.dp), icon = icon)
            }
        },
        label = {
            Text(
                text = stringResource(id = label.res),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        },
        interactionSource = interactionSource,
    )
    // Showing the warning toast if the current running app window has controlled the
    // brightness value.
    if (Flags.showToastWhenAppControlBrightness()) {
        val context = LocalContext.current
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start && overriddenByAppState) {
                    viewModel.showToast(
                        context,
                        R.string.quick_settings_brightness_unable_adjust_msg,
                    )
                }
            }
        }
    }
}

private val sliderBackgroundFrameSize = 8.dp

private fun Modifier.sliderBackground(color: Color) = drawWithCache {
    val offsetAround = sliderBackgroundFrameSize.toPx()
    val newSize = Size(size.width + 2 * offsetAround, size.height + 2 * offsetAround)
    val offset = Offset(-offsetAround, -offsetAround)
    val cornerRadius = CornerRadius(offsetAround + size.height / 2)
    onDrawBehind {
        drawRoundRect(color = color, topLeft = offset, size = newSize, cornerRadius = cornerRadius)
    }
}

@Composable
fun BrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
    containerColor: Color = colorResource(R.color.shade_scrim_background_dark),
) {
    val gamma = viewModel.currentBrightness.value
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )

    DisposableEffect(Unit) { onDispose { viewModel.setIsDragging(false) } }

    Box(modifier = modifier.fillMaxWidth().sysuiResTag("brightness_slider")) {
        BrightnessSlider(
            viewModel = viewModel,
            gammaValue = gamma,
            valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
            label = viewModel.label,
            icon = viewModel.icon,
            restriction = restriction,
            onRestrictedClick = viewModel::showPolicyRestrictionDialog,
            onDrag = {
                viewModel.setIsDragging(true)
                coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(it))) }
            },
            onStop = {
                viewModel.setIsDragging(false)
                coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(it))) }
            },
            modifier =
                Modifier.borderOnFocus(
                        color = MaterialTheme.colorScheme.secondary,
                        cornerSize = CornerSize(32.dp),
                    )
                    .then(if (viewModel.showMirror) Modifier.drawInOverlay() else Modifier)
                    .sliderBackground(containerColor)
                    .fillMaxWidth()
                    .pointerInteropFilter {
                        if (
                            it.actionMasked == MotionEvent.ACTION_UP ||
                                it.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            viewModel.emitBrightnessTouchForFalsing()
                        }
                        false
                    },
            formatter = viewModel::formatValue,
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
        )
    }
}
