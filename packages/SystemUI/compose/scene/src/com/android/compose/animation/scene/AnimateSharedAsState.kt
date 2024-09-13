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
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastLastOrNull
import com.android.compose.animation.scene.content.state.TransitionState
import kotlin.math.roundToInt

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
 * @see ContentScope.animateContentValueAsState
 */
@Composable
fun ContentScope.animateContentIntAsState(
    value: Int,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Int> {
    return animateContentValueAsState(value, key, SharedIntType, canOverflow)
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
    return animateElementValueAsState(value, key, SharedIntType, canOverflow)
}

private object SharedIntType : SharedValueType<Int, Int> {
    override val unspecifiedValue: Int = Int.MIN_VALUE
    override val zeroDeltaValue: Int = 0

    override fun lerp(a: Int, b: Int, progress: Float): Int =
        androidx.compose.ui.util.lerp(a, b, progress)

    override fun diff(a: Int, b: Int): Int = a - b

    override fun addWeighted(a: Int, b: Int, bWeight: Float): Int = (a + b * bWeight).roundToInt()
}

/**
 * Animate a scene Float value.
 *
 * @see ContentScope.animateContentValueAsState
 */
@Composable
fun ContentScope.animateContentFloatAsState(
    value: Float,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Float> {
    return animateContentValueAsState(value, key, SharedFloatType, canOverflow)
}

@Deprecated(
    "Use animateContentFloatAsState() instead",
    replaceWith = ReplaceWith("animateContentFloatAsState(value, key, canOverflow)"),
)
@Composable
fun ContentScope.animateSceneFloatAsState(
    value: Float,
    key: ValueKey,
    canOverflow: Boolean = true,
) = animateContentFloatAsState(value, key, canOverflow)

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
    return animateElementValueAsState(value, key, SharedFloatType, canOverflow)
}

private object SharedFloatType : SharedValueType<Float, Float> {
    override val unspecifiedValue: Float = Float.MIN_VALUE
    override val zeroDeltaValue: Float = 0f

    override fun lerp(a: Float, b: Float, progress: Float): Float =
        androidx.compose.ui.util.lerp(a, b, progress)

    override fun diff(a: Float, b: Float): Float = a - b

    override fun addWeighted(a: Float, b: Float, bWeight: Float): Float = a + b * bWeight
}

/**
 * Animate a scene Dp value.
 *
 * @see ContentScope.animateContentValueAsState
 */
@Composable
fun ContentScope.animateContentDpAsState(
    value: Dp,
    key: ValueKey,
    canOverflow: Boolean = true,
): AnimatedState<Dp> {
    return animateContentValueAsState(value, key, SharedDpType, canOverflow)
}

@Deprecated(
    "Use animateContentDpAsState() instead",
    replaceWith = ReplaceWith("animateContentDpAsState(value, key, canOverflow)"),
)
@Composable
fun ContentScope.animateSceneDpAsState(value: Dp, key: ValueKey, canOverflow: Boolean = true) =
    animateContentDpAsState(value, key, canOverflow)

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
    return animateElementValueAsState(value, key, SharedDpType, canOverflow)
}

private object SharedDpType : SharedValueType<Dp, Dp> {
    override val unspecifiedValue: Dp = Dp.Unspecified
    override val zeroDeltaValue: Dp = 0.dp

    override fun lerp(a: Dp, b: Dp, progress: Float): Dp {
        return androidx.compose.ui.unit.lerp(a, b, progress)
    }

    override fun diff(a: Dp, b: Dp): Dp = a - b

    override fun addWeighted(a: Dp, b: Dp, bWeight: Float): Dp = a + b * bWeight
}

/**
 * Animate a scene Color value.
 *
 * @see ContentScope.animateContentValueAsState
 */
@Composable
fun ContentScope.animateContentColorAsState(value: Color, key: ValueKey): AnimatedState<Color> {
    return animateContentValueAsState(value, key, SharedColorType, canOverflow = false)
}

/**
 * Animate a shared element Color value.
 *
 * @see ElementScope.animateElementValueAsState
 */
@Composable
fun ElementScope<*>.animateElementColorAsState(value: Color, key: ValueKey): AnimatedState<Color> {
    return animateElementValueAsState(value, key, SharedColorType, canOverflow = false)
}

private object SharedColorType : SharedValueType<Color, ColorDelta> {
    override val unspecifiedValue: Color = Color.Unspecified
    override val zeroDeltaValue: ColorDelta = ColorDelta(0f, 0f, 0f, 0f)

