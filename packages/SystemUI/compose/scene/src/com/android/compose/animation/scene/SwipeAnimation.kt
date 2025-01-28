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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.compose.animation.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.Companion.DistanceUnspecified
import com.android.mechanics.GestureContext
import com.android.mechanics.MutableDragOffsetGestureContext
import kotlin.math.absoluteValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

internal fun createSwipeAnimation(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
    distance: Float,
    gestureContext: MutableDragOffsetGestureContext,
    decayAnimationSpec: DecayAnimationSpec<Float>,
): SwipeAnimation<*> {
    return createSwipeAnimation(
        layoutState,
        result,
        isUpOrLeft,
        orientation,
        distance = { distance },
        contentForUserActions = {
            error("Computing contentForUserActions requires a SceneTransitionLayoutImpl")
        },
        gestureContext = gestureContext,
        decayAnimationSpec = decayAnimationSpec,
    )
}

internal fun createSwipeAnimation(
    layoutImpl: SceneTransitionLayoutImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
    gestureContext: MutableDragOffsetGestureContext,
    decayAnimationSpec: DecayAnimationSpec<Float>,
    distance: Float = DistanceUnspecified,
): SwipeAnimation<*> {
    var lastDistance = distance

    fun distance(animation: SwipeAnimation<*>): Float {
        if (lastDistance != DistanceUnspecified) {
            return lastDistance
        }

        val absoluteDistance =
            with(animation.contentTransition.transformationSpec.distance ?: DefaultSwipeDistance) {
                layoutImpl.userActionDistanceScope.absoluteDistance(
                    fromContent = animation.fromContent,
                    toContent = animation.toContent,
                    orientation = orientation,
                )
            }

        if (absoluteDistance <= 0f) {
            return DistanceUnspecified
        }

        // Compute the signed distance and make sure that the offset is always coerced in the right
        // range.
        val distance =
            if (isUpOrLeft) {
                animation.dragOffset = animation.dragOffset.fastCoerceIn(-absoluteDistance, 0f)
                -absoluteDistance
            } else {
                animation.dragOffset = animation.dragOffset.fastCoerceIn(0f, absoluteDistance)
                absoluteDistance
            }
        lastDistance = distance
        return distance
    }

    return createSwipeAnimation(
        layoutImpl.state,
        result,
        isUpOrLeft,
        orientation,
        distance = ::distance,
        contentForUserActions = { layoutImpl.contentForUserActions().key },
        gestureContext = gestureContext,
        decayAnimationSpec = decayAnimationSpec,
    )
}

