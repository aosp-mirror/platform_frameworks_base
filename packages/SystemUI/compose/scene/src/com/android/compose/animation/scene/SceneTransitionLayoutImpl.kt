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

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachReversed
import androidx.compose.ui.zIndex
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.Overlay
import com.android.compose.animation.scene.content.Scene
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope

/** The type for the content of movable elements. */
internal typealias MovableElementContent = @Composable (@Composable () -> Unit) -> Unit

@Stable
internal class SceneTransitionLayoutImpl(
    internal val state: MutableSceneTransitionLayoutStateImpl,
    internal var density: Density,
    internal var layoutDirection: LayoutDirection,
    internal var swipeSourceDetector: SwipeSourceDetector,
    internal var transitionInterceptionThreshold: Float,
    builder: SceneTransitionLayoutScope.() -> Unit,

    /**
     * The scope that should be used by *animations started by this layout only*, i.e. animations
     * triggered by gestures set up on this layout in [swipeToScene] or interruption decay
     * animations.
     */
    internal val animationScope: CoroutineScope,
) {
    /**
     * The map of [Scene]s.
     *
     * TODO(b/317014852): Make this a normal MutableMap instead.
     */
    private val scenes = SnapshotStateMap<SceneKey, Scene>()

    /**
     * The map of [Overlays].
     *
     * Note: We lazily create this map to avoid instantiation an expensive SnapshotStateMap in the
     * common case where there is no overlay in this layout.
     */
    private var _overlays: MutableMap<OverlayKey, Overlay>? = null
    private val overlays
        get() = _overlays ?: SnapshotStateMap<OverlayKey, Overlay>().also { _overlays = it }

    /**
     * The map of [Element]s.
     *
     * Important: [Element]s from this map should never be accessed during composition because the
     * Elements are added when the associated Modifier.element() node is attached to the Modifier
     * tree, i.e. after composition.
     */
    internal val elements = mutableMapOf<ElementKey, Element>()

    /**
     * The map of contents of movable elements.
     *
     * Note that given that this map is mutated directly during a composition, it has to be a
     * [SnapshotStateMap] to make sure that mutations are reverted if composition is cancelled.
     */
    private var _movableContents: SnapshotStateMap<ElementKey, MovableElementContent>? = null
    val movableContents: SnapshotStateMap<ElementKey, MovableElementContent>
        get() =
            _movableContents
                ?: SnapshotStateMap<ElementKey, MovableElementContent>().also {
                    _movableContents = it
                }

    /**
     * The different values of a shared value keyed by a a [ValueKey] and the different elements and
     * contents it is associated to.
     */
    private var _sharedValues: MutableMap<ValueKey, MutableMap<ElementKey?, SharedValue<*, *>>>? =
        null
    internal val sharedValues: MutableMap<ValueKey, MutableMap<ElementKey?, SharedValue<*, *>>>
        get() =
            _sharedValues
                ?: mutableMapOf<ValueKey, MutableMap<ElementKey?, SharedValue<*, *>>>().also {
                    _sharedValues = it
                }

    // TODO(b/317958526): Lazily allocate scene gesture handlers the first time they are needed.
    private val horizontalDraggableHandler: DraggableHandlerImpl
    private val verticalDraggableHandler: DraggableHandlerImpl

    internal val elementStateScope = ElementStateScopeImpl(this)
    private var _userActionDistanceScope: UserActionDistanceScope? = null
    internal val userActionDistanceScope: UserActionDistanceScope
        get() =
            _userActionDistanceScope
                ?: UserActionDistanceScopeImpl(layoutImpl = this).also {
                    _userActionDistanceScope = it
                }

    /**
     * The [LookaheadScope] of this layout, that can be used to compute offsets relative to the
     * layout.
     */
    internal lateinit var lookaheadScope: LookaheadScope
        private set

    internal var lastSize: IntSize = IntSize.Zero

    init {
        updateContents(builder, layoutDirection)

        // DraggableHandlerImpl must wait for the scenes to be initialized, in order to access the
        // current scene (required for SwipeTransition).
        horizontalDraggableHandler =
            DraggableHandlerImpl(layoutImpl = this, orientation = Orientation.Horizontal)

        verticalDraggableHandler =
            DraggableHandlerImpl(layoutImpl = this, orientation = Orientation.Vertical)

        // Make sure that the state is created on the same thread (most probably the main thread)
        // than this STLImpl.
        state.checkThread()
    }

    internal fun draggableHandler(orientation: Orientation): DraggableHandlerImpl =
        when (orientation) {
            Orientation.Vertical -> verticalDraggableHandler
            Orientation.Horizontal -> horizontalDraggableHandler
        }

    internal fun scene(key: SceneKey): Scene {
        return scenes[key] ?: error("Scene $key is not configured")
    }

    internal fun sceneOrNull(key: SceneKey): Scene? = scenes[key]

    internal fun overlay(key: OverlayKey): Overlay {
        return overlays[key] ?: error("Overlay $key is not configured")
    }

    internal fun content(key: ContentKey): Content {
        return when (key) {
            is SceneKey -> scene(key)
            is OverlayKey -> overlay(key)
        }
    }

    internal fun contentForUserActions(): Content {
        return findOverlayWithHighestZIndex() ?: scene(state.transitionState.currentScene)
    }

    private fun findOverlayWithHighestZIndex(): Overlay? {
        val currentOverlays = state.transitionState.currentOverlays
        if (currentOverlays.isEmpty()) {
            return null
        }

        var overlay: Overlay? = null
        currentOverlays.forEach { key ->
            val previousZIndex = overlay?.zIndex
            val candidate = overlay(key)
            if (previousZIndex == null || candidate.zIndex > previousZIndex) {
                overlay = candidate
            }
        }

        return overlay
    }

    internal fun updateContents(
        builder: SceneTransitionLayoutScope.() -> Unit,
        layoutDirection: LayoutDirection,
    ) {
        // Keep a reference of the current contents. After processing [builder], the contents that
        // were not configured will be removed.
        val scenesToRemove = scenes.keys.toMutableSet()
        val overlaysToRemove =
            if (_overlays == null) mutableSetOf() else overlays.keys.toMutableSet()

        // The incrementing zIndex of each scene.
        var zIndex = 0f
        var overlaysDefined = false

        object : SceneTransitionLayoutScope {
                override fun scene(
                    key: SceneKey,
                    userActions: Map<UserAction, UserActionResult>,
                    content: @Composable ContentScope.() -> Unit,
                ) {
                    require(!overlaysDefined) { "all scenes must be defined before overlays" }

                    scenesToRemove.remove(key)

                    val resolvedUserActions = resolveUserActions(key, userActions, layoutDirection)
                    val scene = scenes[key]
                    if (scene != null) {
                        // Update an existing scene.
                        scene.content = content
                        scene.userActions = resolvedUserActions
                        scene.zIndex = zIndex
                    } else {
                        // New scene.
                        scenes[key] =
                            Scene(
                                key,
                                this@SceneTransitionLayoutImpl,
                                content,
                                resolvedUserActions,
                                zIndex,
                            )
                    }

                    zIndex++
                }

                override fun overlay(
                    key: OverlayKey,
                    userActions: Map<UserAction, UserActionResult>,
                    alignment: Alignment,
                    isModal: Boolean,
                    content: @Composable (ContentScope.() -> Unit),
                ) {
                    overlaysDefined = true
                    overlaysToRemove.remove(key)

                    val overlay = overlays[key]
                    val resolvedUserActions = resolveUserActions(key, userActions, layoutDirection)
                    if (overlay != null) {
                        // Update an existing overlay.
                        overlay.content = content
                        overlay.zIndex = zIndex
                        overlay.userActions = resolvedUserActions
                        overlay.alignment = alignment
                        overlay.isModal = isModal
                    } else {
                        // New overlay.
                        overlays[key] =
                            Overlay(
                                key,
                                this@SceneTransitionLayoutImpl,
                                content,
                                resolvedUserActions,
                                zIndex,
                                alignment,
                                isModal,
                            )
                    }

                    zIndex++
                }
            }
            .builder()

        scenesToRemove.forEach { scenes.remove(it) }
        overlaysToRemove.forEach { overlays.remove(it) }
    }

    private fun resolveUserActions(
        key: ContentKey,
        userActions: Map<UserAction, UserActionResult>,
        layoutDirection: LayoutDirection,
    ): Map<UserAction.Resolved, UserActionResult> {
        return userActions
            .mapKeys { it.key.resolve(layoutDirection) }
            .also { checkUserActions(key, it) }
    }

    private fun checkUserActions(
        key: ContentKey,
        userActions: Map<UserAction.Resolved, UserActionResult>,
    ) {
        userActions.forEach { (action, result) ->
            fun details() = "Content $key, action $action, result $result."

            when (result) {
                is UserActionResult.ChangeScene -> {
                    check(key != result.toScene) {
                        error("Transition to the same scene is not supported. ${details()}")
                    }
                }
                is UserActionResult.ReplaceByOverlay -> {
                    check(key is OverlayKey) {
                        "ReplaceByOverlay() can only be used for overlays, not scenes. ${details()}"
                    }

                    check(key != result.overlay) {
                        "Transition to the same overlay is not supported. ${details()}"
                    }
                }
                is UserActionResult.ShowOverlay,
                is UserActionResult.HideOverlay -> {
                    /* Always valid. */
                }
            }
        }
    }

    @Composable
    internal fun Content(modifier: Modifier, swipeDetector: SwipeDetector) {
        Box(
            modifier
                // Handle horizontal and vertical swipes on this layout.
                // Note: order here is important and will give a slight priority to the vertical
                // swipes.
                .swipeToScene(horizontalDraggableHandler, swipeDetector)
                .swipeToScene(verticalDraggableHandler, swipeDetector)
                .then(LayoutElement(layoutImpl = this))
        ) {
            LookaheadScope {
                lookaheadScope = this

                BackHandler()
                Scenes()
                Overlays()
            }
        }
    }

    @Composable
    private fun BackHandler() {
        val result = contentForUserActions().userActions[Back.Resolved]
        PredictiveBackHandler(layoutImpl = this, result = result)
    }

    @Composable
    private fun Scenes() {
        scenesToCompose().fastForEach { scene -> key(scene.key) { scene.Content() } }
    }

    private fun scenesToCompose(): List<Scene> {
        val transitions = state.currentTransitions
        return if (transitions.isEmpty()) {
            listOf(scene(state.transitionState.currentScene))
        } else {
            buildList {
                val visited = mutableSetOf<SceneKey>()
                fun maybeAdd(sceneKey: SceneKey) {
                    if (visited.add(sceneKey)) {
                        add(scene(sceneKey))
                    }
                }

                // Compose the new scene we are going to first.
                transitions.fastForEachReversed { transition ->
                    when (transition) {
                        is TransitionState.Transition.ChangeScene -> {
                            maybeAdd(transition.toScene)
                            maybeAdd(transition.fromScene)
                        }
                        is TransitionState.Transition.ShowOrHideOverlay ->
                            maybeAdd(transition.fromOrToScene)
                        is TransitionState.Transition.ReplaceOverlay -> {}
                    }
                }

                // Make sure that the current scene is always composed.
                maybeAdd(transitions.last().currentScene)
            }
        }
    }

    @Composable
    private fun BoxScope.Overlays() {
        val overlaysOrderedByZIndex = overlaysToComposeOrderedByZIndex()
        if (overlaysOrderedByZIndex.isEmpty()) {
            return
        }

        overlaysOrderedByZIndex.fastForEach { overlay ->
            val key = overlay.key
            key(key) {
                // We put the overlays inside a Box that is matching the layout size so that they
                // are measured after all scenes and that their max size is the size of the layout
                // without the overlays.
                Box(Modifier.matchParentSize().zIndex(overlay.zIndex)) {
                    if (overlay.isModal) {
                        // Add a fullscreen clickable to prevent swipes from reaching the scenes and
                        // other overlays behind this overlay. Clicking will close the overlay.
                        Box(
                            Modifier.fillMaxSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (state.canHideOverlay(key)) {
                                    state.hideOverlay(key, animationScope = animationScope)
                                }
                            }
                        )
                    }

                    overlay.Content(Modifier.align(overlay.alignment))
                }
            }
        }
    }

    private fun overlaysToComposeOrderedByZIndex(): List<Overlay> {
        if (_overlays == null) return emptyList()

        val transitions = state.currentTransitions
        return if (transitions.isEmpty()) {
                state.transitionState.currentOverlays.map { overlay(it) }
            } else {
                buildList {
                    val visited = mutableSetOf<OverlayKey>()
                    fun maybeAdd(key: OverlayKey) {
                        if (visited.add(key)) {
                            add(overlay(key))
                        }
                    }

                    transitions.fastForEach { transition ->
                        when (transition) {
                            is TransitionState.Transition.ChangeScene -> {}
                            is TransitionState.Transition.ShowOrHideOverlay ->
                                maybeAdd(transition.overlay)
                            is TransitionState.Transition.ReplaceOverlay -> {
                                maybeAdd(transition.fromOverlay)
                                maybeAdd(transition.toOverlay)
                            }
                        }
                    }

                    // Make sure that all current overlays are composed.
                    transitions.last().currentOverlays.forEach { maybeAdd(it) }
                }
            }
            .sortedBy { it.zIndex }
    }

    @VisibleForTesting
    internal fun setContentsAndLayoutTargetSizeForTest(size: IntSize) {
        lastSize = size
        (scenes.values + overlays.values).forEach { it.targetSize = size }
    }

    internal fun overlaysOrNullForTest(): Map<OverlayKey, Overlay>? = _overlays
}

