/*
 * Copyright 2023 The Android Open Source Project
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

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import com.android.compose.animation.scene.transition.link.LinkedTransition
import com.android.compose.animation.scene.transition.link.StateLink
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/**
 * The state of a [SceneTransitionLayout].
 *
 * @see MutableSceneTransitionLayoutState
 * @see updateSceneTransitionLayoutState
 */
@Stable
sealed interface SceneTransitionLayoutState {
    /**
     * The current [TransitionState]. All values read here are backed by the Snapshot system.
     *
     * To observe those values outside of Compose/the Snapshot system, use
     * [SceneTransitionLayoutState.observableTransitionState] instead.
     */
    val transitionState: TransitionState

    /** The current transition, or `null` if we are idle. */
    val currentTransition: TransitionState.Transition?
        get() = transitionState as? TransitionState.Transition

    /** The [SceneTransitions] used when animating this state. */
    val transitions: SceneTransitions

    /**
     * Whether we are transitioning. If [from] or [to] is empty, we will also check that they match
     * the scenes we are animating from and/or to.
     */
    fun isTransitioning(from: SceneKey? = null, to: SceneKey? = null): Boolean

    /** Whether we are transitioning from [scene] to [other], or from [other] to [scene]. */
    fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean
}

/** A [SceneTransitionLayoutState] whose target scene can be imperatively set. */
sealed interface MutableSceneTransitionLayoutState : SceneTransitionLayoutState {
    /** The [SceneTransitions] used when animating this state. */
    override var transitions: SceneTransitions

    /**
     * Set the target scene of this state to [targetScene].
     *
     * If [targetScene] is the same as the [currentScene][TransitionState.currentScene] of
     * [transitionState], then nothing will happen and this will return `null`. Note that this means
     * that this will also do nothing if the user is currently swiping from [targetScene] to another
     * scene, or if we were already animating to [targetScene].
     *
     * If [targetScene] is different than the [currentScene][TransitionState.currentScene] of
     * [transitionState], then this will animate to [targetScene]. The associated
     * [TransitionState.Transition] will be returned and will be set as the current
     * [transitionState] of this [MutableSceneTransitionLayoutState].
     *
     * Note that because a non-null [TransitionState.Transition] is returned does not mean that the
     * transition will finish and that we will settle to [targetScene]. The returned transition
     * might still be interrupted, for instance by another call to [setTargetScene] or by a user
     * gesture.
     *
     * If [this] [CoroutineScope] is cancelled during the transition and that the transition was
     * still active, then the [transitionState] of this [MutableSceneTransitionLayoutState] will be
     * set to `TransitionState.Idle(targetScene)`.
     *
     * TODO(b/318794193): Add APIs to await() and cancel() any [TransitionState.Transition].
     */
    fun setTargetScene(
        targetScene: SceneKey,
        coroutineScope: CoroutineScope,
        transitionKey: TransitionKey? = null,
    ): TransitionState.Transition?
}

/**
 * Return a [MutableSceneTransitionLayoutState] initially idle at [initialScene].
 *
 * @param initialScene the initial scene to which this state is initialized.
 * @param transitions the [SceneTransitions] used when this state is transitioning between scenes.
 * @param canChangeScene whether we can transition to the given scene. This is called when the user
 *   commits a transition to a new scene because of a [UserAction]. If [canChangeScene] returns
 *   `true`, then the gesture will be committed and we will animate to the other scene. Otherwise,
 *   the gesture will be cancelled and we will animate back to the current scene.
 * @param stateLinks the [StateLink] connecting this [SceneTransitionLayoutState] to other
 *   [SceneTransitionLayoutState]s.
 */
fun MutableSceneTransitionLayoutState(
    initialScene: SceneKey,
    transitions: SceneTransitions = SceneTransitions.Empty,
    canChangeScene: (SceneKey) -> Boolean = { true },
    stateLinks: List<StateLink> = emptyList(),
): MutableSceneTransitionLayoutState {
    return MutableSceneTransitionLayoutStateImpl(
        initialScene,
        transitions,
        canChangeScene,
        stateLinks,
    )
}