private fun createSwipeAnimation(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
    distance: (SwipeAnimation<*>) -> Float,
    contentForUserActions: () -> ContentKey,
    gestureContext: MutableDragOffsetGestureContext,
    decayAnimationSpec: DecayAnimationSpec<Float>,
): SwipeAnimation<*> {
    fun <T : ContentKey> swipeAnimation(fromContent: T, toContent: T): SwipeAnimation<T> {
        return SwipeAnimation(
            layoutState = layoutState,
            fromContent = fromContent,
            toContent = toContent,
            orientation = orientation,
            isUpOrLeft = isUpOrLeft,
            requiresFullDistanceSwipe = result.requiresFullDistanceSwipe,
            distance = distance,
            gestureContext = gestureContext,
            decayAnimationSpec = decayAnimationSpec,
        )
    }

    return when (result) {
        is UserActionResult.ChangeScene -> {
            val fromScene = layoutState.currentScene
            val toScene = result.toScene
            ChangeSceneSwipeTransition(
                    swipeAnimation = swipeAnimation(fromContent = fromScene, toContent = toScene),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }

        is UserActionResult.ShowOverlay -> {
            val fromScene = layoutState.currentScene
            val overlay = result.overlay
            ShowOrHideOverlaySwipeTransition(
                    swipeAnimation = swipeAnimation(fromContent = fromScene, toContent = overlay),
                    overlay = overlay,
                    fromOrToScene = fromScene,
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }

        is UserActionResult.HideOverlay -> {
            val toScene = layoutState.currentScene
            val overlay = result.overlay
            ShowOrHideOverlaySwipeTransition(
                    swipeAnimation = swipeAnimation(fromContent = overlay, toContent = toScene),
                    overlay = overlay,
                    fromOrToScene = toScene,
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }

        is UserActionResult.ReplaceByOverlay -> {
            val fromOverlay =
                when (val contentForUserActions = contentForUserActions()) {
                    is SceneKey ->
                        error("ReplaceByOverlay can only be called when an overlay is shown")

                    is OverlayKey -> contentForUserActions
                }

            val toOverlay = result.overlay
            ReplaceOverlaySwipeTransition(
                    swipeAnimation =
                        swipeAnimation(fromContent = fromOverlay, toContent = toOverlay),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
    }
}

/** A helper class that contains the main logic for swipe transitions. */
internal class SwipeAnimation<T : ContentKey>(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val fromContent: T,
    val toContent: T,
    val orientation: Orientation,
    val isUpOrLeft: Boolean,
    val requiresFullDistanceSwipe: Boolean,
    private val distance: (SwipeAnimation<T>) -> Float,
    currentContent: T = fromContent,
    private val gestureContext: MutableDragOffsetGestureContext,
    private val decayAnimationSpec: DecayAnimationSpec<Float>,
) : MutableDragOffsetGestureContext by gestureContext {
    /** The [TransitionState.Transition] whose implementation delegates to this [SwipeAnimation]. */
    lateinit var contentTransition: TransitionState.Transition

    private var _currentContent by mutableStateOf(currentContent)
    var currentContent: T
        get() = _currentContent
        set(value) {
            check(!isAnimatingOffset()) {
                "currentContent can not be changed once we are animating the offset"
            }
            _currentContent = value
        }

    val progress: Float
        get() {
            // Important: If we are going to return early because distance is equal to 0, we should
            // still make sure we read the offset before returning so that the calling code still
            // subscribes to the offset value.
            val animatable = offsetAnimation
            val offset =
                when {
                    isInPreviewStage -> 0f
                    animatable != null -> animatable.value
                    else -> dragOffset
                }

            return computeProgress(offset)
        }

    fun computeProgress(offset: Float): Float {
        val distance = distance()
        if (distance == DistanceUnspecified) {
            return 0f
        }
        return offset / distance
    }

    val progressVelocity: Float
        get() {
            val animatable = offsetAnimation ?: return 0f
            val distance = distance()
            if (distance == DistanceUnspecified) {
                return 0f
            }

            val velocityInDistanceUnit = animatable.velocity
            return velocityInDistanceUnit / distance.absoluteValue
        }

    val previewProgress: Float
        get() {
            val offset =
                if (isInPreviewStage) {
                    offsetAnimation?.value ?: dragOffset
                } else {
                    dragOffset
                }
            return computeProgress(offset)
        }

    val previewProgressVelocity: Float
        get() = 0f

    val isInPreviewStage: Boolean
        get() = contentTransition.previewTransformationSpec != null && currentContent == fromContent

    /** The offset animation that animates the offset once the user lifts their finger. */
    private var offsetAnimation: Animatable<Float, AnimationVector1D>? by mutableStateOf(null)
    private val offsetAnimationRunnable = CompletableDeferred<suspend () -> Unit>()

    val isUserInputOngoing: Boolean
        get() = offsetAnimation == null

    suspend fun run() {
        // This animation will first be driven by finger, then when the user lift their finger we
        // start an animation to the target offset (progress = 1f or progress = 0f). We await() for
        // offsetAnimationRunnable to be completed and then run it.
        val runAnimation = offsetAnimationRunnable.await() ?: return
        runAnimation()
    }

    /**
     * The signed distance between [fromContent] and [toContent]. It is negative if [fromContent] is
     * above or to the left of [toContent].
     *
     * Note that this distance can be equal to [DistanceUnspecified] during the first frame of a
     * transition when the distance depends on the size or position of an element that is composed
     * in the content we are going to.
     */
    fun distance(): Float = distance(this)

    fun isAnimatingOffset(): Boolean = offsetAnimation != null

    /** Get the [ContentKey] ([fromContent] or [toContent]) associated to the current [direction] */
    fun contentByDirection(direction: Float): T {
        require(direction != 0f) { "Cannot find a content in this direction: $direction" }
        val isDirectionToContent = (isUpOrLeft && direction < 0) || (!isUpOrLeft && direction > 0)
        return if (isDirectionToContent) {
            toContent
        } else {
            fromContent
        }
    }

    /**
     * Animate the offset to a [targetContent], using the [initialVelocity] and an optional [spec]
     *
     * @return the velocity consumed
     */
    suspend fun animateOffset(
        initialVelocity: Float,
        targetContent: T,
        spec: AnimationSpec<Float>? = null,
        awaitFling: (suspend () -> Unit)? = null,
    ): Float {
        check(!isAnimatingOffset()) { "SwipeAnimation.animateOffset() can only be called once" }

        val targetContent =
            if (targetContent != currentContent && !canChangeContent(targetContent)) {
                currentContent
            } else {
                targetContent
            }

        val distance = distance()
        val targetOffset =
            if (targetContent == fromContent) {
                0f
            } else {
                check(distance != DistanceUnspecified) {
                    "distance is equal to $DistanceUnspecified"
                }
                distance
            }

        // If the effective current content changed, it should be reflected right now in the
        // current state, even before the settle animation is ongoing. That way all the
        // swipeables and back handlers will be refreshed and the user can for instance quickly
        // swipe vertically from A => B then horizontally from B => C, or swipe from A => B then
        // immediately go back B => A.
        if (targetContent != currentContent) {
            currentContent = targetContent
        }

        val initialOffset =
            if (contentTransition.previewTransformationSpec != null && targetContent == toContent) {
                0f
            } else {
                dragOffset
            }

        val animatable =
            Animatable(initialOffset, OffsetVisibilityThreshold).also {
                offsetAnimation = it

                // We should animate when the progress value is between [0, 1].
                if (distance > 0) {
                    it.updateBounds(0f, distance)
                } else {
                    it.updateBounds(distance, 0f)
                }
            }

        check(isAnimatingOffset())

        val velocityConsumed = CompletableDeferred<Float>()
        offsetAnimationRunnable.complete {
            val consumed = animateOffset(animatable, targetOffset, initialVelocity, spec)
            velocityConsumed.complete(consumed)

            // Wait for overscroll to finish so that the transition is removed from the STLState
            // only after the overscroll is done, to avoid dropping frame right when the user lifts
            // their finger and overscroll is animated to 0.
            awaitFling?.invoke()
        }

        return velocityConsumed.await()
    }

    private suspend fun animateOffset(
        animatable: Animatable<Float, AnimationVector1D>,
        targetOffset: Float,
        initialVelocity: Float,
        spec: AnimationSpec<Float>?,
    ): Float {
        val initialOffset = animatable.value
        val decayOffset =
            decayAnimationSpec.calculateTargetValue(
                initialVelocity = initialVelocity,
                initialValue = initialOffset,
            )

        // The decay animation should only play if decayOffset exceeds targetOffset.
        val lowerBound = checkNotNull(animatable.lowerBound) { "No lower bound" }
        val upperBound = checkNotNull(animatable.upperBound) { "No upper bound" }
        val willDecayReachBounds =
            when (targetOffset) {
                lowerBound -> decayOffset <= lowerBound
                upperBound -> decayOffset >= upperBound
                else -> error("Target $targetOffset should be $lowerBound or $upperBound")
            }

        if (willDecayReachBounds) {
            val result = animatable.animateDecay(initialVelocity, decayAnimationSpec)
            check(animatable.value == targetOffset) {
                buildString {
                    appendLine(
                        "animatable.value = ${animatable.value} != $targetOffset = targetOffset"
                    )
                    appendLine("  initialOffset=$initialOffset")
                    appendLine("  targetOffset=$targetOffset")
                    appendLine("  initialVelocity=$initialVelocity")
                    appendLine("  decayOffset=$decayOffset")
                    appendLine(
                        "  animateDecay result: reason=${result.endReason} " +
                            "value=${result.endState.value} velocity=${result.endState.velocity}"
                    )
                }
            }
            return initialVelocity - result.endState.velocity
        }

        val motionSpatialSpec = spec ?: layoutState.motionScheme.defaultSpatialSpec()
        animatable.animateTo(
            targetValue = targetOffset,
            animationSpec = motionSpatialSpec,
            initialVelocity = initialVelocity,
        )

        // We consumed the whole velocity.
        return initialVelocity
    }

    private fun canChangeContent(targetContent: ContentKey): Boolean {
        return when (val transition = contentTransition) {
            is TransitionState.Transition.ChangeScene ->
                layoutState.canChangeScene(targetContent as SceneKey)

            is TransitionState.Transition.ShowOrHideOverlay -> {
                if (targetContent == transition.overlay) {
                    layoutState.canShowOverlay(transition.overlay)
                } else {
                    layoutState.canHideOverlay(transition.overlay)
                }
            }

            is TransitionState.Transition.ReplaceOverlay -> {
                val to = targetContent as OverlayKey
                val from =
                    if (to == transition.toOverlay) transition.fromOverlay else transition.toOverlay
                layoutState.canReplaceOverlay(from, to)
            }
        }
    }

    fun freezeAndAnimateToCurrentState() {
        if (isAnimatingOffset()) return

        contentTransition.coroutineScope.launch {
            animateOffset(initialVelocity = 0f, targetContent = currentContent)
        }
    }
}

private object DefaultSwipeDistance : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromContent: ContentKey,
        toContent: ContentKey,
        orientation: Orientation,
    ): Float {
        val fromContentSize = checkNotNull(fromContent.targetSize())
        return when (orientation) {
            Orientation.Horizontal -> fromContentSize.width
            Orientation.Vertical -> fromContentSize.height
        }.toFloat()
    }
}

private class ChangeSceneSwipeTransition(
    val swipeAnimation: SwipeAnimation<SceneKey>,
    override val key: TransitionKey?,
    replacedTransition: ChangeSceneSwipeTransition?,
) :
    TransitionState.Transition.ChangeScene(
        swipeAnimation.fromContent,
        swipeAnimation.toContent,
        replacedTransition,
    ) {

    init {
        swipeAnimation.contentTransition = this
    }

    override val currentScene: SceneKey
        get() = swipeAnimation.currentContent

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val previewProgress: Float
        get() = swipeAnimation.previewProgress

    override val previewProgressVelocity: Float
        get() = swipeAnimation.previewProgressVelocity

    override val isInPreviewStage: Boolean
        get() = swipeAnimation.isInPreviewStage

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override val gestureContext: GestureContext = swipeAnimation

    override suspend fun run() {
        swipeAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        swipeAnimation.freezeAndAnimateToCurrentState()
    }
}

private class ShowOrHideOverlaySwipeTransition(
    val swipeAnimation: SwipeAnimation<ContentKey>,
    overlay: OverlayKey,
    fromOrToScene: SceneKey,
    override val key: TransitionKey?,
    replacedTransition: ShowOrHideOverlaySwipeTransition?,
) :
    TransitionState.Transition.ShowOrHideOverlay(
        overlay,
        fromOrToScene,
        swipeAnimation.fromContent,
        swipeAnimation.toContent,
        replacedTransition,
    ) {

    init {
        swipeAnimation.contentTransition = this
    }

    override val isEffectivelyShown: Boolean
        get() = swipeAnimation.currentContent == overlay

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val previewProgress: Float
        get() = swipeAnimation.previewProgress

    override val previewProgressVelocity: Float
        get() = swipeAnimation.previewProgressVelocity

    override val isInPreviewStage: Boolean
        get() = swipeAnimation.isInPreviewStage

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override val gestureContext: GestureContext = swipeAnimation

    override suspend fun run() {
        swipeAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        swipeAnimation.freezeAndAnimateToCurrentState()
    }
}

private class ReplaceOverlaySwipeTransition(
    val swipeAnimation: SwipeAnimation<OverlayKey>,
    override val key: TransitionKey?,
    replacedTransition: ReplaceOverlaySwipeTransition?,
) :
    TransitionState.Transition.ReplaceOverlay(
        swipeAnimation.fromContent,
        swipeAnimation.toContent,
        replacedTransition,
    ) {

    init {
        swipeAnimation.contentTransition = this
    }

    override val effectivelyShownOverlay: OverlayKey
        get() = swipeAnimation.currentContent

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val previewProgress: Float
        get() = swipeAnimation.previewProgress

    override val previewProgressVelocity: Float
        get() = swipeAnimation.previewProgressVelocity

    override val isInPreviewStage: Boolean
        get() = swipeAnimation.isInPreviewStage

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override val gestureContext: GestureContext = swipeAnimation

    override suspend fun run() {
        swipeAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        swipeAnimation.freezeAndAnimateToCurrentState()
    }
}
