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
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transition.link.LinkedTransition
import com.android.compose.animation.scene.transition.link.StateLink
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The state of a [SceneTransitionLayout].
 *
 * @see MutableSceneTransitionLayoutState
 */
@Stable
sealed interface SceneTransitionLayoutState {
    /**
     * The current effective scene. If a new transition is triggered, it will start from this scene.
     */
    val currentScene: SceneKey

    /**
     * The current set of overlays. This represents the set of overlays that will be visible on
     * screen once all [currentTransitions] are finished.
     *
     * @see MutableSceneTransitionLayoutState.showOverlay
     * @see MutableSceneTransitionLayoutState.hideOverlay
     * @see MutableSceneTransitionLayoutState.replaceOverlay
     */
    val currentOverlays: Set<OverlayKey>

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
     * the contents we are animating from and/or to.
     */
    fun isTransitioning(from: ContentKey? = null, to: ContentKey? = null): Boolean

    /** Whether we are transitioning from [content] to [other], or from [other] to [content]. */
    fun isTransitioningBetween(content: ContentKey, other: ContentKey): Boolean

    /** Whether we are transitioning from or to [content]. */
    fun isTransitioningFromOrTo(content: ContentKey): Boolean
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
     * [transitionState] of this [MutableSceneTransitionLayoutState]. The [Job] in which the
     * transition runs will be returned, allowing you to easily [join][Job.join] or
     * [cancel][Job.cancel] the animation.
     *
     * Note that because a non-null [TransitionState.Transition] is returned does not mean that the
     * transition will finish and that we will settle to [targetScene]. The returned transition
     * might still be interrupted, for instance by another call to [setTargetScene] or by a user
     * gesture.
     *
     * If [animationScope] is cancelled during the transition and that the transition was still
     * active, then the [transitionState] of this [MutableSceneTransitionLayoutState] will be set to
     * `TransitionState.Idle(targetScene)`.
     */
    fun setTargetScene(
        targetScene: SceneKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey? = null,
    ): Pair<TransitionState.Transition, Job>?

    /** Immediately snap to the given [scene]. */
    fun snapToScene(
        scene: SceneKey,
        currentOverlays: Set<OverlayKey> = transitionState.currentOverlays,
    )