/**
 * Sets up a [SceneTransitionLayoutState] and keeps it synced with [currentScene], [onChangeScene]
 * and [transitions]. New transitions will automatically be started whenever [currentScene] is
 * changed.
 *
 * @param currentScene the current scene
 * @param onChangeScene a mutator that should set [currentScene] to the given scene when called.
 *   This is called when the user commits a transition to a new scene because of a [UserAction], for
 *   instance by triggering back navigation or by swiping to a new scene.
 * @param transitions the definition of the transitions used to animate a change of scene.
 * @param canChangeScene whether we can transition to the given scene. This is called when the user
 *   commits a transition to a new scene because of a [UserAction]. If [canChangeScene] returns
 *   `true`, then [onChangeScene] will be called right afterwards with the same [SceneKey]. If it
 *   returns `false`, the user action will be cancelled and we will animate back to the current
 *   scene.
 * @param stateLinks the [StateLink] connecting this [SceneTransitionLayoutState] to other
 *   [SceneTransitionLayoutState]s.
 */
@Composable
fun updateSceneTransitionLayoutState(
    currentScene: SceneKey,
    onChangeScene: (SceneKey) -> Unit,
    transitions: SceneTransitions = SceneTransitions.Empty,
    canChangeScene: (SceneKey) -> Boolean = { true },
    stateLinks: List<StateLink> = emptyList(),
): SceneTransitionLayoutState {
    return remember {
            HoistedSceneTransitionLayoutState(
                currentScene,
                transitions,
                onChangeScene,
                canChangeScene,
                stateLinks,
            )
        }
        .apply { update(currentScene, onChangeScene, canChangeScene, transitions, stateLinks) }
}

@Stable
sealed interface TransitionState {
    /**
     * The current effective scene. If a new transition was triggered, it would start from this
     * scene.
     *
     * For instance, when swiping from scene A to scene B, the [currentScene] is A when the swipe
     * gesture starts, but then if the user flings their finger and commits the transition to scene
     * B, then [currentScene] becomes scene B even if the transition is not finished yet and is
     * still animating to settle to scene B.
     */
    val currentScene: SceneKey

    /** No transition/animation is currently running. */
    data class Idle(override val currentScene: SceneKey) : TransitionState

    /** There is a transition animating between two scenes. */
    abstract class Transition(
        /** The scene this transition is starting from. Can't be the same as toScene */
        val fromScene: SceneKey,

        /** The scene this transition is going to. Can't be the same as fromScene */
        val toScene: SceneKey,
    ) : TransitionState {
        /**
         * The progress of the transition. This is usually in the `[0; 1]` range, but it can also be
         * less than `0` or greater than `1` when using transitions with a spring AnimationSpec or
         * when flinging quickly during a swipe gesture.
         */
        abstract val progress: Float

        /** Whether the transition was triggered by user input rather than being programmatic. */
        abstract val isInitiatedByUserInput: Boolean

        /** Whether user input is currently driving the transition. */
        abstract val isUserInputOngoing: Boolean

        init {
            check(fromScene != toScene)
        }

        /**
         * Force this transition to finish and animate to [currentScene], so that this transition
         * progress will settle to either 0% (if [currentScene] == [fromScene]) or 100% (if
         * [currentScene] == [toScene]) in a finite amount of time.
         *
         * @return the [Job] that animates the progress to [currentScene]. It can be used to wait
         *   until the animation is complete or cancel it to snap to [currentScene]. Calling
         *   [finish] multiple times will return the same [Job].
         */
        abstract fun finish(): Job

        /**
         * Whether we are transitioning. If [from] or [to] is empty, we will also check that they
         * match the scenes we are animating from and/or to.
         */
        fun isTransitioning(from: SceneKey? = null, to: SceneKey? = null): Boolean {
            return (from == null || fromScene == from) && (to == null || toScene == to)
        }

        /** Whether we are transitioning from [scene] to [other], or from [other] to [scene]. */
        fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean {
            return isTransitioning(from = scene, to = other) ||
                isTransitioning(from = other, to = scene)
        }
    }

    interface HasOverscrollProperties {
        /**
         * The position of the [TransitionState.Transition.toScene].
         *
         * Used to understand the direction of the overscroll.
         */
        val isUpOrLeft: Boolean

        /**
         * The relative orientation between [TransitionState.Transition.fromScene] and
         * [TransitionState.Transition.toScene].
         *
         * Used to understand the orientation of the overscroll.
         */
        val orientation: Orientation
    }
}

