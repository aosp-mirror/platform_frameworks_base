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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import com.android.compose.ui.util.lerp
import kotlinx.coroutines.flow.collect

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
    scene: Scene,
    element: Element?,
    key: ValueKey,
    value: T,
    lerp: (T, T, Float) -> T,
    canOverflow: Boolean,
): AnimatedState<T> {
    // Create the associated SharedValue object that holds the current value.
    DisposableEffect(scene, element, key) {
        val sharedValues = sharedValues(scene, element)
        sharedValues[key] = Element.SharedValue(key, value)
        onDispose { sharedValues.remove(key) }
    }

    // Update the current value. Note that side effects run after disposable effects, so we know
    // that the SharedValue object was created at this point.
    SideEffect { sharedValue<T>(scene, element, key).value = value }

    val sceneKey = scene.key
    return remember(layoutImpl, sceneKey, element, lerp, canOverflow) {
        object : AnimatedState<T> {
            override val value: T
                get() = value(layoutImpl, sceneKey, element, key, lerp, canOverflow)

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

private fun sharedValues(
    scene: Scene,
    element: Element?,
): MutableMap<ValueKey, Element.SharedValue<*>> {
    return element?.sceneValues?.getValue(scene.key)?.sharedValues ?: scene.sharedValues
}

private fun <T> sharedValueOrNull(
    scene: Scene,
    element: Element?,
    key: ValueKey,
): Element.SharedValue<T>? {
    val sharedValue = sharedValues(scene, element)[key] ?: return null
    return sharedValue as Element.SharedValue<T>
}

private fun <T> sharedValue(
    scene: Scene,
    element: Element?,
    key: ValueKey,
): Element.SharedValue<T> {
    return sharedValueOrNull(scene, element, key) ?: error(valueReadTooEarlyMessage(key))
}

private fun valueReadTooEarlyMessage(key: ValueKey) =
    "Animated value $key was read before its target values were set. This probably " +
        "means that you are reading it during composition, which you should not do. See the " +
        "documentation of AnimatedState for more information."

private fun <T> value(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: Element?,
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
    element: Element?,
    key: ValueKey,
    lerp: (T, T, Float) -> T,
    canOverflow: Boolean,
): T? {
    fun sceneValue(scene: SceneKey): Element.SharedValue<T>? {
        val sharedValues =
            if (element == null) {
                layoutImpl.scene(scene).sharedValues
            } else {
                element.sceneValues[scene]?.sharedValues
            }
                ?: return null
        val value = sharedValues[key] ?: return null
        return value as Element.SharedValue<T>
    }

    return when (val transition = layoutImpl.state.transitionState) {
        is TransitionState.Idle -> sceneValue(transition.currentScene)?.value
        is TransitionState.Transition -> {
            // Note: no need to check for transition ready here given that all target values are
            // defined during composition, we should already have the correct values to interpolate
            // between here.
            val fromValue = sceneValue(transition.fromScene)
            val toValue = sceneValue(transition.toScene)
            if (fromValue != null && toValue != null) {
                val from = fromValue.value
                val to = toValue.value
                if (from == to) {
                    // Optimization: avoid reading progress if the values are the same, so we don't
                    // relayout/redraw for nothing.
                    from
                } else {
                    val progress =
                        if (canOverflow) transition.progress
                        else transition.progress.coerceIn(0f, 1f)
                    lerp(from, to, progress)
                }
            } else if (fromValue != null) {
                fromValue.value
            } else toValue?.value
        }
    }
    // TODO(b/311600838): Remove this. We should not have to fallback to the current scene value,
    // but we have to because code of removed nodes can still run if they are placed with a graphics
    // layer.
    ?: sceneValue(scene)?.value
}
