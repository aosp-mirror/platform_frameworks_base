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

import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import android.view.ViewRootImpl
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator
import kotlin.math.roundToInt

/** A controller that can control animated launches from an [Expandable]. */
interface ExpandableController {
    /** The [Expandable] controlled by this controller. */
    val expandable: Expandable
}

/**
 * Create an [ExpandableController] to control an [Expandable]. This is useful if you need to create
 * the controller before the [Expandable], for instance to handle clicks outside of the Expandable
 * that would still trigger a dialog/activity launch animation.
 */
@Composable
fun rememberExpandableController(
    color: Color,
    shape: Shape,
    contentColor: Color = contentColorFor(color),
    borderStroke: BorderStroke? = null,
): ExpandableController {
    val composeViewRoot = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // The current animation state, if we are currently animating a dialog or activity.
    val animatorState = remember { mutableStateOf<TransitionAnimator.State?>(null) }

    // Whether a dialog controlled by this ExpandableController is currently showing.
    val isDialogShowing = remember { mutableStateOf(false) }

    // The overlay in which we should animate the launch.
    val overlay = remember { mutableStateOf<ViewGroupOverlay?>(null) }

    // The current [ComposeView] being animated in the [overlay], if any.
    val currentComposeViewInOverlay = remember { mutableStateOf<View?>(null) }

    // The bounds in [composeViewRoot] of the expandable controlled by this controller.
    val boundsInComposeViewRoot = remember { mutableStateOf(Rect.Zero) }

    // Whether this composable is still composed. We only do the dialog exit animation if this is
    // true.
    val isComposed = remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { isComposed.value = false } }

    return remember(
        color,
        contentColor,
        shape,
        borderStroke,
        composeViewRoot,
        density,
        layoutDirection,
    ) {
        ExpandableControllerImpl(
            color,
            contentColor,
            shape,
            borderStroke,
            composeViewRoot,
            density,
            animatorState,
            isDialogShowing,
            overlay,
            currentComposeViewInOverlay,
            boundsInComposeViewRoot,
            layoutDirection,
            isComposed,
        )
    }
}

