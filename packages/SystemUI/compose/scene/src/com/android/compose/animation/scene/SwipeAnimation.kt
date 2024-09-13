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

package com.android.compose.animation.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified
import kotlin.math.absoluteValue
import kotlinx.coroutines.CompletableDeferred

internal fun createSwipeAnimation(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
    distance: Float,
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
    )
}

internal fun createSwipeAnimation(
    layoutImpl: SceneTransitionLayoutImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
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
                    layoutImpl.content(animation.fromContent).targetSize,
                    orientation,
                )
            }

        if (absoluteDistance <= 0f) {
            return DistanceUnspecified
        }

        val distance = if (isUpOrLeft) -absoluteDistance else absoluteDistance
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
    )
}

private fun createSwipeAnimation(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
    distance: (SwipeAnimation<*>) -> Float,
    contentForUserActions: () -> ContentKey,
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
        )
    }

    return when (result) {
        is UserActionResult.ChangeScene -> {
            val fromScene = layoutState.currentScene
            val toScene = result.toScene
            ChangeSceneSwipeTransition(
                    layoutState = layoutState,
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
                    layoutState = layoutState,
                    fromOrToScene = fromScene,
                    overlay = overlay,
                    swipeAnimation = swipeAnimation(fromContent = fromScene, toContent = overlay),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
        is UserActionResult.HideOverlay -> {
            val toScene = layoutState.currentScene
            val overlay = result.overlay
            ShowOrHideOverlaySwipeTransition(
                    layoutState = layoutState,
                    fromOrToScene = toScene,
                    overlay = overlay,
                    swipeAnimation = swipeAnimation(fromContent = overlay, toContent = toScene),
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
                    layoutState = layoutState,
                    swipeAnimation =
                        swipeAnimation(fromContent = fromOverlay, toContent = toOverlay),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
    }
}

internal fun createSwipeAnimation(old: SwipeAnimation<*>): SwipeAnimation<*> {
    return when (val transition = old.contentTransition) {
        is TransitionState.Transition.ChangeScene -> {
            ChangeSceneSwipeTransition(transition as ChangeSceneSwipeTransition).swipeAnimation
        }
        is TransitionState.Transition.ShowOrHideOverlay -> {
            ShowOrHideOverlaySwipeTransition(transition as ShowOrHideOverlaySwipeTransition)
                .swipeAnimation
        }
        is TransitionState.Transition.ReplaceOverlay -> {
            ReplaceOverlaySwipeTransition(transition as ReplaceOverlaySwipeTransition)
                .swipeAnimation
        }
    }
}

/** A helper class that contains the main logic for swipe transitions. */
internal class SwipeAnimation<T : ContentKey>(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val fromContent: T,
    val toContent: T,
    override val orientation: Orientation,
    override val isUpOrLeft: Boolean,
    val requiresFullDistanceSwipe: Boolean,
    private val distance: (SwipeAnimation<T>) -> Float,
    currentContent: T = fromContent,
    dragOffset: Float = 0f,
) : TransitionState.HasOverscrollProperties {
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

    override var bouncingContent: ContentKey? = null

    /** The current offset caused by the drag gesture. */
    var dragOffset by mutableFloatStateOf(dragOffset)

    /** The offset animation that animates the offset once the user lifts their finger. */
    private var offsetAnimation: Animatable<Float, AnimationVector1D>? by mutableStateOf(null)
    private val offsetAnimationRunnable = CompletableDeferred<(suspend () -> Unit)?>()

    val isUserInputOngoing: Boolean
        get() = offsetAnimation == null

    override val absoluteDistance: Float
        get() = distance().absoluteValue

    constructor(
        other: SwipeAnimation<T>
    ) : this(
        layoutState = other.layoutState,
        fromContent = other.fromContent,
        toContent = other.toContent,
        orientation = other.orientation,
        isUpOrLeft = other.isUpOrLeft,
        requiresFullDistanceSwipe = other.requiresFullDistanceSwipe,
        distance = other.distance,
        currentContent = other.currentContent,
        dragOffset = other.offsetAnimation?.value ?: other.dragOffset,
    )

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

    /**
     * Animate the offset to a [targetContent], using the [initialVelocity] and an optional [spec]
     *
     * @return the velocity consumed
     */
    fun animateOffset(
        initialVelocity: Float,
        targetContent: T,
        spec: AnimationSpec<Float>? = null,
    ): Float {
        check(!isAnimatingOffset()) { "SwipeAnimation.animateOffset() can only be called once" }

        val initialProgress = progress
        // Skip the animation if we have already reached the target content and the overscroll does
        // not animate anything.
        val hasReachedTargetContent =
            (targetContent == toContent && initialProgress >= 1f) ||
                (targetContent == fromContent && initialProgress <= 0f)
        val skipAnimation =
            hasReachedTargetContent && !contentTransition.isWithinProgressRange(initialProgress)

        val targetContent =
            if (targetContent != currentContent && !canChangeContent(targetContent)) {
                currentContent
            } else {
                targetContent
            }

        val targetOffset =
            if (targetContent == fromContent) {
                0f
            } else {
                val distance = distance()
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

        val startProgress =
            if (contentTransition.previewTransformationSpec != null && targetContent == toContent) {
                0f
            } else {
                dragOffset
            }

        val animatable =
            Animatable(startProgress, OffsetVisibilityThreshold).also { offsetAnimation = it }

        check(isAnimatingOffset())

        // Note: we still create the animatable and set it on offsetAnimation even when
        // skipAnimation is true, just so that isUserInputOngoing and isAnimatingOffset() are
        // unchanged even despite this small skip-optimization (which is just an implementation
        // detail).
        if (skipAnimation) {
            // Unblock the job.
            offsetAnimationRunnable.complete(null)
            return 0f
        }

        val isTargetGreater = targetOffset > animatable.value
        val startedWhenOvercrollingTargetContent =
            if (targetContent == fromContent) initialProgress < 0f else initialProgress > 1f

        val swipeSpec =
            spec
                ?: contentTransition.transformationSpec.swipeSpec
                ?: layoutState.transitions.defaultSwipeSpec

        offsetAnimationRunnable.complete {
            try {
                animatable.animateTo(
                    targetValue = targetOffset,
                    animationSpec = swipeSpec,
                    initialVelocity = initialVelocity,
                ) {
                    if (bouncingContent == null) {
                        val isBouncing =
                            if (isTargetGreater) {
                                if (startedWhenOvercrollingTargetContent) {
                                    value >= targetOffset
                                } else {
                                    value > targetOffset
                                }
                            } else {
                                if (startedWhenOvercrollingTargetContent) {
                                    value <= targetOffset
                                } else {
                                    value < targetOffset
                                }
                            }

                        if (isBouncing) {
                            bouncingContent = targetContent

                            // Immediately stop this transition if we are bouncing on a content that
                            // does not bounce.
                            if (!contentTransition.isWithinProgressRange(progress)) {
                                throw SnapException()
                            }
                        }
                    }
                }
            } catch (_: SnapException) {
                /* Ignore. */
            }
        }

        // This animation always consumes the whole available velocity
        return initialVelocity
    }

    /** An exception thrown during the animation to stop it immediately. */
    private class SnapException : Exception()

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

        animateOffset(initialVelocity = 0f, targetContent = currentContent)
    }
}

private object DefaultSwipeDistance : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromSceneSize: IntSize,
        orientation: Orientation,
    ): Float {
        return when (orientation) {
            Orientation.Horizontal -> fromSceneSize.width
            Orientation.Vertical -> fromSceneSize.height
        }.toFloat()
    }
}

private class ChangeSceneSwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<SceneKey>,
    override val key: TransitionKey?,
    replacedTransition: ChangeSceneSwipeTransition?,
) :
    TransitionState.Transition.ChangeScene(
        swipeAnimation.fromContent,
        swipeAnimation.toContent,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {

    constructor(
        other: ChangeSceneSwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        key = other.key,
        replacedTransition = other,
    )

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

    override suspend fun run() {
        swipeAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        swipeAnimation.freezeAndAnimateToCurrentState()
    }
}

private class ShowOrHideOverlaySwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
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
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {
    constructor(
        other: ShowOrHideOverlaySwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        overlay = other.overlay,
        fromOrToScene = other.fromOrToScene,
        key = other.key,
        replacedTransition = other,
    )

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

    override suspend fun run() {
        swipeAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        swipeAnimation.freezeAndAnimateToCurrentState()
    }
}

private class ReplaceOverlaySwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<OverlayKey>,
    override val key: TransitionKey?,
    replacedTransition: ReplaceOverlaySwipeTransition?,
) :
    TransitionState.Transition.ReplaceOverlay(
        swipeAnimation.fromContent,
        swipeAnimation.toContent,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {
    constructor(
        other: ReplaceOverlaySwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        key = other.key,
        replacedTransition = other,
    )

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

    override suspend fun run() {
        swipeAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        swipeAnimation.freezeAndAnimateToCurrentState()
    }
}