private data class LayoutElement(private val layoutImpl: SceneTransitionLayoutImpl) :
    ModifierNodeElement<LayoutNode>() {
    override fun create(): LayoutNode = LayoutNode(layoutImpl)

    override fun update(node: LayoutNode) {
        node.layoutImpl = layoutImpl
    }
}

private class LayoutNode(var layoutImpl: SceneTransitionLayoutImpl) :
    Modifier.Node(), ApproachLayoutModifierNode, LayoutAwareModifierNode {
    override fun onRemeasured(size: IntSize) {
        layoutImpl.lastSize = size
    }

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean {
        return layoutImpl.state.isTransitioning()
    }

    @ExperimentalComposeUiApi
    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        // Measure content normally.
        val placeable = measurable.measure(constraints)

        val width: Int
        val height: Int
        val transition =
            layoutImpl.state.currentTransition as? TransitionState.Transition.ChangeScene
        if (transition == null) {
            width = placeable.width
            height = placeable.height
        } else {
            // Interpolate the size.
            val fromSize = layoutImpl.scene(transition.fromScene).targetSize
            val toSize = layoutImpl.scene(transition.toScene).targetSize

            // Optimization: make sure we don't read state.progress if fromSize ==
            // toSize to avoid running this code every frame when the layout size does
            // not change.
            if (fromSize == toSize) {
                width = fromSize.width
                height = fromSize.height
            } else {
                val overscrollSpec = transition.currentOverscrollSpec
                val progress =
                    when {
                        overscrollSpec == null -> transition.progress
                        overscrollSpec.content == transition.toScene -> 1f
                        else -> 0f
                    }

                val size = lerp(fromSize, toSize, progress)
                width = size.width.coerceAtLeast(0)
                height = size.height.coerceAtLeast(0)
            }
        }

        return layout(width, height) { placeable.place(0, 0) }
    }
}
