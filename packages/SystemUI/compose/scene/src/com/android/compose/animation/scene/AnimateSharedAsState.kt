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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastLastOrNull
import androidx.compose.ui.util.lerp

/**
 * A [State] whose [value] is animated.
 *
 * Important: This animated value should always be ready *after* composition, e.g. during layout,
 * drawing or inside a LaunchedEffect. If you read [value] during composition, it will probably
 * throw an exception, for 2 important reasons:
 * 1. You should never read animated values during composition, because this will probably lead to
 *    bad performance.
 * 2. Given that this value depends on the target value in different scenes, its current value
 *    (depending on the current transition state) can only be computed once the full tree has been
 *    composed.
 *
 * If you don't have the choice and *have to* get the value during composition, for instance because
 * a Modifier or Composable reading this value does not have a lazy/lambda-based API, then you can
 * access [unsafeCompositionState] and use a fallback value for the first frame where this animated
 * value can not be computed yet. Note however that doing so will be bad for performance and might
 * lead to late-by-one-frame flickers.
 */
@Stable
interface AnimatedState<T> : State<T> {
    /**
     * Return a [State] that can be read during composition.
     *
     * Important: You should avoid using this as much as possible and instead read [value] during
     * layout/drawing, otherwise you will probably end up with a few frames that have a value that
     * is not correctly interpolated.
     */
    @Composable fun unsafeCompositionState(initialValue: T): State<T>
}

/**
 * Animate a scene Int value.
 *
 * @see SceneScope.animateSceneValueAsState
 */