internal abstract class BaseSceneTransitionLayoutState(
    initialScene: SceneKey,
    protected var stateLinks: List<StateLink>,
) : SceneTransitionLayoutState {
    override var transitionState: TransitionState by
        mutableStateOf(TransitionState.Idle(initialScene))
        protected set

    /**
     * The current [transformationSpec] associated to [transitionState]. Accessing this value makes
     * sense only if [transitionState] is a [TransitionState.Transition].
     */
    internal var transformationSpec: TransformationSpecImpl = TransformationSpec.Empty

    private var fromOverscrollSpec: OverscrollSpecImpl? = null
    private var toOverscrollSpec: OverscrollSpecImpl? = null

    /**
     * @return the overscroll [OverscrollSpecImpl] if it is defined for the current
     *   [transitionState] and we are currently over scrolling.
     */
    internal val currentOverscrollSpec: OverscrollSpecImpl?
        get() {
            val transition = currentTransition ?: return null
            if (transition !is TransitionState.HasOverscrollProperties) return null
            val progress = transition.progress
            return when {
                progress < 0f -> fromOverscrollSpec
                progress > 1f -> toOverscrollSpec
                else -> null
            }
        }

    private val activeTransitionLinks = mutableMapOf<StateLink, LinkedTransition>()

    /** Whether we can transition to the given [scene]. */
    internal abstract fun canChangeScene(scene: SceneKey): Boolean

    /**
     * Called when the [current scene][TransitionState.currentScene] should be changed to [scene].
     *
     * When this is called, the source of truth for the current scene should be changed so that
     * [transitionState] will animate and settle to [scene].
     */
    internal abstract fun CoroutineScope.onChangeScene(scene: SceneKey)

    override fun isTransitioning(from: SceneKey?, to: SceneKey?): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioning(from, to)
    }

    override fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioningBetween(scene, other)
    }

    /** Start a new [transition], instantly interrupting any ongoing transition if there was one. */
    internal fun startTransition(
        transition: TransitionState.Transition,
        transitionKey: TransitionKey?,
    ) {
        // Compute the [TransformationSpec] when the transition starts.
        val fromScene = transition.fromScene
        val toScene = transition.toScene
        val orientation = (transition as? TransitionState.HasOverscrollProperties)?.orientation
        transformationSpec =
            transitions.transitionSpec(fromScene, toScene, key = transitionKey).transformationSpec()
        fromOverscrollSpec = orientation?.let { transitions.overscrollSpec(fromScene, it) }
        toOverscrollSpec = orientation?.let { transitions.overscrollSpec(toScene, it) }
        cancelActiveTransitionLinks()
        setupTransitionLinks(transition)
        transitionState = transition
    }

    private fun cancelActiveTransitionLinks() {
        for ((link, linkedTransition) in activeTransitionLinks) {
            link.target.finishTransition(linkedTransition, linkedTransition.currentScene)
        }
        activeTransitionLinks.clear()
    }

    private fun setupTransitionLinks(transitionState: TransitionState) {
        if (transitionState !is TransitionState.Transition) return
        stateLinks.fastForEach { stateLink ->
            val matchingLinks =
                stateLink.transitionLinks.fastFilter { it.isMatchingLink(transitionState) }
            if (matchingLinks.isEmpty()) return@fastForEach
            if (matchingLinks.size > 1) error("More than one link matched.")

            val targetCurrentScene = stateLink.target.transitionState.currentScene
            val matchingLink = matchingLinks[0]

            if (!matchingLink.targetIsInValidState(targetCurrentScene)) return@fastForEach

            val linkedTransition =
                LinkedTransition(
                    originalTransition = transitionState,
                    fromScene = targetCurrentScene,
                    toScene = matchingLink.targetTo,
                )

            stateLink.target.startTransition(linkedTransition, matchingLink.targetTransitionKey)
            activeTransitionLinks[stateLink] = linkedTransition
        }
    }

    /**
     * Notify that [transition] was finished and that we should settle to [idleScene]. This will do
     * nothing if [transition] was interrupted since it was started.
     */
    internal fun finishTransition(transition: TransitionState.Transition, idleScene: SceneKey) {
        resolveActiveTransitionLinks(idleScene)
        if (transitionState == transition) {
            transitionState = TransitionState.Idle(idleScene)
        }
    }

    private fun resolveActiveTransitionLinks(idleScene: SceneKey) {
        val previousTransition = this.transitionState as? TransitionState.Transition ?: return
        for ((link, linkedTransition) in activeTransitionLinks) {
            if (previousTransition.fromScene == idleScene) {
                // The transition ended by arriving at the fromScene, move link to Idle(fromScene).
                link.target.finishTransition(linkedTransition, linkedTransition.fromScene)
            } else if (previousTransition.toScene == idleScene) {
                // The transition ended by arriving at the toScene, move link to Idle(toScene).
                link.target.finishTransition(linkedTransition, linkedTransition.toScene)
            } else {
                // The transition was interrupted by something else, we reset to initial state.
                link.target.finishTransition(linkedTransition, linkedTransition.fromScene)
            }
        }
        activeTransitionLinks.clear()
    }

    /**
     * Check if a transition is in progress. If the progress value is near 0 or 1, immediately snap
     * to the closest scene.
     *
     * @return true if snapped to the closest scene.
     */
    internal fun snapToIdleIfClose(threshold: Float): Boolean {
        val transition = currentTransition ?: return false
        val progress = transition.progress
        fun isProgressCloseTo(value: Float) = (progress - value).absoluteValue <= threshold

        return when {
            isProgressCloseTo(0f) -> {
                finishTransition(transition, transition.fromScene)
                true
            }
            isProgressCloseTo(1f) -> {
                finishTransition(transition, transition.toScene)
                true
            }
            else -> false
        }
    }
}

