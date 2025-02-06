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

import android.content.Context
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.Flags
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconAppearSpec
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconDisappearSpec
import com.android.systemui.brightness.ui.compose.Dimensions.IconPadding
import com.android.systemui.brightness.ui.compose.Dimensions.IconSize
import com.android.systemui.brightness.ui.compose.Dimensions.SliderBackgroundFrameSize
import com.android.systemui.brightness.ui.compose.Dimensions.SliderBackgroundRoundedCorner
import com.android.systemui.brightness.ui.compose.Dimensions.SliderTrackRoundedCorner
import com.android.systemui.brightness.ui.compose.Dimensions.ThumbTrackGapSize
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@VisibleForTesting
fun BrightnessSlider(
    gammaValue: Int,
    valueRange: IntRange,
    iconResProvider: (Float) -> Int,
    imageLoader: suspend (Int, Context) -> Icon.Loaded,
    restriction: PolicyRestriction,
    onRestrictedClick: (PolicyRestriction.Restricted) -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    overriddenByAppState: Boolean,
    modifier: Modifier = Modifier,
    showToast: () -> Unit = {},
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = restriction is PolicyRestriction.Restricted
    val enabled = !isRestricted
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
    val colors = SliderDefaults.colors()

    // The value state is recreated every time gammaValue changes, so we recreate this derivedState
    // We have to use value as that's the value that changes when the user is dragging (gammaValue
    // is always the starting value: actual (not temporary) brightness).
    val iconRes by
        remember(gammaValue, valueRange) {
            derivedStateOf {
                val percentage =
                    (value - valueRange.first) * 100f / (valueRange.last - valueRange.first)
                iconResProvider(percentage)
            }
        }
    val context = LocalContext.current
    val painter: Painter by
        produceState<Painter>(
            initialValue = ColorPainter(Color.Transparent),
            key1 = iconRes,
            key2 = context,
        ) {
            val icon = imageLoader(iconRes, context)
            // toBitmap is Drawable?.() -> Bitmap? and handles null internally.
            val bitmap = icon.drawable.toBitmap()!!.asImageBitmap()
            this@produceState.value = BitmapPainter(bitmap)
        }

    val activeIconColor = colors.activeTickColor
    val inactiveIconColor = colors.inactiveTickColor
    val trackIcon: DrawScope.(Offset, Color, Float) -> Unit =
        remember(painter) {
            { offset, color, alpha ->
                translate(offset.x + IconPadding.toPx(), offset.y) {
                    with(painter) {
                        draw(
                            IconSize.toSize(),
                            colorFilter = ColorFilter.tint(color),
                            alpha = alpha,
                        )
                    }
                }
            }
        }

    Slider(
        value = animatedValue,
        valueRange = floatValueRange,
        enabled = enabled,
        colors = colors,
        onValueChange = {
            if (enabled) {
                if (!overriddenByAppState) {
                    hapticsViewModel?.onValueChange(it)
                    value = it.toInt()
                    onDrag(value)
                }
            }
        },
        onValueChangeFinished = {
            if (enabled) {
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
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                enabled = enabled,
                thumbSize = DpSize(4.dp, 52.dp),
            )
        },
        track = { sliderState ->
            var showIconActive by remember { mutableStateOf(true) }
            val iconActiveAlphaAnimatable = remember {
                Animatable(
                    initialValue = 1f,
                    typeConverter = Float.VectorConverter,
                    label = "iconActiveAlpha",
                )
            }

            val iconInactiveAlphaAnimatable = remember {
                Animatable(
                    initialValue = 0f,
                    typeConverter = Float.VectorConverter,
                    label = "iconInactiveAlpha",
                )
            }

            LaunchedEffect(iconActiveAlphaAnimatable, iconInactiveAlphaAnimatable, showIconActive) {
                if (showIconActive) {
                    launch { iconActiveAlphaAnimatable.appear() }
                    launch { iconInactiveAlphaAnimatable.disappear() }
                } else {
                    launch { iconActiveAlphaAnimatable.disappear() }
                    launch { iconInactiveAlphaAnimatable.appear() }
                }
            }

            SliderDefaults.Track(
                sliderState = sliderState,
                modifier =
                    Modifier.motionTestValues {
                            (iconActiveAlphaAnimatable.isRunning ||
                                iconInactiveAlphaAnimatable.isRunning) exportAs
                                BrightnessSliderMotionTestKeys.AnimatingIcon

                            iconActiveAlphaAnimatable.value exportAs
                                BrightnessSliderMotionTestKeys.ActiveIconAlpha
                            iconInactiveAlphaAnimatable.value exportAs
                                BrightnessSliderMotionTestKeys.InactiveIconAlpha
                        }
                        .height(40.dp)
                        .drawWithContent {
                            drawContent()

                            val yOffset = size.height / 2 - IconSize.toSize().height / 2
                            val activeTrackStart = 0f
                            val activeTrackEnd =
                                size.width * sliderState.coercedValueAsFraction -
                                    ThumbTrackGapSize.toPx()
                            val inactiveTrackStart = activeTrackEnd + ThumbTrackGapSize.toPx() * 2
                            val inactiveTrackEnd = size.width

                            val activeTrackWidth = activeTrackEnd - activeTrackStart
                            val inactiveTrackWidth = inactiveTrackEnd - inactiveTrackStart
                            if (
                                IconSize.toSize().width < activeTrackWidth - IconPadding.toPx() * 2
                            ) {
                                showIconActive = true
                                trackIcon(
                                    Offset(activeTrackStart, yOffset),
                                    activeIconColor,
                                    iconActiveAlphaAnimatable.value,
                                )
                            } else if (
                                IconSize.toSize().width <
                                    inactiveTrackWidth - IconPadding.toPx() * 2
                            ) {
                                showIconActive = false
                                trackIcon(
                                    Offset(inactiveTrackStart, yOffset),
                                    inactiveIconColor,
                                    iconInactiveAlphaAnimatable.value,
                                )
                            }
                        },
                trackCornerSize = SliderTrackRoundedCorner,
                trackInsideCornerSize = 2.dp,
                drawStopIndicator = null,
                thumbTrackGapSize = ThumbTrackGapSize,
            )
        },
    )

    val currentShowToast by rememberUpdatedState(showToast)
    // Showing the warning toast if the current running app window has controlled the
    // brightness value.
    if (Flags.showToastWhenAppControlBrightness()) {
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start && overriddenByAppState) {
                    currentShowToast()
                }
            }
        }
    }
}