@Composable
fun SceneScope.animateSceneIntAsState(
    value: Int,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Int> {
    return animateSceneValueAsState(value, key, ::lerp, canOverflow)
}

/**
 * Animate a shared element Int value.
 *
 * @see ElementScope.animateElementValueAsState
 */
@Composable
fun ElementScope<*>.animateElementIntAsState(
    value: Int,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Int> {
    return animateElementValueAsState(value, key, ::lerp, canOverflow)
}

/**
 * Animate a scene Float value.
 *
 * @see SceneScope.animateSceneValueAsState
 */
@Composable
fun SceneScope.animateSceneFloatAsState(
    value: Float,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Float> {
    return animateSceneValueAsState(value, key, ::lerp, canOverflow)
}

/**
 * Animate a shared element Float value.
 *
 * @see ElementScope.animateElementValueAsState
 */
@Composable
fun ElementScope<*>.animateElementFloatAsState(
    value: Float,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Float> {
    return animateElementValueAsState(value, key, ::lerp, canOverflow)
}

/**
 * Animate a scene Dp value.
 *
 * @see SceneScope.animateSceneValueAsState
 */
@Composable
fun SceneScope.animateSceneDpAsState(
    value: Dp,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Dp> {
    return animateSceneValueAsState(value, key, ::lerp, canOverflow)
}

/**
 * Animate a shared element Dp value.
 *
 * @see ElementScope.animateElementValueAsState
 */
@Composable
fun ElementScope<*>.animateElementDpAsState(
    value: Dp,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Dp> {
    return animateElementValueAsState(value, key, ::lerp, canOverflow)
}

/**
 * Animate a scene Color value.
 *
 * @see SceneScope.animateSceneValueAsState
 */
@Composable
fun SceneScope.animateSceneColorAsState(
    value: Color,
    key: ValueKey,
): AnimatedState<Color> {
    return animateSceneValueAsState(value, key, ::lerp, canOverflow = false)
}

/**
 * Animate a shared element Color value.
 *
 * @see ElementScope.animateElementValueAsState
 */
@Composable
fun ElementScope<*>.animateElementColorAsState(
    value: Color,
    key: ValueKey,
): AnimatedState<Color> {
    return animateElementValueAsState(value, key, ::lerp, canOverflow = false)
}

@Composable
internal fun <T> animateSharedValueAsState(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: ElementKey?,
    key: ValueKey,
    value: T,
    lerp: (T, T, Float) -> T,
    canOverflow: Boolean,
): AnimatedState<T> {
    DisposableEffect(layoutImpl, scene, element, key) {
        // Create the associated maps that hold the current value for each (element, scene) pair.
        val valueMap = layoutImpl.sharedValues.getOrPut(key) { mutableMapOf() }
        val sceneToValueMap =
            valueMap.getOrPut(element) { SnapshotStateMap<SceneKey, Any>() }
                as SnapshotStateMap<SceneKey, T>
        sceneToValueMap[scene] = value

        onDispose {
            // Remove the value associated to the current scene, and eventually remove the maps if
            // they are empty.
            sceneToValueMap.remove(scene)

            if (sceneToValueMap.isEmpty() && valueMap[element] === sceneToValueMap) {
                valueMap.remove(element)

                if (valueMap.isEmpty() && layoutImpl.sharedValues[key] === valueMap) {
                    layoutImpl.sharedValues.remove(key)
                }
            }
        }
    }

    // Update the current value. Note that side effects run after disposable effects, so we know
    // that the associated maps were created at this point.
    SideEffect { sceneToValueMap<T>(layoutImpl, key, element)[scene] = value }

    return remember(layoutImpl, scene, element, lerp, canOverflow) {
        object : AnimatedState<T> {
            override val value: T
                get() = value(layoutImpl, scene, element, key, lerp, canOverflow)

            @Composable
            override fun unsafeCompositionState(initialValue: T): State<T> {
                val state = remember { mutableStateOf(initialValue) }

                val animatedState = this
                LaunchedEffect(animatedState) {
                    snapshotFlow { animatedState.value }.collect { state.value = it }
                }

                return state
            }
        }
    }
}

private fun <T> sceneToValueMap(
    layoutImpl: SceneTransitionLayoutImpl,
    key: ValueKey,
    element: ElementKey?
): MutableMap<SceneKey, T> {
    return layoutImpl.sharedValues[key]?.get(element)?.let { it as SnapshotStateMap<SceneKey, T> }
        ?: error(valueReadTooEarlyMessage(key))
}

private fun valueReadTooEarlyMessage(key: ValueKey) =
    "Animated value $key was read before its target values were set. This probably " +
        "means that you are reading it during composition, which you should not do. See the " +
        "documentation of AnimatedState for more information."

private fun <T> value(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: ElementKey?,
    key: ValueKey,
    lerp: (T, T, Float) -> T,
    canOverflow: Boolean,
): T {
    return valueOrNull(layoutImpl, scene, element, key, lerp, canOverflow)
        ?: error(valueReadTooEarlyMessage(key))
}

private fun <T> valueOrNull(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: ElementKey?,
    key: ValueKey,
    lerp: (T, T, Float) -> T,
    canOverflow: Boolean,
): T? {
    val sceneToValueMap = sceneToValueMap<T>(layoutImpl, key, element)
    fun sceneValue(scene: SceneKey): T? = sceneToValueMap[scene]

    val transition =
        transition(layoutImpl, element, sceneToValueMap)
            ?: return sceneValue(layoutImpl.state.transitionState.currentScene)
                // TODO(b/311600838): Remove this. We should not have to fallback to the current
                // scene value, but we have to because code of removed nodes can still run if they
                // are placed with a graphics layer.
                ?: sceneValue(scene)

    val fromValue = sceneValue(transition.fromScene)
    val toValue = sceneValue(transition.toScene)
    return if (fromValue != null && toValue != null) {
        if (fromValue == toValue) {
            // Optimization: avoid reading progress if the values are the same, so we don't
            // relayout/redraw for nothing.
            fromValue
        } else {
            // In the case of bouncing, if the value remains constant during the overscroll,
            // we should use the value of the scene we are bouncing around.
            if (!canOverflow && transition is TransitionState.HasOverscrollProperties) {
                val bouncingScene = transition.bouncingScene
                if (bouncingScene != null) {
                    return sceneValue(bouncingScene)
                }
            }

            val progress =
                if (canOverflow) transition.progress else transition.progress.fastCoerceIn(0f, 1f)
            lerp(fromValue, toValue, progress)
        }
    } else
        fromValue
            ?: toValue
            // TODO(b/311600838): Remove this. We should not have to fallback to the current scene
            // value, but we have to because code of removed nodes can still run if they are placed
            // with a graphics layer.
            ?: sceneValue(scene)
}

private fun transition(
    layoutImpl: SceneTransitionLayoutImpl,
    element: ElementKey?,
    sceneToValueMap: Map<SceneKey, *>,
): TransitionState.Transition? {
    return if (element != null) {
        layoutImpl.elements[element]?.sceneStates?.let { sceneStates ->
            layoutImpl.state.currentTransitions.fastLastOrNull { transition ->
                transition.fromScene in sceneStates || transition.toScene in sceneStates
            }
        }
    } else {
        layoutImpl.state.currentTransitions.fastLastOrNull { transition ->
            transition.fromScene in sceneToValueMap || transition.toScene in sceneToValueMap
        }
    }
}