internal class ExpandableControllerImpl(
    internal val color: Color,
    internal val contentColor: Color,
    internal val shape: Shape,
    internal val borderStroke: BorderStroke?,
    internal val composeViewRoot: View,
    internal val density: Density,
    internal val animatorState: MutableState<TransitionAnimator.State?>,
    internal val isDialogShowing: MutableState<Boolean>,
    internal val overlay: MutableState<ViewGroupOverlay?>,
    internal val currentComposeViewInOverlay: MutableState<View?>,
    internal val boundsInComposeViewRoot: MutableState<Rect>,
    private val layoutDirection: LayoutDirection,
    private val isComposed: State<Boolean>,
) : ExpandableController {
    override val expandable: Expandable =
        object : Expandable {
            override fun activityTransitionController(
                launchCujType: Int?,
                cookie: ActivityTransitionAnimator.TransitionCookie?,
                component: ComponentName?,
                returnCujType: Int?
            ): ActivityTransitionAnimator.Controller? {
                if (!isComposed.value) {
                    return null
                }

                return activityController(launchCujType, cookie, component, returnCujType)
            }

            override fun dialogTransitionController(
                cuj: DialogCuj?
            ): DialogTransitionAnimator.Controller? {
                if (!isComposed.value) {
                    return null
                }

                return dialogController(cuj)
            }
        }

    /**
     * Create a [TransitionAnimator.Controller] that is going to be used to drive an activity or
     * dialog animation. This controller will:
     * 1. Compute the start/end animation state using [boundsInComposeViewRoot] and the location of
     *    composeViewRoot on the screen.
     * 2. Update [animatorState] with the current animation state if we are animating, or null
     *    otherwise.
     */
    private fun transitionController(): TransitionAnimator.Controller {
        return object : TransitionAnimator.Controller {
            private val rootLocationOnScreen = intArrayOf(0, 0)

            override var transitionContainer: ViewGroup = composeViewRoot.rootView as ViewGroup

            override val isLaunching: Boolean = true

            override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                animatorState.value = null
            }

            override fun onTransitionAnimationProgress(
                state: TransitionAnimator.State,
                progress: Float,
                linearProgress: Float
            ) {
                // We copy state given that it's always the same object that is mutated by
                // ActivityTransitionAnimator.
                animatorState.value =
                    TransitionAnimator.State(
                            state.top,
                            state.bottom,
                            state.left,
                            state.right,
                            state.topCornerRadius,
                            state.bottomCornerRadius,
                        )
                        .apply { visible = state.visible }

                // Force measure and layout the ComposeView in the overlay whenever the animation
                // state changes.
                currentComposeViewInOverlay.value?.let {
                    measureAndLayoutComposeViewInOverlay(it, state)
                }
            }

            override fun createAnimatorState(): TransitionAnimator.State {
                val boundsInRoot = boundsInComposeViewRoot.value
                val outline =
                    shape.createOutline(
                        Size(boundsInRoot.width, boundsInRoot.height),
                        layoutDirection,
                        density,
                    )

                val (topCornerRadius, bottomCornerRadius) =
                    when (outline) {
                        is Outline.Rectangle -> 0f to 0f
                        is Outline.Rounded -> {
                            val roundRect = outline.roundRect

                            // TODO(b/230830644): Add better support different corner radii.
                            val topCornerRadius =
                                maxOf(
                                    roundRect.topLeftCornerRadius.x,
                                    roundRect.topLeftCornerRadius.y,
                                    roundRect.topRightCornerRadius.x,
                                    roundRect.topRightCornerRadius.y,
                                )
                            val bottomCornerRadius =
                                maxOf(
                                    roundRect.bottomLeftCornerRadius.x,
                                    roundRect.bottomLeftCornerRadius.y,
                                    roundRect.bottomRightCornerRadius.x,
                                    roundRect.bottomRightCornerRadius.y,
                                )

                            topCornerRadius to bottomCornerRadius
                        }
                        else ->
                            error(
                                "ExpandableState only supports (rounded) rectangles at the " +
                                    "moment."
                            )
                    }

                val rootLocation = rootLocationOnScreen()
                return TransitionAnimator.State(
                    top = rootLocation.y.roundToInt(),
                    bottom = (rootLocation.y + boundsInRoot.height).roundToInt(),
                    left = rootLocation.x.roundToInt(),
                    right = (rootLocation.x + boundsInRoot.width).roundToInt(),
                    topCornerRadius = topCornerRadius,
                    bottomCornerRadius = bottomCornerRadius,
                )
            }

            private fun rootLocationOnScreen(): Offset {
                composeViewRoot.getLocationOnScreen(rootLocationOnScreen)
                val boundsInRoot = boundsInComposeViewRoot.value
                val x = rootLocationOnScreen[0] + boundsInRoot.left
                val y = rootLocationOnScreen[1] + boundsInRoot.top
                return Offset(x, y)
            }
        }
    }

    /** Create an [ActivityTransitionAnimator.Controller] that can be used to animate activities. */
    private fun activityController(
        launchCujType: Int?,
        cookie: ActivityTransitionAnimator.TransitionCookie?,
        component: ComponentName?,
        returnCujType: Int?
    ): ActivityTransitionAnimator.Controller {
        val delegate = transitionController()
        return object :
            ActivityTransitionAnimator.Controller, TransitionAnimator.Controller by delegate {
            /**
             * CUJ identifier accounting for whether this controller is for a launch or a return.
             */
            private val cujType: Int?
                get() =
                    if (isLaunching) {
                        launchCujType
                    } else {
                        returnCujType
                    }

            override val transitionCookie = cookie
            override val component = component

            override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
                delegate.onTransitionAnimationStart(isExpandingFullyAbove)
                overlay.value = composeViewRoot.rootView.overlay as ViewGroupOverlay
                cujType?.let { InteractionJankMonitor.getInstance().begin(composeViewRoot, it) }
            }

            override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                cujType?.let { InteractionJankMonitor.getInstance().end(it) }
                delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
                overlay.value = null
            }
        }
    }

    private fun dialogController(cuj: DialogCuj?): DialogTransitionAnimator.Controller {
        return object : DialogTransitionAnimator.Controller {
            override val viewRoot: ViewRootImpl? = composeViewRoot.viewRootImpl
            override val sourceIdentity: Any = this@ExpandableControllerImpl
            override val cuj: DialogCuj? = cuj

            override fun startDrawingInOverlayOf(viewGroup: ViewGroup) {
                val newOverlay = viewGroup.overlay as ViewGroupOverlay
                if (newOverlay != overlay.value) {
                    overlay.value = newOverlay
                }
            }

            override fun stopDrawingInOverlay() {
                if (overlay.value != null) {
                    overlay.value = null
                }
            }

            override fun createTransitionController(): TransitionAnimator.Controller {
                val delegate = transitionController()
                return object : TransitionAnimator.Controller by delegate {
                    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)

                        // Make sure we don't draw this expandable when the dialog is showing.
                        isDialogShowing.value = true
                    }
                }
            }

            override fun createExitController(): TransitionAnimator.Controller {
                val delegate = transitionController()
                return object : TransitionAnimator.Controller by delegate {
                    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
                        isDialogShowing.value = false
                    }
                }
            }

            override fun shouldAnimateExit(): Boolean =
                isComposed.value && composeViewRoot.isAttachedToWindow && composeViewRoot.isShown

            override fun onExitAnimationCancelled() {
                isDialogShowing.value = false
            }

            override fun jankConfigurationBuilder(): InteractionJankMonitor.Configuration.Builder? {
                val type = cuj?.cujType ?: return null
                return InteractionJankMonitor.Configuration.Builder.withView(type, composeViewRoot)
            }
        }
    }
}
