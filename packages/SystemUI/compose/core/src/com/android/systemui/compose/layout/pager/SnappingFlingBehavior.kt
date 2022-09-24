/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.compose.layout.pager

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Default values used for [SnappingFlingBehavior] & [rememberSnappingFlingBehavior]. */
internal object SnappingFlingBehaviorDefaults {
    /** TODO */
    val snapAnimationSpec: AnimationSpec<Float> = spring(stiffness = 600f)
}

/**
 * Create and remember a snapping [FlingBehavior] to be used with [LazyListState].
 *
 * TODO: move this to a new module and make it public
 *
 * @param lazyListState The [LazyListState] to update.
 * @param decayAnimationSpec The decay animation spec to use for decayed flings.
 * @param snapAnimationSpec The animation spec to use when snapping.
 */
@Composable
internal fun rememberSnappingFlingBehavior(
    lazyListState: LazyListState,
    decayAnimationSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay(),
    snapAnimationSpec: AnimationSpec<Float> = SnappingFlingBehaviorDefaults.snapAnimationSpec,
): SnappingFlingBehavior =
    remember(lazyListState, decayAnimationSpec, snapAnimationSpec) {
        SnappingFlingBehavior(
            lazyListState = lazyListState,
            decayAnimationSpec = decayAnimationSpec,
            snapAnimationSpec = snapAnimationSpec,
        )
    }

/**
 * A snapping [FlingBehavior] for [LazyListState]. Typically this would be created via
 * [rememberSnappingFlingBehavior].
 *
 * @param lazyListState The [LazyListState] to update.
 * @param decayAnimationSpec The decay animation spec to use for decayed flings.
 * @param snapAnimationSpec The animation spec to use when snapping.
 */
