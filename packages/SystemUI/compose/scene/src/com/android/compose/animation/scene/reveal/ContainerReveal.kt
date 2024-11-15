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

package com.android.compose.animation.scene.reveal

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DeferredTargetAnimation
import androidx.compose.animation.core.ExperimentalAnimatableApi
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.CustomPropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformationScope
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

interface ContainerRevealHaptics {
    /**
     * Called when the reveal threshold is crossed while the user was dragging on screen.
     *
     * Important: This callback is called during layout and its implementation should therefore be
     * very fast or posted to a different thread.
     *
     * @param revealed whether we go from hidden to revealed, i.e. whether the container size is
     *   going to jump from a smaller size to a bigger size.
     */
    fun onRevealThresholdCrossed(revealed: Boolean)
}

/** Animate the reveal of [container] by animating its size. */
fun TransitionBuilder.verticalContainerReveal(
    container: ElementKey,
    haptics: ContainerRevealHaptics,
) {
    // Make the swipe distance be exactly the target height of the container.
    // TODO(b/376438969): Make sure that this works correctly when the target size of the element
    // is changing during the transition (e.g. a notification was added). At the moment, the user
    // action distance is only called until it returns a value > 0f, which is then cached.
    distance = UserActionDistance { fromContent, toContent, _ ->
        val targetSizeInFromContent = container.targetSize(fromContent)
        val targetSizeInToContent = container.targetSize(toContent)
        if (targetSizeInFromContent != null && targetSizeInToContent != null) {
            error(
                "verticalContainerReveal should not be used with shared elements, but " +
                    "${container.debugName} is in both ${fromContent.debugName} and " +
                    toContent.debugName
            )
        }

        (targetSizeInToContent?.height ?: targetSizeInFromContent?.height)?.toFloat() ?: 0f
    }

    // TODO(b/376438969): Improve the motion of this gesture using Motion Mechanics.

    // The min distance to swipe before triggering the reveal spring.
    val distanceThreshold = 80.dp

    // The minimum height of the container.
    val minHeight = 10.dp

    // The amount removed from the container width at 0% progress.
    val widthDelta = 140.dp

    // The ratio at which the distance is tracked before reaching the threshold, e.g. if the user
    // drags 60dp then the height will be 60dp * 0.25f = 15dp.
    val trackingRatio = 0.25f

    // The max progress starting from which the container should always be visible, even if we are
    // animating the container out. This is used so that we don't immediately fade out the container
    // when triggering a one-off animation that hides it.
    val alphaProgressThreshold = 0.05f

    // The spring animating the size of the container.
    val sizeSpec = spring<Float>(stiffness = 380f, dampingRatio = 0.9f)

    // The spring animating the alpha of the container.
    val alphaSpec = spring<Float>(stiffness = 1200f, dampingRatio = 0.99f)

    // The spring animating the progress when releasing the finger.
    swipeSpec =
        spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy,
            visibilityThreshold = 0.5f,
        )

    // Size transformation.
    transformation(container) {
        VerticalContainerRevealSizeTransformation(
            haptics,
            distanceThreshold,
            trackingRatio,
            minHeight,
            widthDelta,
            sizeSpec,
        )
    }

    // Alpha transformation.
    transformation(container) {
        ContainerRevealAlphaTransformation(alphaSpec, alphaProgressThreshold)
    }
}