    /**
     * Request to show [overlay] so that it animates in from [currentScene] and ends up being
     * visible on screen.
     *
     * After this returns, this overlay will be included in [currentOverlays]. This does nothing if
     * [overlay] is already in [currentOverlays].
     */
    fun showOverlay(
        overlay: OverlayKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Request to hide [overlay] so that it animates out to [currentScene] and ends up *not* being
     * visible on screen.
     *
     * After this returns, this overlay will not be included in [currentOverlays]. This does nothing
     * if [overlay] is not in [currentOverlays].
     */
    fun hideOverlay(
        overlay: OverlayKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Replace [from] by [to] so that [from] ends up not being visible on screen and [to] ends up
     * being visible.
     *
     * This throws if [from] is not currently in [currentOverlays] or if [to] is already in
     * [currentOverlays].
     */
    fun replaceOverlay(
        from: OverlayKey,
        to: OverlayKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Instantly start a [transition], running it in [animationScope].
     *
     * This call returns immediately and [transition] will be the [currentTransition] of this
     * [MutableSceneTransitionLayoutState].
     *
     * @see startTransition
     */
    fun startTransitionImmediately(
        animationScope: CoroutineScope,
        transition: TransitionState.Transition,
        chain: Boolean = true,
    ): Job

    /**
     * Start a new [transition].
     *
     * If [chain] is `true`, then the transitions will simply be added to [currentTransitions] and
     * will run in parallel to the current transitions. If [chain] is `false`, then the list of
     * [currentTransitions] will be cleared and [transition] will be the only running transition.
     *
     * If any transition is currently ongoing, it will be interrupted and forced to animate to its
     * current state by calling [TransitionState.Transition.freezeAndAnimateToCurrentState].
     *
     * This method returns when [transition] is done running, i.e. when the call to
     * [run][TransitionState.Transition.run] returns.
     */
    suspend fun startTransition(transition: TransitionState.Transition, chain: Boolean = true)
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
 * @param canShowOverlay whether we should commit a user action that will result in showing the
 *   given overlay.
 * @param canHideOverlay whether we should commit a user action that will result in hiding the given
 *   overlay.
 * @param canReplaceOverlay whether we should commit a user action that will result in replacing
 *   `from` overlay by `to` overlay.
 * @param stateLinks the [StateLink] connecting this [SceneTransitionLayoutState] to other
 *   [SceneTransitionLayoutState]s.
 */
fun MutableSceneTransitionLayoutState(
    initialScene: SceneKey,
    transitions: SceneTransitions = SceneTransitions.Empty,
    initialOverlays: Set<OverlayKey> = emptySet(),
    canChangeScene: (SceneKey) -> Boolean = { true },
    canShowOverlay: (OverlayKey) -> Boolean = { true },
    canHideOverlay: (OverlayKey) -> Boolean = { true },
    canReplaceOverlay: (from: OverlayKey, to: OverlayKey) -> Boolean = { _, _ -> true },
    stateLinks: List<StateLink> = emptyList(),
    enableInterruptions: Boolean = DEFAULT_INTERRUPTIONS_ENABLED,
): MutableSceneTransitionLayoutState {
    return MutableSceneTransitionLayoutStateImpl(
        initialScene,
        transitions,
        initialOverlays,
        canChangeScene,
        canShowOverlay,
        canHideOverlay,
        canReplaceOverlay,
        stateLinks,
        enableInterruptions,
    )
}

/** A [MutableSceneTransitionLayoutState] that holds the value for the current scene. */
internal class MutableSceneTransitionLayoutStateImpl(
    initialScene: SceneKey,
    override var transitions: SceneTransitions = transitions {},
    initialOverlays: Set<OverlayKey> = emptySet(),
    internal val canChangeScene: (SceneKey) -> Boolean = { true },
    internal val canShowOverlay: (OverlayKey) -> Boolean = { true },
    internal val canHideOverlay: (OverlayKey) -> Boolean = { true },
    internal val canReplaceOverlay: (from: OverlayKey, to: OverlayKey) -> Boolean = { _, _ ->
        true
    },
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
    internal var transitionStates: List<TransitionState> by
        mutableStateOf(listOf(TransitionState.Idle(initialScene, initialOverlays)))
        private set

    override val currentScene: SceneKey
        get() = transitionState.currentScene

    override val currentOverlays: Set<OverlayKey>
        get() = transitionState.currentOverlays

    override val transitionState: TransitionState
        get() = transitionStates[transitionStates.lastIndex]

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

    /** The transitions that are finished, i.e. for which [finishTransition] was called. */
    @VisibleForTesting internal val finishedTransitions = mutableSetOf<TransitionState.Transition>()

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

    override fun isTransitioning(from: ContentKey?, to: ContentKey?): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioning(from, to)
    }

    override fun isTransitioningBetween(content: ContentKey, other: ContentKey): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioningBetween(content, other)
    }

    override fun isTransitioningFromOrTo(content: ContentKey): Boolean {
        val transition = currentTransition ?: return false
        return transition.isTransitioningFromOrTo(content)
    }

    override fun setTargetScene(
        targetScene: SceneKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey?,
    ): Pair<TransitionState.Transition.ChangeScene, Job>? {
        checkThread()

        return animationScope.animateToScene(
            layoutState = this@MutableSceneTransitionLayoutStateImpl,
            target = targetScene,
            transitionKey = transitionKey,
        )
    }

    override fun startTransitionImmediately(
        animationScope: CoroutineScope,
        transition: TransitionState.Transition,
        chain: Boolean,
    ): Job {
        // Note that we start with UNDISPATCHED so that startTransition() is called directly and
        // transition becomes the current [transitionState] right after this call.
        return animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            startTransition(transition, chain)
        }
    }

    override suspend fun startTransition(transition: TransitionState.Transition, chain: Boolean) {
        checkThread()

        try {
            // Keep a reference to the previous transition (if any).
            val previousTransition = currentTransition

            // Start the transition.
            startTransitionInternal(transition, chain)

            // Handle transition links.
            previousTransition?.let { cancelActiveTransitionLinks(it) }
            if (stateLinks.isNotEmpty()) {
                coroutineScope { setupTransitionLinks(transition) }
            }

            // Run the transition until it is finished.
            transition.run()
        } finally {
            finishTransition(transition)
        }
    }

    private fun startTransitionInternal(transition: TransitionState.Transition, chain: Boolean) {
        // Set the current scene and overlays on the transition.
        val currentState = transitionState
        transition.currentSceneWhenTransitionStarted = currentState.currentScene
        transition.currentOverlaysWhenTransitionStarted = currentState.currentOverlays

        // Compute the [TransformationSpec] when the transition starts.
        val fromContent = transition.fromContent
        val toContent = transition.toContent
        val orientation = (transition as? TransitionState.HasOverscrollProperties)?.orientation

        // Update the transition specs.
        transition.transformationSpec =
            transitions
                .transitionSpec(fromContent, toContent, key = transition.key)
                .transformationSpec()
        transition.previewTransformationSpec =
            transitions
                .transitionSpec(fromContent, toContent, key = transition.key)
                .previewTransformationSpec()
        if (orientation != null) {
            transition.updateOverscrollSpecs(
                fromSpec = transitions.overscrollSpec(fromContent, orientation),
                toSpec = transitions.overscrollSpec(toContent, orientation),
            )
        } else {
            transition.updateOverscrollSpecs(fromSpec = null, toSpec = null)
        }

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
                // Force the current transition to finish to currentScene.
                currentState.freezeAndAnimateToCurrentState()

                val tooManyTransitions = transitionStates.size >= MAX_CONCURRENT_TRANSITIONS
                val clearCurrentTransitions = !chain || tooManyTransitions
                if (clearCurrentTransitions) {
                    if (tooManyTransitions) logTooManyTransitions()

                    // Force finish all transitions.
                    while (currentTransitions.isNotEmpty()) {
                        finishTransition(transitionStates[0] as TransitionState.Transition)
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
                    val from = transition.fromContent
                    val to = transition.toContent
                    val indicator = if (finishedTransitions.contains(transition)) "x" else " "
                    appendLine("  [$indicator] $from => $to ($transition)")
                }
            },
        )
    }

