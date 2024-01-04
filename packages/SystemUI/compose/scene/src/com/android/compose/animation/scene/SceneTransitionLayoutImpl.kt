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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.intermediateLayout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import com.android.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope

/**
 * The type for the content of movable elements.
 *
 * TODO(b/317972419): Revert back to make this movable content have a single @Composable lambda
 *   parameter.
 */
internal typealias MovableElementContent =
    @Composable
    (MovableElementContentScope, @Composable MovableElementContentScope.() -> Unit) -> Unit

@Stable
internal class SceneTransitionLayoutImpl(
    internal val state: SceneTransitionLayoutStateImpl,
    internal var density: Density,
    internal var edgeDetector: EdgeDetector,
    internal var transitionInterceptionThreshold: Float,
    builder: SceneTransitionLayoutScope.() -> Unit,
    coroutineScope: CoroutineScope,
) {
    /**
     * The map of [Scene]s.
     *
     * TODO(b/317014852): Make this a normal MutableMap instead.
     */
    internal val scenes = SnapshotStateMap<SceneKey, Scene>()

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
     * scenes it is associated to.
     */
    private var _sharedValues:
        MutableMap<ValueKey, MutableMap<ElementKey?, SnapshotStateMap<SceneKey, *>>>? =
        null
    internal val sharedValues:
        MutableMap<ValueKey, MutableMap<ElementKey?, SnapshotStateMap<SceneKey, *>>>
        get() =
            _sharedValues
                ?: mutableMapOf<ValueKey, MutableMap<ElementKey?, SnapshotStateMap<SceneKey, *>>>()
                    .also { _sharedValues = it }

    /**
     * The scenes that are "ready", i.e. they were composed and fully laid-out at least once.
     *
     * Note that this map is *read* during composition, so it is a [SnapshotStateMap] to make sure
     * that we recompose when modifications are made to this map.
     *
     * TODO(b/316901148): Remove this map.
     */
    private val readyScenes = SnapshotStateMap<SceneKey, Boolean>()

    private val horizontalGestureHandler: SceneGestureHandler
    private val verticalGestureHandler: SceneGestureHandler

    init {
        updateScenes(builder)

        // SceneGestureHandler must wait for the scenes to be initialized, in order to access the
        // current scene (required for SwipeTransition).
        horizontalGestureHandler =
            SceneGestureHandler(
                layoutImpl = this,
                orientation = Orientation.Horizontal,
                coroutineScope = coroutineScope,
            )

        verticalGestureHandler =
            SceneGestureHandler(
                layoutImpl = this,
                orientation = Orientation.Vertical,
                coroutineScope = coroutineScope,
            )
    }

    internal fun gestureHandler(orientation: Orientation): SceneGestureHandler =
        when (orientation) {
            Orientation.Vertical -> verticalGestureHandler
            Orientation.Horizontal -> horizontalGestureHandler
        }

    internal fun scene(key: SceneKey): Scene {
        return scenes[key] ?: error("Scene $key is not configured")
    }

    internal fun updateScenes(builder: SceneTransitionLayoutScope.() -> Unit) {
        // Keep a reference of the current scenes. After processing [builder], the scenes that were
        // not configured will be removed.
        val scenesToRemove = scenes.keys.toMutableSet()

        // The incrementing zIndex of each scene.
        var zIndex = 0f

        object : SceneTransitionLayoutScope {
                override fun scene(
                    key: SceneKey,
                    userActions: Map<UserAction, SceneKey>,
                    content: @Composable SceneScope.() -> Unit,
                ) {
                    scenesToRemove.remove(key)

                    val scene = scenes[key]
                    if (scene != null) {
                        // Update an existing scene.
                        scene.content = content
                        scene.userActions = userActions
                        scene.zIndex = zIndex
                    } else {
                        // New scene.
                        scenes[key] =
                            Scene(
                                key,
                                this@SceneTransitionLayoutImpl,
                                content,
                                userActions,
                                zIndex,
                            )
                    }

                    zIndex++
                }
            }
            .builder()

        scenesToRemove.forEach { scenes.remove(it) }
    }

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    internal fun Content(modifier: Modifier) {
        Box(
            modifier
                // Handle horizontal and vertical swipes on this layout.
                // Note: order here is important and will give a slight priority to the vertical
                // swipes.
                .swipeToScene(horizontalGestureHandler)
                .swipeToScene(verticalGestureHandler)
                // Animate the size of this layout.
                .intermediateLayout { measurable, constraints ->
                    // Measure content normally.
                    val placeable = measurable.measure(constraints)

                    val width: Int
                    val height: Int
                    val transition = state.currentTransition
                    if (transition == null) {
                        width = placeable.width
                        height = placeable.height
                    } else {
                        // Interpolate the size.
                        val fromSize = scene(transition.fromScene).targetSize
                        val toSize = scene(transition.toScene).targetSize

                        // Optimization: make sure we don't read state.progress if fromSize ==
                        // toSize to avoid running this code every frame when the layout size does
                        // not change.
                        if (fromSize == toSize) {
                            width = fromSize.width
                            height = fromSize.height
                        } else {
                            val size = lerp(fromSize, toSize, transition.progress)
                            width = size.width.coerceAtLeast(0)
                            height = size.height.coerceAtLeast(0)
                        }
                    }

                    layout(width, height) { placeable.place(0, 0) }
                }
        ) {
            LookaheadScope {
                val scenesToCompose =
                    when (val state = state.transitionState) {
                        is TransitionState.Idle -> listOf(scene(state.currentScene))
                        is TransitionState.Transition -> {
                            if (state.toScene != state.fromScene) {
                                listOf(scene(state.toScene), scene(state.fromScene))
                            } else {
                                listOf(scene(state.fromScene))
                            }
                        }
                    }

                // Handle back events.
                // TODO(b/290184746): Make sure that this works with SystemUI once we use
                // SceneTransitionLayout in Flexiglass.
                scene(state.transitionState.currentScene).userActions[Back]?.let { backScene ->
                    BackHandler { state.onChangeScene(backScene) }
                }

                Box {
                    scenesToCompose.fastForEach { scene ->
                        val key = scene.key
                        key(key) {
                            // Mark this scene as ready once it has been composed, laid out and
                            // drawn the first time. We have to do this in a LaunchedEffect here
                            // because DisposableEffect runs between composition and layout.
                            LaunchedEffect(key) { readyScenes[key] = true }
                            DisposableEffect(key) { onDispose { readyScenes.remove(key) } }

                            scene.Content(
                                Modifier.drawWithContent {
                                    if (state.currentTransition == null) {
                                        drawContent()
                                    } else {
                                        // Don't draw scenes that are not ready yet.
                                        if (readyScenes.containsKey(key)) {
                                            drawContent()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Return whether [transition] is ready, i.e. the elements of both scenes of the transition were
     * laid out at least once.
     */
    internal fun isTransitionReady(transition: TransitionState.Transition): Boolean {
        return readyScenes.containsKey(transition.fromScene) &&
            readyScenes.containsKey(transition.toScene)
    }

    internal fun isSceneReady(scene: SceneKey): Boolean = readyScenes.containsKey(scene)

    internal fun setScenesTargetSizeForTest(size: IntSize) {
        scenes.values.forEach { it.targetSize = size }
    }
}
