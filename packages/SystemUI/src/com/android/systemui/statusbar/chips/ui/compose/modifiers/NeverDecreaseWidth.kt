/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.ui.compose.modifiers

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain

/** A modifier that ensures the width of the content only increases and never decreases. */
fun Modifier.neverDecreaseWidth(): Modifier {
    return this.then(neverDecreaseWidthElement)
}

private data object neverDecreaseWidthElement : ModifierNodeElement<NeverDecreaseWidthNode>() {
    override fun create(): NeverDecreaseWidthNode {
        return NeverDecreaseWidthNode()
    }

    override fun update(node: NeverDecreaseWidthNode) {
        error("This should never be called")
    }
}

private class NeverDecreaseWidthNode : Modifier.Node(), LayoutModifierNode {
    private var minWidth = 0

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(Constraints(minWidth = minWidth).constrain(constraints))
        val width = placeable.width
        val height = placeable.height

        minWidth = maxOf(minWidth, width)

        return layout(width, height) { placeable.place(0, 0) }
    }
}
