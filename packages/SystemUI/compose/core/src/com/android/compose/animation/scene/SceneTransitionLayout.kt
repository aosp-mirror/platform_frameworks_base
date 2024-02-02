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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/**
 * [SceneTransitionLayout] is a container that automatically animates its content whenever
 * [currentScene] changes, using the transitions defined in [transitions].
 *
 * Note: You should use [androidx.compose.animation.AnimatedContent] instead of
 * [SceneTransitionLayout] if it fits your need. Use [SceneTransitionLayout] over AnimatedContent if
 * you need support for swipe gestures, shared elements or transitions defined declaratively outside
 * UI code.
 *
 * @param currentScene the current scene
 * @param onChangeScene a mutator that should set [currentScene] to the given scene when called.
 *   This is called when the user commits a transition to a new scene because of a [UserAction], for
 *   instance by triggering back navigation or by swiping to a new scene.
 * @param transitions the definition of the transitions used to animate a change of scene.
 * @param state the observable state of this layout.
 * @param scenes the configuration of the different scenes of this layout.
 */
@Composable
fun SceneTransitionLayout(
    currentScene: SceneKey,
    onChangeScene: (SceneKey) -> Unit,
    transitions: SceneTransitions,
    modifier: Modifier = Modifier,
    state: SceneTransitionLayoutState = remember { SceneTransitionLayoutState(currentScene) },
    scenes: SceneTransitionLayoutScope.() -> Unit,
) {
    val density = LocalDensity.current
    val layoutImpl = remember {
        SceneTransitionLayoutImpl(
            onChangeScene,
            scenes,
            transitions,
            state,
            density,
        )
    }

    layoutImpl.onChangeScene = onChangeScene
    layoutImpl.transitions = transitions
    layoutImpl.density = density
    layoutImpl.setScenes(scenes)
    layoutImpl.setCurrentScene(currentScene)

    layoutImpl.Content(modifier)
}

interface SceneTransitionLayoutScope {
    /**
     * Add a scene to this layout, identified by [key].
     *
     * You can configure [userActions] so that swiping on this layout or navigating back will
     * transition to a different scene.
     *
     * Important: scene order along the z-axis follows call order. Calling scene(A) followed by
     * scene(B) will mean that scene B renders after/above scene A.
     */
    fun scene(
        key: SceneKey,
        userActions: Map<UserAction, SceneKey> = emptyMap(),
        content: @Composable SceneScope.() -> Unit,
    )
}

interface SceneScope {
    /**
     * Tag an element identified by [key].
     *
     * Tagging an element will allow you to reference that element when defining transitions, so
     * that the element can be transformed and animated when the scene transitions in or out.
     *
     * Additionally, this [key] will be used to detect elements that are shared between scenes to
     * automatically interpolate their size, offset and [shared values][animateSharedValueAsState].
     *
     * TODO(b/291566282): Migrate this to the new Modifier Node API and remove the @Composable
     *   constraint.
     */
    @Composable fun Modifier.element(key: ElementKey): Modifier

    /**
     * Animate some value of a shared element.
     *
     * @param value the value of this shared value in the current scene.
     * @param key the key of this shared value.
     * @param element the element associated with this value.
     * @param lerp the *linear* interpolation function that should be used to interpolate between
     *   two different values. Note that it has to be linear because the [fraction] passed to this
     *   interpolator is already interpolated.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateSharedIntAsState
     * @see animateSharedFloatAsState
     * @see animateSharedDpAsState
     * @see animateSharedColorAsState
     */
    @Composable
    fun <T> animateSharedValueAsState(
        value: T,
        key: ValueKey,
        element: ElementKey,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean,
    ): State<T>
}

/** An action performed by the user. */
sealed interface UserAction

/** The user navigated back, either using a gesture or by triggering a KEYCODE_BACK event. */
object Back : UserAction

/** The user swiped on the container. */
enum class Swipe : UserAction {
    Up,
    Down,
    Left,
    Right,
}
