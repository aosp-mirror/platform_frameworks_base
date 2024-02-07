/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.compose.animation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator
import kotlin.math.max
import kotlin.math.min

/**
 * Create an expandable shape that can launch into an Activity or a Dialog.
 *
 * If this expandable should be expanded when it is clicked directly, then you should specify a
 * [onClick] handler, which will ensure that this expandable interactive size and background size
 * are consistent with the M3 components (48dp and 40dp respectively).
 *
 * If this expandable should be expanded when a children component is clicked, like a button inside
 * the expandable, then you can use the Expandable parameter passed to the [content] lambda.
 *
 * Example:
 * ```
 *    Expandable(
 *      color = MaterialTheme.colorScheme.primary,
 *      shape = RoundedCornerShape(16.dp),
 *
 *      // For activities:
 *      onClick = { expandable ->
 *          activityStarter.startActivity(intent, expandable.activityLaunchController())
 *      },
 *
 *      // For dialogs:
 *      onClick = { expandable ->
 *          dialogLaunchAnimator.show(dialog, controller.dialogLaunchController())
 *      },
 *    ) {
 *      ...
 *    }
 * ```
 *
 * @sample com.android.systemui.compose.gallery.ActivityLaunchScreen
 * @sample com.android.systemui.compose.gallery.DialogLaunchScreen
 */
@Composable
fun Expandable(
    color: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    contentColor: Color = contentColorFor(color),
    borderStroke: BorderStroke? = null,
    onClick: ((Expandable) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        rememberExpandableController(color, shape, contentColor, borderStroke),
        modifier,
        onClick,
        interactionSource,
        content,
    )
}

/**
 * Create an expandable shape that can launch into an Activity or a Dialog.
 *
 * This overload can be used in cases where you need to create the [ExpandableController] before
 * composing this [Expandable], for instance if something outside of this Expandable can trigger a
 * launch animation
 *
 * Example:
 * ```
 *    // The controller that you can use to trigger the animations from anywhere.
 *    val controller =
 *        rememberExpandableController(
 *          color = MaterialTheme.colorScheme.primary,
 *          shape = RoundedCornerShape(16.dp),
 *        )
 *
 *    Expandable(controller) {
 *       ...
 *    }
 * ```
 *
 * @sample com.android.systemui.compose.gallery.ActivityLaunchScreen
 * @sample com.android.systemui.compose.gallery.DialogLaunchScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Expandable(
    controller: ExpandableController,
    modifier: Modifier = Modifier,
    onClick: ((Expandable) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (Expandable) -> Unit,
) {
    val controller = controller as ExpandableControllerImpl
    val color = controller.color
    val contentColor = controller.contentColor
    val shape = controller.shape

    val wrappedContent =
        remember(content) {
            movableContentOf { expandable: Expandable ->
                CompositionLocalProvider(
                    LocalContentColor provides contentColor,
                ) {
                    // We make sure that the content itself (wrapped by the background) is at least
                    // 40.dp, which is the same as the M3 buttons. This applies even if onClick is
                    // null, to make it easier to write expandables that are sometimes clickable and
                    // sometimes not. There shouldn't be any Expandable smaller than 40dp because if
                    // the expandable is not clickable directly, then something in its content
                    // should be (and with a size >= 40dp).
                    val minSize = 40.dp
                    Box(
                        Modifier.defaultMinSize(minWidth = minSize, minHeight = minSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        content(expandable)
                    }
                }
            }
        }

    var thisExpandableSize by remember { mutableStateOf(Size.Zero) }

    /** Set the current element size as this Expandable size. */
    fun Modifier.updateExpandableSize(): Modifier {
        return this.onGloballyPositioned { coords ->
            thisExpandableSize =
                coords
                    .findRootCoordinates()
                    // Make sure that we report the actual size, and not the visual/clipped one.
                    .localBoundingBoxOf(coords, clipBounds = false)
                    .size
        }
    }

    // Make sure we don't read animatorState directly here to avoid recomposition every time the
    // state changes (i.e. every frame of the animation).
    val isAnimating by remember {
        derivedStateOf {
            controller.animatorState.value != null && controller.overlay.value != null
        }
    }

    // If this expandable is expanded when it's being directly clicked on, let's ensure that it has
    // the minimum interactive size followed by all M3 components (48.dp).
    val minInteractiveSizeModifier =
        if (onClick != null) {
            Modifier.minimumInteractiveComponentSize()
        } else {
            Modifier
        }

    when {
        isAnimating -> {
            // Don't compose the movable content during the animation, as it should be composed only
            // once at all times. We make this spacer exactly the same size as this Expandable when
            // it is visible.
            Spacer(
                modifier.requiredSize(with(controller.density) { thisExpandableSize.toDpSize() })
            )

            // The content and its animated background in the overlay. We draw it only when we are
            // animating.
            AnimatedContentInOverlay(
                color,
                controller.boundsInComposeViewRoot.value.size,
                controller.animatorState,
                controller.overlay.value
                    ?: error("AnimatedContentInOverlay shouldn't be composed with null overlay."),
                controller,
                wrappedContent,
                controller.composeViewRoot,
                { controller.currentComposeViewInOverlay.value = it },
                controller.density,
            )
        }
        controller.isDialogShowing.value -> {
            Box(
                modifier
                    .updateExpandableSize()
                    .then(minInteractiveSizeModifier)
                    .drawWithContent { /* Don't draw anything when the dialog is shown. */}
                    .onGloballyPositioned {
                        controller.boundsInComposeViewRoot.value = it.boundsInRoot()
                    }
            ) {
                wrappedContent(controller.expandable)
            }
        }
        else -> {
            val clickModifier =
                if (onClick != null) {
                    if (interactionSource != null) {
                        // If the caller provided an interaction source, then that means that they
                        // will draw the click indication themselves.
                        Modifier.clickable(interactionSource, indication = null) {
                            onClick(controller.expandable)
                        }
                    } else {
                        // If no interaction source is provided, we draw the default indication (a
                        // ripple) and make sure it's clipped by the expandable shape.
                        Modifier.clip(shape).clickable { onClick(controller.expandable) }
                    }
                } else {
                    Modifier
                }

            Box(
                modifier
                    .updateExpandableSize()
                    .then(minInteractiveSizeModifier)
                    .then(clickModifier)
                    .background(color, shape)
                    .border(controller)
                    .onGloballyPositioned {
                        controller.boundsInComposeViewRoot.value = it.boundsInRoot()
                    },
            ) {
                wrappedContent(controller.expandable)
            }
        }
    }
}