internal class SnappingFlingBehavior(
    private val lazyListState: LazyListState,
    private val decayAnimationSpec: DecayAnimationSpec<Float>,
    private val snapAnimationSpec: AnimationSpec<Float>,
) : FlingBehavior {
    /** The target item index for any on-going animations. */
    var animationTarget: Int? by mutableStateOf(null)
        private set

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val itemInfo = currentItemInfo ?: return initialVelocity

        // If the decay fling can scroll past the current item, fling with decay
        return if (decayAnimationSpec.canFlingPastCurrentItem(itemInfo, initialVelocity)) {
            performDecayFling(initialVelocity, itemInfo)
        } else {
            // Otherwise we 'spring' to current/next item
            performSpringFling(
                index =
                    when {
                        // If the velocity is greater than 1 item per second (velocity is px/s),
                        // spring
                        // in the relevant direction
                        initialVelocity > itemInfo.size -> {
                            (itemInfo.index + 1).coerceAtMost(
                                lazyListState.layoutInfo.totalItemsCount - 1
                            )
                        }
                        initialVelocity < -itemInfo.size -> itemInfo.index
                        // If the velocity is 0 (or less than the size of the item), spring to
                        // whichever item is closest to the snap point
                        itemInfo.offset < -itemInfo.size / 2 -> itemInfo.index + 1
                        else -> itemInfo.index
                    },
                initialVelocity = initialVelocity,
            )
        }
    }

    private suspend fun ScrollScope.performDecayFling(
        initialVelocity: Float,
        startItem: LazyListItemInfo,
    ): Float {
        val index =
            when {
                initialVelocity > 0 -> startItem.index + 1
                else -> startItem.index
            }
        val forward = index > (currentItemInfo?.index ?: return initialVelocity)

        // Update the animationTarget
        animationTarget = index

        var velocityLeft = initialVelocity
        var lastValue = 0f
        AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            )
            .animateDecay(decayAnimationSpec) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = this.velocity

                val current = currentItemInfo
                if (current == null) {
                    cancelAnimation()
                    return@animateDecay
                }

                if (
                    !forward &&
                        (current.index < index || current.index == index && current.offset >= 0)
                ) {
                    // 'snap back' to the item as we may have scrolled past it
                    scrollBy(lazyListState.calculateScrollOffsetToItem(index).toFloat())
                    cancelAnimation()
                } else if (
                    forward &&
                        (current.index > index || current.index == index && current.offset <= 0)
                ) {
                    // 'snap back' to the item as we may have scrolled past it
                    scrollBy(lazyListState.calculateScrollOffsetToItem(index).toFloat())
                    cancelAnimation()
                } else if (abs(delta - consumed) > 0.5f) {
                    // avoid rounding errors and stop if anything is unconsumed
                    cancelAnimation()
                }
            }
        animationTarget = null
        return velocityLeft
    }

    private suspend fun ScrollScope.performSpringFling(
        index: Int,
        scrollOffset: Int = 0,
        initialVelocity: Float = 0f,
    ): Float {
        // If we don't have a current layout, we can't snap
        val initialItem = currentItemInfo ?: return initialVelocity

        val forward = index > initialItem.index
        // We add 10% on to the size of the current item, to compensate for any item spacing, etc
        val target = (if (forward) initialItem.size else -initialItem.size) * 1.1f

        // Update the animationTarget
        animationTarget = index

        var velocityLeft = initialVelocity
        var lastValue = 0f
        AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            )
            .animateTo(
                targetValue = target,
                animationSpec = snapAnimationSpec,
            ) {
                // Springs can overshoot their target, clamp to the desired range
                val coercedValue =
                    if (forward) {
                        value.coerceAtMost(target)
                    } else {
                        value.coerceAtLeast(target)
                    }
                val delta = coercedValue - lastValue
                val consumed = scrollBy(delta)
                lastValue = coercedValue
                velocityLeft = this.velocity

                val current = currentItemInfo
                if (current == null) {
                    cancelAnimation()
                    return@animateTo
                }

                if (scrolledPastItem(initialVelocity, current, index, scrollOffset)) {
                    // If we've scrolled to/past the item, stop the animation. We may also need to
                    // 'snap back' to the item as we may have scrolled past it
                    scrollBy(lazyListState.calculateScrollOffsetToItem(index).toFloat())
                    cancelAnimation()
                } else if (abs(delta - consumed) > 0.5f) {
                    // avoid rounding errors and stop if anything is unconsumed
                    cancelAnimation()
                }
            }
        animationTarget = null
        return velocityLeft
    }

    private fun LazyListState.calculateScrollOffsetToItem(index: Int): Int {
        return layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: 0
    }

    private val currentItemInfo: LazyListItemInfo?
        get() =
            lazyListState.layoutInfo.visibleItemsInfo
                .asSequence()
                .filter { it.offset <= 0 && it.offset + it.size > 0 }
                .lastOrNull()
}

private fun scrolledPastItem(
    initialVelocity: Float,
    currentItem: LazyListItemInfo,
    targetIndex: Int,
    targetScrollOffset: Int = 0,
): Boolean {
    return if (initialVelocity > 0) {
        // forward
        currentItem.index > targetIndex ||
            (currentItem.index == targetIndex && currentItem.offset <= targetScrollOffset)
    } else {
        // backwards
        currentItem.index < targetIndex ||
            (currentItem.index == targetIndex && currentItem.offset >= targetScrollOffset)
    }
}

private fun DecayAnimationSpec<Float>.canFlingPastCurrentItem(
    currentItem: LazyListItemInfo,
    initialVelocity: Float,
): Boolean {
    val targetValue =
        calculateTargetValue(
            initialValue = currentItem.offset.toFloat(),
            initialVelocity = initialVelocity,
        )
    return when {
        // forward. We add 10% onto the size to cater for any item spacing
        initialVelocity > 0 -> targetValue <= -(currentItem.size * 1.1f)
        // backwards. We add 10% onto the size to cater for any item spacing
        else -> targetValue >= (currentItem.size * 0.1f)
    }
}