    override fun lerp(a: Color, b: Color, progress: Float): Color {
        return androidx.compose.ui.graphics.lerp(a, b, progress)
    }

    override fun diff(a: Color, b: Color): ColorDelta {
        // Similar to lerp, we convert colors to the Oklab color space to perform operations on
        // colors.
        val aOklab = a.convert(ColorSpaces.Oklab)
        val bOklab = b.convert(ColorSpaces.Oklab)
        return ColorDelta(
            red = aOklab.red - bOklab.red,
            green = aOklab.green - bOklab.green,
            blue = aOklab.blue - bOklab.blue,
            alpha = aOklab.alpha - bOklab.alpha,
        )
    }

    override fun addWeighted(a: Color, b: ColorDelta, bWeight: Float): Color {
        val aOklab = a.convert(ColorSpaces.Oklab)
        return Color(
                red = aOklab.red + b.red * bWeight,
                green = aOklab.green + b.green * bWeight,
                blue = aOklab.blue + b.blue * bWeight,
                alpha = aOklab.alpha + b.alpha * bWeight,
                colorSpace = ColorSpaces.Oklab,
            )
            .convert(aOklab.colorSpace)
    }
}

/**
 * Represents the diff between two colors in the same color space.
 *
 * Note: This class is necessary because Color() checks the bounds of its values and UncheckedColor
 * is internal.
 */
private class ColorDelta(val red: Float, val green: Float, val blue: Float, val alpha: Float)

@Composable
internal fun <T> animateSharedValueAsState(
    layoutImpl: SceneTransitionLayoutImpl,
    content: ContentKey,
    element: ElementKey?,
    key: ValueKey,
    value: T,
    type: SharedValueType<T, *>,
    canOverflow: Boolean,
): AnimatedState<T> {
    DisposableEffect(layoutImpl, content, element, key) {
        // Create the associated maps that hold the current value for each (element, content) pair.
        val valueMap = layoutImpl.sharedValues.getOrPut(key) { mutableMapOf() }
        val sharedValue = valueMap.getOrPut(element) { SharedValue(type) } as SharedValue<T, *>
        val targetValues = sharedValue.targetValues
        targetValues[content] = value

        onDispose {
            // Remove the value associated to the current scene, and eventually remove the maps if
            // they are empty.
            targetValues.remove(content)

            if (targetValues.isEmpty() && valueMap[element] === sharedValue) {
                valueMap.remove(element)

                if (valueMap.isEmpty() && layoutImpl.sharedValues[key] === valueMap) {
                    layoutImpl.sharedValues.remove(key)
                }
            }
        }
    }

    // Update the current value. Note that side effects run after disposable effects, so we know
    // that the associated maps were created at this point.
    SideEffect {
        if (value == type.unspecifiedValue) {
            error("value is equal to $value, which is the undefined value for this type.")
        }

        sharedValue<T, Any>(layoutImpl, key, element).targetValues[content] = value
    }

    return remember(layoutImpl, content, element, canOverflow) {
        AnimatedStateImpl<T, Any>(layoutImpl, content, element, key, canOverflow)
    }
}

private fun <T, Delta> sharedValue(
    layoutImpl: SceneTransitionLayoutImpl,
    key: ValueKey,
    element: ElementKey?,
): SharedValue<T, Delta> {
    return layoutImpl.sharedValues[key]?.get(element)?.let { it as SharedValue<T, Delta> }
        ?: error(valueReadTooEarlyMessage(key))
}

private fun valueReadTooEarlyMessage(key: ValueKey) =
    "Animated value $key was read before its target values were set. This probably " +
        "means that you are reading it during composition, which you should not do. See the " +
        "documentation of AnimatedState for more information."

internal class SharedValue<T, Delta>(val type: SharedValueType<T, Delta>) {
    /** The target value of this shared value for each content. */
    val targetValues = SnapshotStateMap<ContentKey, T>()

    /** The last value of this shared value. */
    var lastValue: T = type.unspecifiedValue

    /** The value of this shared value before the last interruption (if any). */
    var valueBeforeInterruption: T = type.unspecifiedValue

    /** The delta value to add to this shared value to have smoother interruptions. */
    var valueInterruptionDelta = type.zeroDeltaValue

    /** The last transition that was used when the value of this shared state. */
    var lastTransition: TransitionState.Transition? = null
}