/** Draw [content] in [overlay] while respecting its screen position given by [animatorState]. */
@Composable
private fun AnimatedContentInOverlay(
    color: Color,
    sizeInOriginalLayout: Size,
    animatorState: State<TransitionAnimator.State?>,
    overlay: ViewGroupOverlay,
    controller: ExpandableControllerImpl,
    content: @Composable (Expandable) -> Unit,
    composeViewRoot: View,
    onOverlayComposeViewChanged: (View?) -> Unit,
    density: Density,
) {
    val compositionContext = rememberCompositionContext()
    val context = LocalContext.current

    // Create the ComposeView and force its content composition so that the movableContent is
    // composed exactly once when we start animating.
    val composeViewInOverlay =
        remember(context, density) {
            val startWidth = sizeInOriginalLayout.width
            val startHeight = sizeInOriginalLayout.height
            val contentModifier =
                Modifier
                    // Draw the content with the same size as it was at the start of the animation
                    // so that its content is laid out exactly the same way.
                    .requiredSize(with(density) { sizeInOriginalLayout.toDpSize() })
                    .drawWithContent {
                        val animatorState = animatorState.value ?: return@drawWithContent

                        // Scale the content with the background while keeping its aspect ratio.
                        val widthRatio =
                            if (startWidth != 0f) {
                                animatorState.width.toFloat() / startWidth
                            } else {
                                1f
                            }
                        val heightRatio =
                            if (startHeight != 0f) {
                                animatorState.height.toFloat() / startHeight
                            } else {
                                1f
                            }
                        val scale = min(widthRatio, heightRatio)
                        scale(scale) { this@drawWithContent.drawContent() }
                    }

            val composeView =
                ComposeView(context).apply {
                    setContent {
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                val animatorState = animatorState.value ?: return@drawWithContent
                                if (!animatorState.visible) {
                                    return@drawWithContent
                                }

                                drawBackground(animatorState, color, controller.borderStroke)
                                drawContent()
                            },
                            // We center the content in the expanding container.
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(contentModifier) { content(controller.expandable) }
                        }
                    }
                }

            // Set the owners.
            val overlayViewGroup =
                getOverlayViewGroup(
                    context,
                    overlay,
                )

            overlayViewGroup.setViewTreeLifecycleOwner(composeViewRoot.findViewTreeLifecycleOwner())
            overlayViewGroup.setViewTreeViewModelStoreOwner(
                composeViewRoot.findViewTreeViewModelStoreOwner()
            )
            ViewTreeSavedStateRegistryOwner.set(
                overlayViewGroup,
                ViewTreeSavedStateRegistryOwner.get(composeViewRoot),
            )

            composeView.setParentCompositionContext(compositionContext)

            composeView
        }

    DisposableEffect(overlay, composeViewInOverlay) {
        // Add the ComposeView to the overlay.
        overlay.add(composeViewInOverlay)

        val startState =
            animatorState.value
                ?: throw IllegalStateException(
                    "AnimatedContentInOverlay shouldn't be composed with null animatorState."
                )
        measureAndLayoutComposeViewInOverlay(composeViewInOverlay, startState)
        onOverlayComposeViewChanged(composeViewInOverlay)

        onDispose {
            composeViewInOverlay.disposeComposition()
            overlay.remove(composeViewInOverlay)
            onOverlayComposeViewChanged(null)
        }
    }
}

