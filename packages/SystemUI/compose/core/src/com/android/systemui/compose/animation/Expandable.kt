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

package com.android.systemui.compose.animation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import com.android.systemui.animation.LaunchAnimator
import kotlin.math.min

/**
 * Create an expandable shape that can launch into an Activity or a Dialog.
 *
 * Example:
 * ```
 *    Expandable(
 *      color = MaterialTheme.colorScheme.primary,
 *      shape = RoundedCornerShape(16.dp),
 *    ) { controller ->
 *      Row(
 *        Modifier
 *          // For activities:
 *          .clickable { activityStarter.startActivity(intent, controller.forActivity()) }
 *
 *          // For dialogs:
 *          .clickable { dialogLaunchAnimator.show(dialog, controller.forDialog()) }
 *      ) { ... }
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
    content: @Composable (ExpandableController) -> Unit,
) {
    Expandable(
        rememberExpandableController(color, shape, contentColor),
        modifier,
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
@Composable
fun Expandable(
    controller: ExpandableController,
    modifier: Modifier = Modifier,
    content: @Composable (ExpandableController) -> Unit,
) {
    val controller = controller as ExpandableControllerImpl
    val color = controller.color
    val contentColor = controller.contentColor
    val shape = controller.shape

    // TODO(b/230830644): Use movableContentOf to preserve the content state instead once the
    // Compose libraries have been updated and include aosp/2163631.
    val wrappedContent =
        @Composable { controller: ExpandableController ->
            CompositionLocalProvider(
                LocalContentColor provides contentColor,
            ) {
                content(controller)
            }
        }

    val thisExpandableSize by remember {
        derivedStateOf { controller.boundsInComposeViewRoot.value.size }
    }

    // Make sure we don't read animatorState directly here to avoid recomposition every time the
    // state changes (i.e. every frame of the animation).
    val isAnimating by remember {
        derivedStateOf {
            controller.animatorState.value != null && controller.overlay.value != null
        }
    }

    when {
        isAnimating -> {
            // Don't compose the movable content during the animation, as it should be composed only
            // once at all times. We make this spacer exactly the same size as this Expandable when
            // it is visible.
            Spacer(
                modifier
                    .clip(shape)
                    .requiredSize(with(controller.density) { thisExpandableSize.toDpSize() })
            )

            // The content and its animated background in the overlay. We draw it only when we are
            // animating.
            AnimatedContentInOverlay(
                color,
                thisExpandableSize,
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
                    .drawWithContent { /* Don't draw anything when the dialog is shown. */}
                    .onGloballyPositioned {
                        controller.boundsInComposeViewRoot.value = it.boundsInRoot()
                    }
            ) { wrappedContent(controller) }
        }
        else -> {
            Box(
                modifier.clip(shape).background(color, shape).onGloballyPositioned {
                    controller.boundsInComposeViewRoot.value = it.boundsInRoot()
                }
            ) { wrappedContent(controller) }
        }
    }
}

/** Draw [content] in [overlay] while respecting its screen position given by [animatorState]. */
@Composable
private fun AnimatedContentInOverlay(
    color: Color,
    sizeInOriginalLayout: Size,
    animatorState: State<LaunchAnimator.State?>,
    overlay: ViewGroupOverlay,
    controller: ExpandableController,
    content: @Composable (ExpandableController) -> Unit,
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

                                val topRadius = animatorState.topCornerRadius
                                val bottomRadius = animatorState.bottomCornerRadius
                                if (topRadius == bottomRadius) {
                                    // Shortcut to avoid Outline calculation and allocation.
                                    val cornerRadius = CornerRadius(topRadius)
                                    drawRoundRect(color, cornerRadius = cornerRadius)
                                } else {
                                    val shape =
                                        RoundedCornerShape(
                                            topStart = topRadius,
                                            topEnd = topRadius,
                                            bottomStart = bottomRadius,
                                            bottomEnd = bottomRadius,
                                        )
                                    val outline = shape.createOutline(size, layoutDirection, this)
                                    drawOutline(outline, color = color)
                                }

                                drawContent()
                            },
                            // We center the content in the expanding container.
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(contentModifier) { content(controller) }
                        }
                    }
                }

            // Set the owners.
            val overlayViewGroup =
                getOverlayViewGroup(
                    context,
                    overlay,
                )
            ViewTreeLifecycleOwner.set(
                overlayViewGroup,
                ViewTreeLifecycleOwner.get(composeViewRoot),
            )
            ViewTreeViewModelStoreOwner.set(
                overlayViewGroup,
                ViewTreeViewModelStoreOwner.get(composeViewRoot),
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
    state: LaunchAnimator.State,
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
