/*
 * Copyright (C) 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalAnimationApi::class)

package com.android.systemui.bouncer.ui.composable

import android.view.HapticFeedbackConstants
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Easings
import com.android.compose.grid.VerticalGrid
import com.android.systemui.R
import com.android.systemui.bouncer.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.bouncer.ui.viewmodel.EnteredKey
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.compose.modifiers.thenIf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PinBouncer(
    viewModel: PinBouncerViewModel,
    modifier: Modifier = Modifier,
) {
    // Report that the UI is shown to let the view-model run some logic.
    LaunchedEffect(Unit) { viewModel.onShown() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        PinInputDisplay(viewModel)
        Spacer(Modifier.height(100.dp))
        PinPad(viewModel)
    }
}

@Composable
private fun PinInputDisplay(viewModel: PinBouncerViewModel) {
    val currentPinEntries: List<EnteredKey> by viewModel.pinEntries.collectAsState()

    // visiblePinEntries keeps pins removed from currentPinEntries in the composition until their
    // disappear-animation completed. The list is sorted by the natural ordering of EnteredKey,
    // which is guaranteed to produce the original edit order, since the model only modifies entries
    // at the end.
    val visiblePinEntries = remember { SnapshotStateList<EnteredKey>() }
    currentPinEntries.forEach {
        val index = visiblePinEntries.binarySearch(it)
        if (index < 0) {
            val insertionPoint = -(index + 1)
            visiblePinEntries.add(insertionPoint, it)
        }
    }

    Row(
        modifier =
            Modifier.heightIn(min = entryShapeSize)
                // Pins overflowing horizontally should still be shown as scrolling.
                .wrapContentSize(unbounded = true),
    ) {
        visiblePinEntries.forEachIndexed { index, entry ->
            key(entry) {
                val visibility = remember {
                    MutableTransitionState<EntryVisibility>(EntryVisibility.Hidden)
                }
                visibility.targetState =
                    when {
                        currentPinEntries.isEmpty() && visiblePinEntries.size > 1 ->
                            EntryVisibility.BulkHidden(index, visiblePinEntries.size)
                        currentPinEntries.contains(entry) -> EntryVisibility.Shown
                        else -> EntryVisibility.Hidden
                    }

                ObscuredInputEntry(updateTransition(visibility, label = "Pin Entry $entry"))

                LaunchedEffect(entry) {
                    // Remove entry from visiblePinEntries once the hide transition completed.
                    snapshotFlow {
                            visibility.currentState == visibility.targetState &&
                                visibility.targetState != EntryVisibility.Shown
                        }
                        .collect { isRemoved ->
                            if (isRemoved) {
                                visiblePinEntries.remove(entry)
                            }
                        }
                }
            }
        }
    }
}

private sealed class EntryVisibility {
    object Shown : EntryVisibility()

    object Hidden : EntryVisibility()

    /**
     * Same as [Hidden], but applies when multiple entries are hidden simultaneously, without
     * collapsing during the hide.
     */
    data class BulkHidden(val staggerIndex: Int, val totalEntryCount: Int) : EntryVisibility()
}

@Composable
private fun ObscuredInputEntry(transition: Transition<EntryVisibility>) {
    // spec: http://shortn/_DEhE3Xl2bi
    val shapePadding = 6.dp
    val shapeOvershootSize = 22.dp
    val dismissStaggerDelayMs = 33
    val dismissDurationMs = 450
    val expansionDurationMs = 250
    val shapeExpandDurationMs = 83
    val shapeRetractDurationMs = 167
    val shapeCollapseDurationMs = 200

    val animatedEntryWidth by
        transition.animateDp(
            transitionSpec = {
                when (val target = targetState) {
                    is EntryVisibility.BulkHidden ->
                        // only collapse horizontal space once all entries are removed
                        snap(dismissDurationMs + dismissStaggerDelayMs * target.totalEntryCount)
                    else -> tween(expansionDurationMs, easing = Easings.Standard)
                }
            },
            label = "entry space"
        ) { state ->
            if (state == EntryVisibility.Shown) entryShapeSize + (shapePadding * 2) else 0.dp
        }

    val animatedShapeSize by
        transition.animateDp(
            transitionSpec = {
                when {
                    EntryVisibility.Hidden isTransitioningTo EntryVisibility.Shown ->
                        keyframes {
                            durationMillis = shapeExpandDurationMs + shapeRetractDurationMs
                            0.dp at 0 with Easings.Linear
                            shapeOvershootSize at shapeExpandDurationMs with Easings.Legacy
                        }
                    targetState is EntryVisibility.BulkHidden -> {
                        val target = targetState as EntryVisibility.BulkHidden
                        tween(
                            dismissDurationMs,
                            delayMillis = target.staggerIndex * dismissStaggerDelayMs,
                            easing = Easings.Legacy,
                        )
                    }
                    else -> tween(shapeCollapseDurationMs, easing = Easings.StandardDecelerate)
                }
            },
            label = "shape size"
        ) { state ->
            when (state) {
                EntryVisibility.Shown -> entryShapeSize
                else -> 0.dp
            }
        }

    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            // TODO(b/282730134): add support for dot shapes.
            Canvas(Modifier) { drawCircle(dotColor) }
        }
    ) { measurables, _ ->
        val shapeSizePx = animatedShapeSize.roundToPx()
        val placeable = measurables.single().measure(Constraints.fixed(shapeSizePx, shapeSizePx))

        layout(animatedEntryWidth.roundToPx(), entryShapeSize.roundToPx()) {
            placeable.place(
                ((animatedEntryWidth - animatedShapeSize) / 2f).roundToPx(),
                ((entryShapeSize - animatedShapeSize) / 2f).roundToPx()
            )
        }
    }
}