private class AnimatedStateImpl<T, Delta>(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val content: ContentKey,
    private val element: ElementKey?,
    private val key: ValueKey,
    private val canOverflow: Boolean,
) : AnimatedState<T> {
    override val value: T
        get() = value()

    private fun value(): T {
        val sharedValue = sharedValue<T, Delta>(layoutImpl, key, element)
        val transition = transition(sharedValue)
        val value: T =
            valueOrNull(sharedValue, transition)
                // TODO(b/311600838): Remove this. We should not have to fallback to the current
                // scene value, but we have to because code of removed nodes can still run if they
                // are placed with a graphics layer.
                ?: sharedValue[content]
                ?: error(valueReadTooEarlyMessage(key))
        val interruptedValue = computeInterruptedValue(sharedValue, transition, value)
        sharedValue.lastValue = interruptedValue
        return interruptedValue
    }

    private operator fun SharedValue<T, *>.get(content: ContentKey): T? = targetValues[content]

    private fun valueOrNull(
        sharedValue: SharedValue<T, *>,
        transition: TransitionState.Transition?,
    ): T? {
        if (transition == null) {
            return sharedValue[content]
                ?: sharedValue[layoutImpl.state.transitionState.currentScene]
        }

        val fromValue = sharedValue[transition.fromContent]
        val toValue = sharedValue[transition.toContent]
        if (fromValue == null && toValue == null) {
            return null
        }

        if (fromValue != null && toValue != null) {
            return interpolateSharedValue(fromValue, toValue, transition, sharedValue)
        }

        if (transition is TransitionState.Transition.ReplaceOverlay) {
            val currentSceneValue = sharedValue[transition.currentScene]
            if (currentSceneValue != null) {
                return interpolateSharedValue(
                    fromValue = fromValue ?: currentSceneValue,
                    toValue = toValue ?: currentSceneValue,
                    transition,
                    sharedValue,
                )
            }
        }

        return fromValue ?: toValue
    }

    private fun interpolateSharedValue(
        fromValue: T,
        toValue: T,
        transition: TransitionState.Transition,
        sharedValue: SharedValue<T, *>,
    ): T? {
        if (fromValue == toValue) {
            // Optimization: avoid reading progress if the values are the same, so we don't
            // relayout/redraw for nothing.
            return fromValue
        }

        val overscrollSpec = transition.currentOverscrollSpec
        val progress =
            when {
                overscrollSpec == null -> {
                    if (canOverflow) transition.progress
                    else transition.progress.fastCoerceIn(0f, 1f)
                }
                overscrollSpec.content == transition.toContent -> 1f
                else -> 0f
            }

        return sharedValue.type.lerp(fromValue, toValue, progress)
    }

    private fun transition(sharedValue: SharedValue<T, Delta>): TransitionState.Transition? {
        val targetValues = sharedValue.targetValues
        val transition =
            if (element != null) {
                layoutImpl.elements[element]?.let { element ->
                    elementState(
                        layoutImpl.state.transitionStates,
                        isInContent = { it in element.stateByContent },
                    )
                        as? TransitionState.Transition
                }
            } else {
                layoutImpl.state.currentTransitions.fastLastOrNull { transition ->
                    transition.fromContent in targetValues || transition.toContent in targetValues
                }
            }

        val previousTransition = sharedValue.lastTransition
        sharedValue.lastTransition = transition

        if (transition != previousTransition && transition != null && previousTransition != null) {
            // The previous transition was interrupted by another transition.
            sharedValue.valueBeforeInterruption = sharedValue.lastValue
            sharedValue.valueInterruptionDelta = sharedValue.type.zeroDeltaValue
        } else if (transition == null && previousTransition != null) {
            // The transition was just finished.
            sharedValue.valueBeforeInterruption = sharedValue.type.unspecifiedValue
            sharedValue.valueInterruptionDelta = sharedValue.type.zeroDeltaValue
        }

        return transition
    }

    /**
     * Compute what [value] should be if we take the
     * [interruption progress][TransitionState.Transition.interruptionProgress] of [transition] into
     * account.
     */
    private fun computeInterruptedValue(
        sharedValue: SharedValue<T, Delta>,
        transition: TransitionState.Transition?,
        value: T,
    ): T {
        val type = sharedValue.type
        if (sharedValue.valueBeforeInterruption != type.unspecifiedValue) {
            sharedValue.valueInterruptionDelta =
                type.diff(sharedValue.valueBeforeInterruption, value)
            sharedValue.valueBeforeInterruption = type.unspecifiedValue
        }

        val delta = sharedValue.valueInterruptionDelta
        return if (delta == type.zeroDeltaValue || transition == null) {
            value
        } else {
            val interruptionProgress = transition.interruptionProgress(layoutImpl)
            if (interruptionProgress == 0f) {
                value
            } else {
                type.addWeighted(value, delta, interruptionProgress)
            }
        }
    }

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
