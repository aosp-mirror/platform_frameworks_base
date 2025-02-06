/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.compose.gesture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity

/**
 * Update [state] and disallow outer scroll after a child node consumed a non-zero scroll amount
 * before reaching its [bounds], so that the child is overscrolled instead of letting the outer
 * scrollable(s) consume the extra scroll.
 *
 * Example:
 * ```
 * val nestedScrollControlState = remember { NestedScrollControlState() }
 * Column(
 *     Modifier
 *         // Note: Any scrollable/draggable parent should use nestedScrollControlState to
 *         // enable/disable themselves.
 *         .verticalScroll(
 *             rememberScrollState(),
 *             enabled = nestedScrollControlState.isOuterScrollAllowed,
 *         )
 * ) {
 *     Column(
 *         Modifier
 *             .nestedScrollController(nestedScrollControlState)
 *             .verticalScroll(rememberScrollState())
 *     ) { ...}
 * }
 * ```
 */
fun Modifier.nestedScrollController(
    state: NestedScrollControlState,
    bounds: NestedScrollableBound = NestedScrollableBound.Any,
): Modifier {
    return this.then(NestedScrollControllerElement(state, bounds))
}

/**
 * A state that should be used by outer scrollables to disable themselves so that nested scrollables
 * will overscroll when reaching their bounds.
 *
 * @see nestedScrollController
 */
class NestedScrollControlState {
    var isOuterScrollAllowed by mutableStateOf(true)
        internal set
}

/**
 * Specifies when to disable outer scroll after reaching the bounds of a nested scrollable.
 *
 * @see nestedScrollController
 */
enum class NestedScrollableBound {
    /** Disable after reaching any of the scrollable bounds. */
    Any,

    /** Disable after reaching the top (left) bound when scrolling vertically (horizontally). */
    TopLeft,

    /** Disable after reaching the bottom (right) bound when scrolling vertically (horizontally). */
    BottomRight;

    companion object {
        /**
         * Disable after reaching the left (right) bound when scrolling horizontally in a LTR (RTL)
         * layout.
         */
        val Start: NestedScrollableBound
            @Composable
            get() =
                when (LocalLayoutDirection.current) {
                    LayoutDirection.Ltr -> TopLeft
                    LayoutDirection.Rtl -> BottomRight
                }

        /**
         * Disable after reaching the right (left) bound when scrolling horizontally in a LTR (RTL)
         * layout.
         */
        val End: NestedScrollableBound
            @Composable
            get() =
                when (LocalLayoutDirection.current) {
                    LayoutDirection.Ltr -> BottomRight
                    LayoutDirection.Rtl -> TopLeft
                }
    }
}

private data class NestedScrollControllerElement(
    private val state: NestedScrollControlState,
    private val bounds: NestedScrollableBound,
) : ModifierNodeElement<NestedScrollControllerNode>() {
    override fun create(): NestedScrollControllerNode {
        return NestedScrollControllerNode(state, bounds)
    }

    override fun update(node: NestedScrollControllerNode) {
        node.update(state, bounds)
    }
}

private class NestedScrollControllerNode(
    private var state: NestedScrollControlState,
    private var bounds: NestedScrollableBound,
) : DelegatingNode(), NestedScrollConnection {
    private var childrenConsumedAnyScroll = false

    init {
        delegate(nestedScrollModifierNode(this, dispatcher = null))
    }

    override fun onDetach() {
        state.isOuterScrollAllowed = true
    }

    fun update(controller: NestedScrollControlState, bounds: NestedScrollableBound) {
        if (controller != this.state) {
            controller.isOuterScrollAllowed = this.state.isOuterScrollAllowed
            this.state.isOuterScrollAllowed = true
            this.state = controller
        }

        this.bounds = bounds
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (hasConsumedScrollInBounds(consumed.x) || hasConsumedScrollInBounds(consumed.y)) {
            childrenConsumedAnyScroll = true
        }

        if (!childrenConsumedAnyScroll) {
            state.isOuterScrollAllowed = true
        } else {
            state.isOuterScrollAllowed = false
        }

        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        childrenConsumedAnyScroll = false
        state.isOuterScrollAllowed = true
        return super.onPostFling(consumed, available)
    }

    private fun hasConsumedScrollInBounds(consumed: Float): Boolean {
        return when {
            consumed < 0f ->
                bounds == NestedScrollableBound.Any || bounds == NestedScrollableBound.BottomRight

            consumed > 0f ->
                bounds == NestedScrollableBound.Any || bounds == NestedScrollableBound.TopLeft

            else -> false
        }
    }
}