@Composable
private fun PinPad(viewModel: PinBouncerViewModel) {
    val isInputEnabled: Boolean by viewModel.isInputEnabled.collectAsState()
    val backspaceButtonAppearance by viewModel.backspaceButtonAppearance.collectAsState()
    val confirmButtonAppearance by viewModel.confirmButtonAppearance.collectAsState()
    val animateFailure: Boolean by viewModel.animateFailure.collectAsState()

    val buttonScaleAnimatables = remember { List(12) { Animatable(1f) } }
    LaunchedEffect(animateFailure) {
        // Show the failure animation if the user entered the wrong input.
        if (animateFailure) {
            showFailureAnimation(buttonScaleAnimatables)
            viewModel.onFailureAnimationShown()
        }
    }

    VerticalGrid(
        columns = 3,
        verticalSpacing = 12.dp,
        horizontalSpacing = 20.dp,
    ) {
        repeat(9) { index ->
            DigitButton(
                index + 1,
                isInputEnabled,
                viewModel::onPinButtonClicked,
                buttonScaleAnimatables[index]::value,
            )
        }

        ActionButton(
            icon =
                Icon.Resource(
                    res = R.drawable.ic_backspace_24dp,
                    contentDescription =
                        ContentDescription.Resource(R.string.keyboardview_keycode_delete),
                ),
            isInputEnabled = isInputEnabled,
            onClicked = viewModel::onBackspaceButtonClicked,
            onLongPressed = viewModel::onBackspaceButtonLongPressed,
            appearance = backspaceButtonAppearance,
            scaling = buttonScaleAnimatables[9]::value,
        )

        DigitButton(
            0,
            isInputEnabled,
            viewModel::onPinButtonClicked,
            buttonScaleAnimatables[10]::value,
        )

        ActionButton(
            icon =
                Icon.Resource(
                    res = R.drawable.ic_keyboard_tab_36dp,
                    contentDescription =
                        ContentDescription.Resource(R.string.keyboardview_keycode_enter),
                ),
            isInputEnabled = isInputEnabled,
            onClicked = viewModel::onAuthenticateButtonClicked,
            appearance = confirmButtonAppearance,
            scaling = buttonScaleAnimatables[11]::value,
        )
    }
}

@Composable
private fun DigitButton(
    digit: Int,
    isInputEnabled: Boolean,
    onClicked: (Int) -> Unit,
    scaling: () -> Float,
) {
    PinPadButton(
        onClicked = { onClicked(digit) },
        isEnabled = isInputEnabled,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        foregroundColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.graphicsLayer {
                val scale = scaling()
                scaleX = scale
                scaleY = scale
            }
    ) { contentColor ->
        // TODO(b/281878426): once "color: () -> Color" (added to BasicText in aosp/2568972) makes
        // it into Text, use that here, to animate more efficiently.
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.headlineLarge,
            color = contentColor(),
        )
    }
}

