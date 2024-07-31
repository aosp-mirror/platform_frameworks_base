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

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import com.android.compose.animation.scene.content.state.ContentState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transition.link.LinkedTransition
import com.android.compose.animation.scene.transition.link.StateLink
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope

/**
 * The state of a [SceneTransitionLayout].
 *
 * @see MutableSceneTransitionLayoutState
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

    /**
     * The current transition, or `null` if we are idle.
     *
     * Note: If you need to handle interruptions and multiple transitions running in parallel, use
     * [currentTransitions] instead.
     */
    val currentTransition: TransitionState.Transition?
        get() = transitionState as? TransitionState.Transition

    /**
     * The list of [TransitionState.Transition] currently running. This will be the empty list if we
     * are idle.
     */
    val currentTransitions: List<TransitionState.Transition>

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

    /** Immediately snap to the given [scene]. */
    fun snapToScene(scene: SceneKey)
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
    enableInterruptions: Boolean = DEFAULT_INTERRUPTIONS_ENABLED,
): MutableSceneTransitionLayoutState {
    return MutableSceneTransitionLayoutStateImpl(
        initialScene,
        transitions,
        canChangeScene,
        stateLinks,
        enableInterruptions,
    )
}

/** A [MutableSceneTransitionLayoutState] that holds the value for the current scene. */
internal class MutableSceneTransitionLayoutStateImpl(
    initialScene: SceneKey,
    override var transitions: SceneTransitions = transitions {},
    internal val canChangeScene: (SceneKey) -> Boolean = { true },
    private val stateLinks: List<StateLink> = emptyList(),

    // TODO(b/290930950): Remove this flag.
    internal val enableInterruptions: Boolean = DEFAULT_INTERRUPTIONS_ENABLED,
) : MutableSceneTransitionLayoutState {
    private val creationThread: Thread = Thread.currentThread()

    /**
     * The current [TransitionState]. This list will either be:
     * 1. A list with a single [TransitionState.Idle] element, when we are idle.
     * 2. A list with one or more [TransitionState.Transition], when we are transitioning.
     */
    @VisibleForTesting
    internal var transitionStates: List<TransitionState> by
        mutableStateOf(listOf(TransitionState.Idle(initialScene)))
        private set

    override val transitionState: TransitionState
        get() = transitionStates.last()

    private val activeTransitionLinks = mutableMapOf<StateLink, LinkedTransition>()

    override val currentTransitions: List<TransitionState.Transition>
        get() {
            if (transitionStates.last() is TransitionState.Idle) {
                check(transitionStates.size == 1)
                return emptyList()
            } else {
                @Suppress("UNCHECKED_CAST")
                return transitionStates as List<TransitionState.Transition>
            }
        }

    /**
     * The mapping of transitions that are finished, i.e. for which [finishTransition] was called,
     * to their idle scene.
     */
    @VisibleForTesting
    internal val finishedTransitions = mutableMapOf<TransitionState.Transition, SceneKey>()

    internal fun checkThread() {
        val current = Thread.currentThread()
        if (current !== creationThread) {
            error(
                """
                    Only the original thread that created a SceneTransitionLayoutState can mutate it
                      Expected: ${creationThread.name}
                      Current: ${current.name}
                """
                    .trimIndent()
            )
        }
    }

    override fun isTransitioning(from: SceneKey?, to: SceneKey?): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioning(from, to)
    }

    override fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioningBetween(scene, other)
    }

    override fun setTargetScene(
        targetScene: SceneKey,
        coroutineScope: CoroutineScope,
        transitionKey: TransitionKey?,
    ): TransitionState.Transition? {
        checkThread()

        return coroutineScope.animateToScene(
            layoutState = this@MutableSceneTransitionLayoutStateImpl,
            target = targetScene,
            transitionKey = transitionKey,
        )
    }

    /**
     * Start a new [transition].
     *
     * If [chain] is `true`, then the transitions will simply be added to [currentTransitions] and
     * will run in parallel to the current transitions. If [chain] is `false`, then the list of
     * [currentTransitions] will be cleared and [transition] will be the only running transition.
     *
     * Important: you *must* call [finishTransition] once the transition is finished.
     */
    internal fun startTransition(transition: TransitionState.Transition, chain: Boolean = true) {
        checkThread()

        // Compute the [TransformationSpec] when the transition starts.
        val fromScene = transition.fromScene
        val toScene = transition.toScene
        val orientation = (transition as? ContentState.HasOverscrollProperties)?.orientation

        // Update the transition specs.
        transition.transformationSpec =
            transitions
                .transitionSpec(fromScene, toScene, key = transition.key)
                .transformationSpec()
        transition.previewTransformationSpec =
            transitions
                .transitionSpec(fromScene, toScene, key = transition.key)
                .previewTransformationSpec()
        if (orientation != null) {
            transition.updateOverscrollSpecs(
                fromSpec = transitions.overscrollSpec(fromScene, orientation),
                toSpec = transitions.overscrollSpec(toScene, orientation),
            )
        } else {
            transition.updateOverscrollSpecs(fromSpec = null, toSpec = null)
        }

        // Handle transition links.
        cancelActiveTransitionLinks()
        setupTransitionLinks(transition)

        if (!enableInterruptions) {
            // Set the current transition.
            check(transitionStates.size == 1)
            transitionStates = listOf(transition)
            return
        }

        when (val currentState = transitionStates.last()) {
            is TransitionState.Idle -> {
                // Replace [Idle] by [transition].
                check(transitionStates.size == 1)
                transitionStates = listOf(transition)
            }
            is TransitionState.Transition -> {
                // Force the current transition to finish to currentScene. The transition will call
                // [finishTransition] once it's finished.
                currentState.finish()

                val tooManyTransitions = transitionStates.size >= MAX_CONCURRENT_TRANSITIONS
                val clearCurrentTransitions = !chain || tooManyTransitions
                if (clearCurrentTransitions) {
                    if (tooManyTransitions) logTooManyTransitions()

                    // Force finish all transitions.
                    while (currentTransitions.isNotEmpty()) {
                        val transition = transitionStates[0] as TransitionState.Transition
                        finishTransition(transition, transition.currentScene)
                    }

                    // We finished all transitions, so we are now idle. We remove this state so that
                    // we end up only with the new transition after appending it.
                    check(transitionStates.size == 1)
                    check(transitionStates[0] is TransitionState.Idle)
                    transitionStates = listOf(transition)
                } else if (currentState == transition.replacedTransition) {
                    // Replace the transition.
                    transitionStates =
                        transitionStates.subList(0, transitionStates.lastIndex) + transition
                } else {
                    // Append the new transition.
                    transitionStates = transitionStates + transition
                }
            }
        }
    }

    private fun logTooManyTransitions() {
        Log.wtf(
            TAG,
            buildString {
                appendLine("Potential leak detected in SceneTransitionLayoutState!")
                appendLine("  Some transition(s) never called STLState.finishTransition().")
                appendLine("  Transitions (size=${transitionStates.size}):")
                transitionStates.fastForEach { state ->
                    val transition = state as TransitionState.Transition
                    val from = transition.fromScene
                    val to = transition.toScene
                    val indicator = if (finishedTransitions.contains(transition)) "x" else " "
                    appendLine("  [$indicator] $from => $to ($transition)")
                }
            }
        )
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
                    key = matchingLink.targetTransitionKey,
                )

            stateLink.target.startTransition(linkedTransition)
            activeTransitionLinks[stateLink] = linkedTransition
        }
    }

    /**
     * Notify that [transition] was finished and that we should settle to [idleScene]. This will do
     * nothing if [transition] was interrupted since it was started.
     */
    internal fun finishTransition(transition: TransitionState.Transition, idleScene: SceneKey) {
        checkThread()

        val existingIdleScene = finishedTransitions[transition]
        if (existingIdleScene != null) {
            // This transition was already finished.
            check(idleScene == existingIdleScene) {
                "Transition $transition was finished multiple times with different " +
                    "idleScene ($existingIdleScene != $idleScene)"
            }
            return
        }

        val transitionStates = this.transitionStates
        if (!transitionStates.contains(transition)) {
            // This transition was already removed from transitionStates.
            return
        }

        check(transitionStates.fastAll { it is TransitionState.Transition })

        // Mark this transition as finished and save the scene it is settling at.
        finishedTransitions[transition] = idleScene

        // Finish all linked transitions.
        finishActiveTransitionLinks(idleScene)

        // Keep a reference to the idle scene of the last removed transition, in case we remove all
        // transitions and should settle to Idle.
        var lastRemovedIdleScene: SceneKey? = null

        // Remove all first n finished transitions.
        var i = 0
        val nStates = transitionStates.size
        while (i < nStates) {
            val t = transitionStates[i]
            if (!finishedTransitions.contains(t)) {
                // Stop here.
                break
            }

            // Remove the transition from the set of finished transitions.
            lastRemovedIdleScene = finishedTransitions.remove(t)
            i++
        }

        // If all transitions are finished, we are idle.
        if (i == nStates) {
            check(finishedTransitions.isEmpty())
            this.transitionStates = listOf(TransitionState.Idle(checkNotNull(lastRemovedIdleScene)))
        } else if (i > 0) {
            this.transitionStates = transitionStates.subList(fromIndex = i, toIndex = nStates)
        }
    }

    override fun snapToScene(scene: SceneKey) {
        checkThread()

        // Force finish all transitions.
        while (currentTransitions.isNotEmpty()) {
            val transition = transitionStates[0] as TransitionState.Transition
            finishTransition(transition, transition.currentScene)
        }

        check(transitionStates.size == 1)
        transitionStates = listOf(TransitionState.Idle(scene))
    }

    private fun finishActiveTransitionLinks(idleScene: SceneKey) {
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
     * Important: Snapping to the closest scene will instantly finish *all* ongoing transitions,
     * only the progress of the last transition will be checked.
     *
     * @return true if snapped to the closest scene.
     */
    internal fun snapToIdleIfClose(threshold: Float): Boolean {
        val transition = currentTransition ?: return false
        val progress = transition.progress

        fun isProgressCloseTo(value: Float) = (progress - value).absoluteValue <= threshold

        fun finishAllTransitions(lastTransitionIdleScene: SceneKey) {
            // Force finish all transitions.
            while (currentTransitions.isNotEmpty()) {
                val transition = transitionStates[0] as TransitionState.Transition
                val idleScene =
                    if (transitionStates.size == 1) {
                        lastTransitionIdleScene
                    } else {
                        transition.currentScene
                    }

                finishTransition(transition, idleScene)
            }
        }

        return when {
            isProgressCloseTo(0f) -> {
                finishAllTransitions(transition.fromScene)
                true
            }
            isProgressCloseTo(1f) -> {
                finishAllTransitions(transition.toScene)
                true
            }
            else -> false
        }
    }
}

private const val TAG = "SceneTransitionLayoutState"

/** Whether support for interruptions in enabled by default. */
internal const val DEFAULT_INTERRUPTIONS_ENABLED = true

/**
 * The max number of concurrent transitions. If the number of transitions goes past this number,
 * this probably means that there is a leak and we will Log.wtf before clearing the list of
 * transitions.
 */
private const val MAX_CONCURRENT_TRANSITIONS = 100
