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

package com.android.compose.animation.scene.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrain

/**
 * An object to make one or more destination node be the same size as a source node.
 *
 * Important: Most of the time, you should not use this class and instead use `Box` together with
 * `Modifier.matchParentSize()`. Use this only if you need to use both `Modifier.element()` and
 * `Modifier.matchParentSize()` (see b/347910697 for details).
 *
 * Example:
 * ```
 * Box {
 *     val sizeMatcher = remember { SizeMatcher() }
 *
 *     // The content.
 *     Content(Modifier.sizeMatcherSource(sizeMatcher))
 *
 *     // The background. Note that this has to be composed after Content() so that it
 *     // is measured after it. We set its zIndex to -1 so that it is still placed
 *     // (drawn) before/below it. We don't use BoxScope.matchParentSize() because it
 *     // does not play well with Modifier.element().
 *     Box(
 *         Modifier.zIndex(-1f)
 *             .element(Background)
 *             // Set the preferred size of this element.
 *             // Important: This must be *after* the Modifier.element() so that
 *             // Modifier.element() can override the size.
 *             .sizeMatcherDestination(sizeMatcher)
 *             .background(
 *                 MaterialTheme.colorScheme.primaryContainer,
 *                 RoundedCornerShape(32.dp),
 *             )
 *     )
 * }
 * ```
 *
 * @see sizeMatcherSource
 * @see sizeMatcherDestination
 */
class SizeMatcher {
    internal var source: LayoutModifierNode? = null
        set(value) {
            if (value != null && field != null && value != field) {
                error("Exactly one Modifier.sizeMatcherSource() should be specified")
            }
        }

    internal var destinations = mutableSetOf<LayoutModifierNode>()
    internal var sourceSize: IntSize = InvalidSize
        get() {
            if (field == InvalidSize) {
                error(
                    "SizeMatcher size was retrieved before it was set. You should make sure that " +
                        "all matcher destination are measured *after* the matcher source."
                )
            }
            return field
        }
        set(value) {
            if (value != field) {
                field = value
                destinations.forEach { it.invalidateMeasurement() }
            }
        }

    companion object {
        private val InvalidSize = IntSize(Int.MIN_VALUE, Int.MIN_VALUE)
    }
}

/**
 * Mark this node as the source of a [SizeMatcher].
 *
 * Important: There must be only a single source node associated to a [SizeMatcher] and it must be
 * measured before any destination.
 */
fun Modifier.sizeMatcherSource(matcher: SizeMatcher): Modifier {
    return this.then(SizeMatcherSourceNodeElement(matcher))
}

/**
 * Mark this node as the destination of a [SizeMatcher] so that its *preferred* size is the same
 * size as the source size.
 *
 * Important: Destination nodes must be measured *after* the source node, otherwise it might cause
 * crashes or 1-frame flickers. For most simple layouts (like Box, Row or Column), this usually
 * means that the destinations nodes must be composed *after* the source node. If doing so is
 * causing layering issues, you can use `Modifier.zIndex` to explicitly set the placement order of
 * your composables.
 */
fun Modifier.sizeMatcherDestination(matcher: SizeMatcher): Modifier {
    return this.then(SizeMatcherDestinationElement(matcher))
}

private data class SizeMatcherSourceNodeElement(private val matcher: SizeMatcher) :
    ModifierNodeElement<SizeMatcherSourceNode>() {
    override fun create(): SizeMatcherSourceNode = SizeMatcherSourceNode(matcher)

    override fun update(node: SizeMatcherSourceNode) {
        node.update(matcher)
    }
}

private class SizeMatcherSourceNode(private var matcher: SizeMatcher) :
    Modifier.Node(), LayoutModifierNode {
    override fun onAttach() {
        matcher.source = this
    }

    override fun onDetach() {
        matcher.source = null
    }

    fun update(matcher: SizeMatcher) {
        val previous = this.matcher
        this.matcher = matcher

        previous.source = null
        matcher.source = this
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        return measurable.measure(constraints).run {
            matcher.sourceSize = IntSize(width, height)
            layout(width, height) { place(0, 0) }
        }
    }
}

private data class SizeMatcherDestinationElement(private val matcher: SizeMatcher) :
    ModifierNodeElement<SizeMatcherDestinationNode>() {
    override fun create(): SizeMatcherDestinationNode = SizeMatcherDestinationNode(matcher)

    override fun update(node: SizeMatcherDestinationNode) {
        node.update(matcher)
    }
}

private class SizeMatcherDestinationNode(private var matcher: SizeMatcher) :
    Modifier.Node(), LayoutModifierNode {
    override fun onAttach() {
        this.matcher.destinations.add(this)
    }

    override fun onDetach() {
        this.matcher.destinations.remove(this)
    }

    fun update(matcher: SizeMatcher) {
        val previous = this.matcher
        this.matcher = matcher

        previous.destinations.remove(this)
        matcher.destinations.add(this)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val preferredSize = matcher.sourceSize
        val preferredConstraints = Constraints.fixed(preferredSize.width, preferredSize.height)

        // Make sure we still respect the incoming constraints.
        val placeable = measurable.measure(constraints.constrain(preferredConstraints))
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