@Composable
private fun ActionButton(
    icon: Icon,
    isInputEnabled: Boolean,
    onClicked: () -> Unit,
    onLongPressed: (() -> Unit)? = null,
    appearance: ActionButtonAppearance,
    scaling: () -> Float,
) {
    val isHidden = appearance == ActionButtonAppearance.Hidden
    val hiddenAlpha by animateFloatAsState(if (isHidden) 0f else 1f, label = "Action button alpha")

    val foregroundColor =
        when (appearance) {
            ActionButtonAppearance.Shown -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    val backgroundColor =
        when (appearance) {
            ActionButtonAppearance.Shown -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        }

    PinPadButton(
        onClicked = onClicked,
        onLongPressed = onLongPressed,
        isEnabled = isInputEnabled && !isHidden,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
        modifier =
            Modifier.graphicsLayer {
                alpha = hiddenAlpha
                val scale = scaling()
                scaleX = scale
                scaleY = scale
            }
    ) { contentColor ->
        Icon(
            icon = icon,
            tint = contentColor(),
        )
    }
}

@Composable
private fun PinPadButton(
    onClicked: () -> Unit,
    isEnabled: Boolean,
    backgroundColor: Color,
    foregroundColor: Color,
    modifier: Modifier = Modifier,
    onLongPressed: (() -> Unit)? = null,
    content: @Composable (contentColor: () -> Color) -> Unit,
) {
    var isPressed: Boolean by remember { mutableStateOf(false) }

    val view = LocalView.current
    LaunchedEffect(isPressed) {
        if (isPressed) {
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    // Pin button animation specification is asymmetric: fast animation to the pressed state, and a
    // slow animation upon release. Note that isPressed is guaranteed to be true for at least the
    // press animation duration (see below in detectTapGestures).
    val animEasing = if (isPressed) pinButtonPressedEasing else pinButtonReleasedEasing
    val animDurationMillis =
        (if (isPressed) pinButtonPressedDuration else pinButtonReleasedDuration).toInt(
            DurationUnit.MILLISECONDS
        )

    val cornerRadius: Dp by
        animateDpAsState(
            if (isPressed) 24.dp else pinButtonSize / 2,
            label = "PinButton round corners",
            animationSpec = tween(animDurationMillis, easing = animEasing)
        )
    val colorAnimationSpec: AnimationSpec<Color> = tween(animDurationMillis, easing = animEasing)
    val containerColor: Color by
        animateColorAsState(
            when {
                isPressed -> MaterialTheme.colorScheme.primary
                else -> backgroundColor
            },
            label = "Pin button container color",
            animationSpec = colorAnimationSpec
        )
    val contentColor =
        animateColorAsState(
            when {
                isPressed -> MaterialTheme.colorScheme.onPrimary
                else -> foregroundColor
            },
            label = "Pin button container color",
            animationSpec = colorAnimationSpec
        )

    val scope = rememberCoroutineScope()

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(pinButtonSize)
                .drawBehind {
                    drawRoundRect(
                        color = containerColor,
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                    )
                }
                .thenIf(isEnabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                scope.launch {
                                    isPressed = true
                                    val minDuration = async {
                                        delay(pinButtonPressedDuration + pinButtonHoldTime)
                                    }
                                    tryAwaitRelease()
                                    minDuration.await()
                                    isPressed = false
                                }
                            },
                            onTap = { onClicked() },
                            onLongPress = onLongPressed?.let { { onLongPressed() } },
                        )
                    }
                },
    ) {
        content(contentColor::value)
    }
}

private suspend fun showFailureAnimation(
    buttonScaleAnimatables: List<Animatable<Float, AnimationVector1D>>
) {
    coroutineScope {
        buttonScaleAnimatables.forEachIndexed { index, animatable ->
            launch {
                animatable.animateTo(
                    targetValue = pinButtonErrorShrinkFactor,
                    animationSpec =
                        tween(
                            durationMillis = pinButtonErrorShrinkMs,
                            delayMillis = index * pinButtonErrorStaggerDelayMs,
                            easing = Easings.Linear,
                        ),
                )

                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = pinButtonErrorRevertMs,
                            easing = Easings.Legacy,
                        ),
                )
            }
        }
    }
}

private val entryShapeSize = 16.dp

private val pinButtonSize = 84.dp
private val pinButtonErrorShrinkFactor = 67.dp / pinButtonSize
private const val pinButtonErrorShrinkMs = 50
private const val pinButtonErrorStaggerDelayMs = 33
private const val pinButtonErrorRevertMs = 617

// Pin button motion spec: http://shortn/_9TTIG6SoEa
private val pinButtonPressedDuration = 100.milliseconds
private val pinButtonPressedEasing = Easings.Linear
private val pinButtonHoldTime = 33.milliseconds
private val pinButtonReleasedDuration = 420.milliseconds
private val pinButtonReleasedEasing = Easings.Standard
