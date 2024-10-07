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

package com.android.systemui.keyboard.shortcut.ui.composable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.compose.modifiers.thenIf
import kotlinx.coroutines.launch

/**
 * A selectable surface with no default focus/hover indications.
 *
 * This composable is similar to [androidx.compose.material3.Surface], but removes default
 * focus/hover states to enable custom implementations.
 */
@Composable
@NonRestartableComposable
fun SelectableShortcutSurface(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    interactionsConfig: InteractionsConfig = InteractionsConfig(),
    content: @Composable () -> Unit,
) {
    @Suppress("NAME_SHADOWING")
    val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
    val absoluteElevation = LocalAbsoluteTonalElevation.current + tonalElevation
    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalAbsoluteTonalElevation provides absoluteElevation,
    ) {
        val isFocused = interactionSource.collectIsFocusedAsState()
        Box(
            modifier =
                modifier
                    .minimumInteractiveComponentSize()
                    .surface(
                        shape = shape,
                        backgroundColor =
                            surfaceColorAtElevation(color = color, elevation = absoluteElevation),
                        border = border,
                        shadowElevation = with(LocalDensity.current) { shadowElevation.toPx() },
                    )
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication =
                            ShortcutHelperIndication(interactionSource, interactionsConfig),
                        enabled = enabled,
                        onClick = onClick,
                    )
                    .thenIf(isFocused.value) { Modifier.zIndex(1f) },
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

/**
 * A clickable surface with no default focus/hover indications.
 *
 * This composable is similar to [androidx.compose.material3.Surface], but removes default
 * focus/hover states to enable custom implementations.
 */
@Composable
@NonRestartableComposable
fun ClickableShortcutSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    interactionsConfig: InteractionsConfig = InteractionsConfig(),
    content: @Composable () -> Unit,
) {
    @Suppress("NAME_SHADOWING")
    val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
    val absoluteElevation = LocalAbsoluteTonalElevation.current + tonalElevation
    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalAbsoluteTonalElevation provides absoluteElevation,
    ) {
        Box(
            modifier =
                modifier
                    .minimumInteractiveComponentSize()
                    .surface(
                        shape = shape,
                        backgroundColor =
                            surfaceColorAtElevation(color = color, elevation = absoluteElevation),
                        border = border,
                        shadowElevation = with(LocalDensity.current) { shadowElevation.toPx() },
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication =
                            ShortcutHelperIndication(interactionSource, interactionsConfig),
                        enabled = enabled,
                        onClick = onClick,
                    ),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

@Composable
private fun surfaceColorAtElevation(color: Color, elevation: Dp): Color {
    return MaterialTheme.colorScheme.applyTonalElevation(color, elevation)
}

@Composable
internal fun ColorScheme.applyTonalElevation(backgroundColor: Color, elevation: Dp): Color {
    val tonalElevationEnabled = LocalTonalElevationEnabled.current
    return if (backgroundColor == surface && tonalElevationEnabled) {
        surfaceColorAtElevation(elevation)
    } else {
        backgroundColor
    }
}

/**
 * Applies surface-related modifiers to a composable.
 *
 * This function adds background, border, and shadow effects to a composable. Also ensure the
 * composable is clipped to the given shape.
 *
 * @param shape The shape to apply to the composable's background, border, and clipping.
 * @param backgroundColor The background color to apply to the composable.
 * @param border An optional border to draw around the composable.
 * @param shadowElevation The size of the shadow below the surface. To prevent shadow creep, only
 *   apply shadow elevation when absolutely necessary, such as when the surface requires visual
 *   separation from a patterned background. Note that It will not affect z index of the Surface. If
 *   you want to change the drawing order you can use `Modifier.zIndex`.
 * @return The modified Modifier instance with surface-related modifiers applied.
 */
@Stable
private fun Modifier.surface(
    shape: Shape,
    backgroundColor: Color,
    border: BorderStroke?,
    shadowElevation: Float,
): Modifier {
    return this.thenIf(shadowElevation > 0f) {
            Modifier.graphicsLayer(shadowElevation = shadowElevation, shape = shape, clip = false)
        }
        .thenIf(border != null) { Modifier.border(border!!, shape) }
        .background(color = backgroundColor, shape = shape)
}

private class ShortcutHelperInteractionsNode(
    private val interactionSource: InteractionSource,
    private val interactionsConfig: InteractionsConfig,
) : Modifier.Node(), DrawModifierNode {

    var isFocused = mutableStateOf(false)
    var isHovered = mutableStateOf(false)
    var isPressed = mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            val hoverInteractions = mutableListOf<HoverInteraction.Enter>()
            val focusInteractions = mutableListOf<FocusInteraction.Focus>()
            val pressInteractions = mutableListOf<PressInteraction.Press>()

            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusInteractions.add(interaction)
                    is FocusInteraction.Unfocus -> focusInteractions.remove(interaction.focus)
                    is HoverInteraction.Enter -> hoverInteractions.add(interaction)
                    is HoverInteraction.Exit -> hoverInteractions.remove(interaction.enter)
                    is PressInteraction.Press -> pressInteractions.add(interaction)
                    is PressInteraction.Release -> pressInteractions.remove(interaction.press)
                    is PressInteraction.Cancel -> pressInteractions.add(interaction.press)
                }
                isHovered.value = hoverInteractions.isNotEmpty()
                isPressed.value = pressInteractions.isNotEmpty()
                isFocused.value = focusInteractions.isNotEmpty()
            }
        }
    }

    override fun ContentDrawScope.draw() {

        fun getRectangleWithPadding(padding: Dp, size: Size): Rect {
            return Rect(Offset.Zero, size).let {
                if (interactionsConfig.focusOutlinePadding > 0.dp) {
                    it.inflate(padding.toPx())
                } else {
                    it.deflate(padding.unaryMinus().toPx())
                }
            }
        }

        drawContent()
        if (isHovered.value) {
            val hoverRect = getRectangleWithPadding(interactionsConfig.pressedPadding, size)
            drawRoundRect(
                color = interactionsConfig.hoverOverlayColor,
                alpha = interactionsConfig.hoverOverlayAlpha,
                cornerRadius = CornerRadius(interactionsConfig.surfaceCornerRadius.toPx()),
                topLeft = hoverRect.topLeft,
                size = hoverRect.size,
            )
        }
        if (isPressed.value) {
            val pressedRect = getRectangleWithPadding(interactionsConfig.pressedPadding, size)
            drawRoundRect(
                color = interactionsConfig.pressedOverlayColor,
                alpha = interactionsConfig.pressedOverlayAlpha,
                cornerRadius = CornerRadius(interactionsConfig.surfaceCornerRadius.toPx()),
                topLeft = pressedRect.topLeft,
                size = pressedRect.size,
            )
        }
        if (isFocused.value) {
            val focusOutline = getRectangleWithPadding(interactionsConfig.focusOutlinePadding, size)
            drawRoundRect(
                color = interactionsConfig.focusOutlineColor,
                style = Stroke(width = interactionsConfig.focusOutlineStrokeWidth.toPx()),
                topLeft = focusOutline.topLeft,
                size = focusOutline.size,
                cornerRadius = CornerRadius(interactionsConfig.focusOutlineCornerRadius.toPx()),
            )
        }
    }
}

data class ShortcutHelperIndication(
    private val interactionSource: InteractionSource,
    private val interactionsConfig: InteractionsConfig,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return ShortcutHelperInteractionsNode(interactionSource, interactionsConfig)
    }
}

data class InteractionsConfig(
    val hoverOverlayColor: Color = Color.Transparent,
    val hoverOverlayAlpha: Float = 0.0f,
    val pressedOverlayColor: Color = Color.Transparent,
    val pressedOverlayAlpha: Float = 0.0f,
    val focusOutlineColor: Color = Color.Transparent,
    val focusOutlineStrokeWidth: Dp = 0.dp,
    val focusOutlinePadding: Dp = 0.dp,
    val surfaceCornerRadius: Dp = 0.dp,
    val focusOutlineCornerRadius: Dp = 0.dp,
    val hoverPadding: Dp = 0.dp,
    val pressedPadding: Dp = hoverPadding,
)
