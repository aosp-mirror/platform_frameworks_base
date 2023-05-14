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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.grid.VerticalGrid
import com.android.systemui.R
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import kotlin.math.max

@Composable
internal fun PinBouncer(
    viewModel: PinBouncerViewModel,
    modifier: Modifier = Modifier,
) {
    // Report that the UI is shown to let the view-model run some logic.
    LaunchedEffect(Unit) { viewModel.onShown() }

    // The length of the PIN input received so far, so we know how many dots to render.
    val pinLength: Pair<Int, Int> by viewModel.pinLengths.collectAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(min = 16.dp).animateContentSize(),
        ) {
            // TODO(b/281871687): add support for dot shapes.
            val (previousPinLength, currentPinLength) = pinLength
            val dotCount = max(previousPinLength, currentPinLength) + 1
            repeat(dotCount) { index ->
                AnimatedVisibility(
                    visible = index < currentPinLength,
                    enter = fadeIn() + scaleIn() + slideInHorizontally(),
                    exit = fadeOut() + scaleOut() + slideOutHorizontally(),
                ) {
                    Box(
                        modifier =
                            Modifier.size(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    CircleShape,
                                )
                    )
                }
            }
        }

        Spacer(Modifier.height(100.dp))

        VerticalGrid(
            columns = 3,
            verticalSpacing = 12.dp,
            horizontalSpacing = 20.dp,
        ) {
            repeat(9) { index ->
                val digit = index + 1
                PinButton(
                    onClicked = { viewModel.onPinButtonClicked(digit) },
                ) { contentColor ->
                    PinDigit(digit, contentColor)
                }
            }

            PinButton(
                onClicked = { viewModel.onBackspaceButtonClicked() },
                onLongPressed = { viewModel.onBackspaceButtonLongPressed() },
                isHighlighted = true,
            ) { contentColor ->
                PinIcon(
                    Icon.Resource(
                        res = R.drawable.ic_backspace_24dp,
                        contentDescription =
                            ContentDescription.Resource(R.string.keyboardview_keycode_delete),
                    ),
                    contentColor,
                )
            }

            PinButton(
                onClicked = { viewModel.onPinButtonClicked(0) },
            ) { contentColor ->
                PinDigit(0, contentColor)
            }

            PinButton(
                onClicked = { viewModel.onAuthenticateButtonClicked() },
                isHighlighted = true,
            ) { contentColor ->
                PinIcon(
                    Icon.Resource(
                        res = R.drawable.ic_keyboard_tab_36dp,
                        contentDescription =
                            ContentDescription.Resource(R.string.keyboardview_keycode_enter),
                    ),
                    contentColor,
                )
            }
        }
    }
}

@Composable
private fun PinDigit(
    digit: Int,
    contentColor: Color,
) {
    // TODO(b/281878426): once "color: () -> Color" (added to BasicText in aosp/2568972) makes it
    //  into Text, use that here, to animate more efficiently.
    Text(
        text = digit.toString(),
        style = MaterialTheme.typography.headlineLarge,
        color = contentColor,
    )
}

@Composable
private fun PinIcon(
    icon: Icon,
    contentColor: Color,
) {
    Icon(
        icon = icon,
        tint = contentColor,
    )
}

@Composable
private fun PinButton(
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPressed: (() -> Unit)? = null,
    isHighlighted: Boolean = false,
    content: @Composable (contentColor: Color) -> Unit,
) {
    var isPressed: Boolean by remember { mutableStateOf(false) }
    val cornerRadius: Dp by
        animateDpAsState(
            if (isPressed) 24.dp else PinButtonSize / 2,
            label = "PinButton round corners",
        )
    val containerColor: Color by
        animateColorAsState(
            when {
                isPressed -> MaterialTheme.colorScheme.primaryContainer
                isHighlighted -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
            label = "Pin button container color",
        )
    val contentColor: Color by
        animateColorAsState(
            when {
                isPressed -> MaterialTheme.colorScheme.onPrimaryContainer
                isHighlighted -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            label = "Pin button container color",
        )

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(PinButtonSize)
                .drawBehind {
                    drawRoundRect(
                        color = containerColor,
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClicked() },
                        onLongPress = onLongPressed?.let { { onLongPressed() } },
                    )
                },
    ) {
        content(contentColor)
    }
}

private val PinButtonSize = 84.dp