@OptIn(ExperimentalAnimatableApi::class)
private class VerticalContainerRevealSizeTransformation(
    private val haptics: ContainerRevealHaptics,
    private val distanceThreshold: Dp,
    private val trackingRatio: Float,
    private val minHeight: Dp,
    private val widthDelta: Dp,
    private val spec: FiniteAnimationSpec<Float>,
) : CustomPropertyTransformation<IntSize> {
    override val property = PropertyTransformation.Property.Size

    private val widthAnimation = DeferredTargetAnimation(Float.VectorConverter)
    private val heightAnimation = DeferredTargetAnimation(Float.VectorConverter)

    private var previousHasReachedThreshold: Boolean? = null

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        transitionScope: CoroutineScope,
    ): IntSize {
        // The distance to go to 100%. Note that we don't use
        // TransitionState.HasOverscrollProperties.absoluteDistance because the transition will not
        // implement HasOverscrollProperties if the transition is triggered and not gesture based.
        val idleSize = checkNotNull(element.targetSize(content))
        val userActionDistance = idleSize.height
        val progress =
            when ((transition as? TransitionState.HasOverscrollProperties)?.bouncingContent) {
                null -> transition.progressTo(content)
                content -> 1f
                else -> 0f
            }
        val distance = (progress * userActionDistance).fastCoerceAtLeast(0f)
        val threshold = distanceThreshold.toPx()

        // Width.
        val widthDelta = widthDelta.toPx()
        val width =
            (idleSize.width - widthDelta +
                    animateSize(
                        size = widthDelta,
                        distance = distance,
                        threshold = threshold,
                        transitionScope = transitionScope,
                        animation = widthAnimation,
                    ))
                .roundToInt()

        // Height.
        val minHeight = minHeight.toPx()
        val height =
            (
                // 1) The minimum size of the container.
                minHeight +

                    // 2) The animated size between the minimum size and the threshold.
                    animateSize(
                        size = threshold - minHeight,
                        distance = distance,
                        threshold = threshold,
                        transitionScope = transitionScope,
                        animation = heightAnimation,
                    ) +

                    // 3) The remaining height after the threshold, tracking the finger.
                    (distance - threshold).fastCoerceAtLeast(0f))
                .roundToInt()
                .fastCoerceAtMost(idleSize.height)

        // Haptics.
        val hasReachedThreshold = distance >= threshold
        if (
            previousHasReachedThreshold != null &&
                hasReachedThreshold != previousHasReachedThreshold &&
                transition.isUserInputOngoing
        ) {
            haptics.onRevealThresholdCrossed(revealed = hasReachedThreshold)
        }
        previousHasReachedThreshold = hasReachedThreshold

        return IntSize(width = width, height = height)
    }

    /**
     * Animate a size up to [size], so that it is equal to 0f when distance is 0f and equal to
     * [size] when `distance >= threshold`, taking the [trackingRatio] into account.
     */
    @OptIn(ExperimentalAnimatableApi::class)
    private fun animateSize(
        size: Float,
        distance: Float,
        threshold: Float,
        transitionScope: CoroutineScope,
        animation: DeferredTargetAnimation<Float, AnimationVector1D>,
    ): Float {
        val trackingSize = distance.fastCoerceAtMost(threshold) / threshold * size * trackingRatio
        val springTarget =
            if (distance >= threshold) {
                size * (1f - trackingRatio)
            } else {
                0f
            }
        val springSize = animation.updateTarget(springTarget, transitionScope, spec)
        return trackingSize + springSize
    }
}

@OptIn(ExperimentalAnimatableApi::class)
private class ContainerRevealAlphaTransformation(
    private val spec: FiniteAnimationSpec<Float>,
    private val progressThreshold: Float,
) : CustomPropertyTransformation<Float> {
    override val property = PropertyTransformation.Property.Alpha
    private val alphaAnimation = DeferredTargetAnimation(Float.VectorConverter)

    override fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        transitionScope: CoroutineScope,
    ): Float {
        return alphaAnimation.updateTarget(targetAlpha(transition, content), transitionScope, spec)
    }

    private fun targetAlpha(transition: TransitionState.Transition, content: ContentKey): Float {
        if (transition.isUserInputOngoing) {
            if (transition !is TransitionState.HasOverscrollProperties) {
                error(
                    "Unsupported transition driven by user input but that does not have " +
                        "overscroll properties: $transition"
                )
            }

            val bouncingContent = transition.bouncingContent
            return if (bouncingContent != null) {
                if (bouncingContent == content) 1f else 0f
            } else {
                if (transition.progressTo(content) > 0f) 1f else 0f
            }
        }

        // The transition was committed (the user released their finger), so the alpha depends on
        // whether we are animating towards the content (showing the container) or away from it
        // (hiding the container).
        val isShowingContainer =
            when (content) {
                is SceneKey -> transition.currentScene == content
                is OverlayKey -> transition.currentOverlays.contains(content)
            }

        return if (isShowingContainer || transition.progressTo(content) >= progressThreshold) {
            1f
        } else {
            0f
        }
    }
}