internal fun measureAndLayoutComposeViewInOverlay(
    view: View,
    state: TransitionAnimator.State,
) {
    val exactWidth = state.width
    val exactHeight = state.height
    view.measure(
        View.MeasureSpec.makeSafeMeasureSpec(exactWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeSafeMeasureSpec(exactHeight, View.MeasureSpec.EXACTLY),
    )

    val parent = view.parent as ViewGroup
    val parentLocation = parent.locationOnScreen
    val offsetX = parentLocation[0]
    val offsetY = parentLocation[1]
    view.layout(
        state.left - offsetX,
        state.top - offsetY,
        state.right - offsetX,
        state.bottom - offsetY,
    )
}

// TODO(b/230830644): Add hidden API to ViewGroupOverlay to access this ViewGroup directly?
private fun getOverlayViewGroup(context: Context, overlay: ViewGroupOverlay): ViewGroup {
    val view = View(context)
    overlay.add(view)
    var current = view.parent
    while (current.parent != null) {
        current = current.parent
    }
    overlay.remove(view)
    return current as ViewGroup
}

private fun Modifier.border(controller: ExpandableControllerImpl): Modifier {
    return if (controller.borderStroke != null) {
        this.border(controller.borderStroke, controller.shape)
    } else {
        this
    }
}

private fun ContentDrawScope.drawBackground(
    animatorState: TransitionAnimator.State,
    color: Color,
    border: BorderStroke?,
) {
    val topRadius = animatorState.topCornerRadius
    val bottomRadius = animatorState.bottomCornerRadius
    if (topRadius == bottomRadius) {
        // Shortcut to avoid Outline calculation and allocation.
        val cornerRadius = CornerRadius(topRadius)

        // Draw the background.
        drawRoundRect(color, cornerRadius = cornerRadius)

        // Draw the border.
        if (border != null) {
            // Copied from androidx.compose.foundation.Border.kt
            val strokeWidth = border.width.toPx()
            val halfStroke = strokeWidth / 2
            val borderStroke = Stroke(strokeWidth)

            drawRoundRect(
                brush = border.brush,
                topLeft = Offset(halfStroke, halfStroke),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = cornerRadius.shrink(halfStroke),
                style = borderStroke
            )
        }
    } else {
        val shape =
            RoundedCornerShape(
                topStart = topRadius,
                topEnd = topRadius,
                bottomStart = bottomRadius,
                bottomEnd = bottomRadius,
            )
        val outline = shape.createOutline(size, layoutDirection, this)

        // Draw the background.
        drawOutline(outline, color = color)

        // Draw the border.
        if (border != null) {
            // Copied from androidx.compose.foundation.Border.kt.
            val strokeWidth = border.width.toPx()
            val path =
                createRoundRectPath(
                    (outline as Outline.Rounded).roundRect,
                    strokeWidth,
                )

            drawPath(path, border.brush)
        }
    }
}

/**
 * Helper method that creates a round rect with the inner region removed by the given stroke width.
 *
 * Copied from androidx.compose.foundation.Border.kt.
 */
private fun createRoundRectPath(
    roundedRect: RoundRect,
    strokeWidth: Float,
): Path {
    return Path().apply {
        addRoundRect(roundedRect)
        val insetPath =
            Path().apply { addRoundRect(createInsetRoundedRect(strokeWidth, roundedRect)) }
        op(this, insetPath, PathOperation.Difference)
    }
}

/* Copied from androidx.compose.foundation.Border.kt. */
private fun createInsetRoundedRect(widthPx: Float, roundedRect: RoundRect) =
    RoundRect(
        left = widthPx,
        top = widthPx,
        right = roundedRect.width - widthPx,
        bottom = roundedRect.height - widthPx,
        topLeftCornerRadius = roundedRect.topLeftCornerRadius.shrink(widthPx),
        topRightCornerRadius = roundedRect.topRightCornerRadius.shrink(widthPx),
        bottomLeftCornerRadius = roundedRect.bottomLeftCornerRadius.shrink(widthPx),
        bottomRightCornerRadius = roundedRect.bottomRightCornerRadius.shrink(widthPx)
    )

/**
 * Helper method to shrink the corner radius by the given value, clamping to 0 if the resultant
 * corner radius would be negative.
 *
 * Copied from androidx.compose.foundation.Border.kt.
 */
private fun CornerRadius.shrink(value: Float): CornerRadius =
    CornerRadius(max(0f, this.x - value), max(0f, this.y - value))