    private fun cancelActiveTransitionLinks(transition: TransitionState.Transition) {
        transition.activeTransitionLinks.forEach { (link, linkedTransition) ->
            link.target.finishTransition(linkedTransition)
        }
        transition.activeTransitionLinks.clear()
    }

    private fun CoroutineScope.setupTransitionLinks(transition: TransitionState.Transition) {
        stateLinks.fastForEach { stateLink ->
            val matchingLinks =
                stateLink.transitionLinks.fastFilter { it.isMatchingLink(transition) }
            if (matchingLinks.isEmpty()) return@fastForEach
            if (matchingLinks.size > 1) error("More than one link matched.")

            val targetCurrentScene = stateLink.target.transitionState.currentScene
            val matchingLink = matchingLinks[0]

            if (!matchingLink.targetIsInValidState(targetCurrentScene)) return@fastForEach

            val linkedTransition =
                LinkedTransition(
                    originalTransition = transition,
                    fromScene = targetCurrentScene,
                    toScene = matchingLink.targetTo,
                    key = matchingLink.targetTransitionKey,
                )

            // Start with UNDISPATCHED so that startTransition is called directly and the new linked
            // transition is observable directly.
            launch(start = CoroutineStart.UNDISPATCHED) {
                stateLink.target.startTransition(linkedTransition)
            }
            transition.activeTransitionLinks[stateLink] = linkedTransition
        }
    }

    /**
     * Notify that [transition] was finished and that it settled to its
     * [currentScene][TransitionState.currentScene]. This will do nothing if [transition] was
     * interrupted since it was started.
     */
    private fun finishTransition(transition: TransitionState.Transition) {
        checkThread()

        if (finishedTransitions.contains(transition)) {
            // This transition was already finished.
            return
        }

        // Make sure that this transition settles in case it was force finished, for instance by
        // calling snapToScene().
        transition.freezeAndAnimateToCurrentState()

        val transitionStates = this.transitionStates
        if (!transitionStates.contains(transition)) {
            // This transition was already removed from transitionStates.
            return
        }

        check(transitionStates.fastAll { it is TransitionState.Transition })

        // Mark this transition as finished.
        finishedTransitions.add(transition)

        // Finish all linked transitions.
        finishActiveTransitionLinks(transition)

        // Keep a reference to the last transition, in case we remove all transitions and should
        // settle to Idle.
        val lastTransition = transitionStates.last()

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
            finishedTransitions.remove(t)
            i++
        }