/**
 * A [SceneTransitionLayout] whose current scene/source of truth is hoisted (its current value comes
 * from outside).
 */
internal class HoistedSceneTransitionLayoutState(
    initialScene: SceneKey,
    override var transitions: SceneTransitions,
    private var changeScene: (SceneKey) -> Unit,
    private var canChangeScene: (SceneKey) -> Boolean,
    stateLinks: List<StateLink> = emptyList(),
) : BaseSceneTransitionLayoutState(initialScene, stateLinks) {
    private val targetSceneChannel = Channel<SceneKey>(Channel.CONFLATED)

    override fun canChangeScene(scene: SceneKey): Boolean = canChangeScene.invoke(scene)

    override fun CoroutineScope.onChangeScene(scene: SceneKey) = changeScene.invoke(scene)

    @Composable
    fun update(
        currentScene: SceneKey,
        onChangeScene: (SceneKey) -> Unit,
        canChangeScene: (SceneKey) -> Boolean,
        transitions: SceneTransitions,
        stateLinks: List<StateLink>,
    ) {
        SideEffect {
            this.changeScene = onChangeScene
            this.canChangeScene = canChangeScene
            this.transitions = transitions
            this.stateLinks = stateLinks

            targetSceneChannel.trySend(currentScene)
        }

        LaunchedEffect(targetSceneChannel) {
            for (newKey in targetSceneChannel) {
                // Inspired by AnimateAsState.kt: let's poll the last value to avoid being one frame
                // late.
                val newKey = targetSceneChannel.tryReceive().getOrNull() ?: newKey
                animateToScene(
                    layoutState = this@HoistedSceneTransitionLayoutState,
                    target = newKey,
                    transitionKey = null,
                )
            }
        }
    }
}

/** A [MutableSceneTransitionLayoutState] that holds the value for the current scene. */
internal class MutableSceneTransitionLayoutStateImpl(
    initialScene: SceneKey,
    override var transitions: SceneTransitions,
    private val canChangeScene: (SceneKey) -> Boolean = { true },
    stateLinks: List<StateLink> = emptyList(),
) : MutableSceneTransitionLayoutState, BaseSceneTransitionLayoutState(initialScene, stateLinks) {
    override fun setTargetScene(
        targetScene: SceneKey,
        coroutineScope: CoroutineScope,
        transitionKey: TransitionKey?,
    ): TransitionState.Transition? {
        return coroutineScope.animateToScene(
            layoutState = this@MutableSceneTransitionLayoutStateImpl,
            target = targetScene,
            transitionKey = transitionKey,
        )
    }

    override fun canChangeScene(scene: SceneKey): Boolean = canChangeScene.invoke(scene)

    override fun CoroutineScope.onChangeScene(scene: SceneKey) {
        setTargetScene(scene, coroutineScope = this)
    }
}