private fun Modifier.sliderBackground(color: Color) = drawWithCache {
    val offsetAround = SliderBackgroundFrameSize.toSize()
    val newSize = Size(size.width + 2 * offsetAround.width, size.height + 2 * offsetAround.height)
    val offset = Offset(-offsetAround.width, -offsetAround.height)
    val cornerRadius = CornerRadius(SliderBackgroundRoundedCorner.toPx())
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
    if (gamma == BrightnessSliderViewModel.initialValue.value) { // Ignore initial negative value.
        return
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val overriddenByAppState by
        if (Flags.showToastWhenAppControlBrightness()) {
            viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
        } else {
            remember { mutableStateOf(false) }
        }

    DisposableEffect(Unit) { onDispose { viewModel.setIsDragging(false) } }

    Box(modifier = modifier.fillMaxWidth().sysuiResTag("brightness_slider")) {
        BrightnessSlider(
            gammaValue = gamma,
            valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
            iconResProvider = BrightnessSliderViewModel::getIconForPercentage,
            imageLoader = viewModel::loadImage,
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
                        cornerSize = CornerSize(SliderTrackRoundedCorner),
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
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
            overriddenByAppState = overriddenByAppState,
            showToast = {
                viewModel.showToast(context, R.string.quick_settings_brightness_unable_adjust_msg)
            },
        )
    }
}

private object Dimensions {
    val SliderBackgroundFrameSize = DpSize(10.dp, 6.dp)
    val SliderBackgroundRoundedCorner = 24.dp
    val SliderTrackRoundedCorner = 12.dp
    val IconSize = DpSize(28.dp, 28.dp)
    val IconPadding = 6.dp
    val ThumbTrackGapSize = 6.dp
}

private object AnimationSpecs {
    val IconAppearSpec = tween<Float>(durationMillis = 100, delayMillis = 33)
    val IconDisappearSpec = tween<Float>(durationMillis = 50)
}

private suspend fun Animatable<Float, AnimationVector1D>.appear() =
    animateTo(targetValue = 1f, animationSpec = IconAppearSpec)

private suspend fun Animatable<Float, AnimationVector1D>.disappear() =
    animateTo(targetValue = 0f, animationSpec = IconDisappearSpec)

@VisibleForTesting
object BrightnessSliderMotionTestKeys {
    val AnimatingIcon = MotionTestValueKey<Boolean>("animatingIcon")
    val ActiveIconAlpha = MotionTestValueKey<Float>("activeIconAlpha")
    val InactiveIconAlpha = MotionTestValueKey<Float>("inactiveIconAlpha")
}