        // If all transitions are finished, we are idle.
        if (i == nStates) {
            check(finishedTransitions.isEmpty())
            this.transitionStates =
                listOf(
                    TransitionState.Idle(
                        lastTransition.currentScene,
                        lastTransition.currentOverlays,
                    )
                )
        } else if (i > 0) {
            this.transitionStates = transitionStates.subList(fromIndex = i, toIndex = nStates)
        }
    }

    override fun snapToScene(scene: SceneKey, currentOverlays: Set<OverlayKey>) {
        checkThread()

        // Force finish all transitions.
        while (currentTransitions.isNotEmpty()) {
            finishTransition(transitionStates[0] as TransitionState.Transition)
        }

        check(transitionStates.size == 1)
        transitionStates = listOf(TransitionState.Idle(scene, currentOverlays))
    }

    private fun finishActiveTransitionLinks(transition: TransitionState.Transition) {
        for ((link, linkedTransition) in transition.activeTransitionLinks) {
            link.target.finishTransition(linkedTransition)
        }
        transition.activeTransitionLinks.clear()
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

        fun finishAllTransitions() {
            // Force finish all transitions.
            while (currentTransitions.isNotEmpty()) {
                finishTransition(transitionStates[0] as TransitionState.Transition)
            }
        }

        val shouldSnap =
            (isProgressCloseTo(0f) && transition.currentScene == transition.fromContent) ||
                (isProgressCloseTo(1f) && transition.currentScene == transition.toContent)
        return if (shouldSnap) {
            finishAllTransitions()
            true
        } else {
            false
        }
    }

    override fun showOverlay(
        overlay: OverlayKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey?,
    ) {
        checkThread()

        // Overlay is already shown, do nothing.
        val currentState = transitionState
        if (overlay in currentState.currentOverlays) {
            return
        }

        val fromScene = currentState.currentScene
        fun animate(
            replacedTransition: TransitionState.Transition.ShowOrHideOverlay? = null,
            reversed: Boolean = false,
        ) {
            animationScope.showOrHideOverlay(
                layoutState = this@MutableSceneTransitionLayoutStateImpl,
                overlay = overlay,
                fromOrToScene = fromScene,
                isShowing = true,
                transitionKey = transitionKey,
                replacedTransition = replacedTransition,
                reversed = reversed,
            )
        }

        if (
            currentState is TransitionState.Transition.ShowOrHideOverlay &&
                currentState.overlay == overlay &&
                currentState.fromOrToScene == fromScene
        ) {
            animate(
                replacedTransition = currentState,
                reversed = overlay == currentState.fromContent,
            )
        } else {
            animate()
        }
    }

    override fun hideOverlay(
        overlay: OverlayKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey?,
    ) {
        checkThread()

        // Overlay is not shown, do nothing.
        val currentState = transitionState
        if (!currentState.currentOverlays.contains(overlay)) {
            return
        }

        val toScene = currentState.currentScene
        fun animate(
            replacedTransition: TransitionState.Transition.ShowOrHideOverlay? = null,
            reversed: Boolean = false,
        ) {
            animationScope.showOrHideOverlay(
                layoutState = this@MutableSceneTransitionLayoutStateImpl,
                overlay = overlay,
                fromOrToScene = toScene,
                isShowing = false,
                transitionKey = transitionKey,
                replacedTransition = replacedTransition,
                reversed = reversed,
            )
        }

        if (
            currentState is TransitionState.Transition.ShowOrHideOverlay &&
                currentState.overlay == overlay &&
                currentState.fromOrToScene == toScene
        ) {
            animate(replacedTransition = currentState, reversed = overlay == currentState.toContent)
        } else {
            animate()
        }
    }

    override fun replaceOverlay(
        from: OverlayKey,
        to: OverlayKey,
        animationScope: CoroutineScope,
        transitionKey: TransitionKey?,
    ) {
        checkThread()

        val currentState = transitionState
        require(from != to) {
            "replaceOverlay must be called with different overlays (from = to = ${from.debugName})"
        }
        require(from in currentState.currentOverlays) {
            "Overlay ${from.debugName} is not shown so it can't be replaced by ${to.debugName}"
        }
        require(to !in currentState.currentOverlays) {
            "Overlay ${to.debugName} is already shown so it can't replace ${from.debugName}"
        }

        fun animate(
            replacedTransition: TransitionState.Transition.ReplaceOverlay? = null,
            reversed: Boolean = false,
        ) {
            animationScope.replaceOverlay(
                layoutState = this@MutableSceneTransitionLayoutStateImpl,
                fromOverlay = if (reversed) to else from,
                toOverlay = if (reversed) from else to,
                transitionKey = transitionKey,
                replacedTransition = replacedTransition,
                reversed = reversed,
            )
        }

        if (currentState is TransitionState.Transition.ReplaceOverlay) {
            if (currentState.fromOverlay == from && currentState.toOverlay == to) {
                animate(replacedTransition = currentState, reversed = false)
                return
            }

            if (currentState.fromOverlay == to && currentState.toOverlay == from) {
                animate(replacedTransition = currentState, reversed = true)
                return
            }
        }

        animate()
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
