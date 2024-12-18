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

package com.android.systemui.notifications.ui.composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView

/**
 * Modify element, which updates the height to the height of current top heads up notification, or
 * to 0 if there is none.
 *
 * @param view Notification stack scroll view
 */
fun Modifier.notificationHeadsUpHeight(view: NotificationScrollView) =
    this then HeadsUpLayoutElement(view)

private data class HeadsUpLayoutElement(
    val view: NotificationScrollView,
) : ModifierNodeElement<HeadsUpLayoutNode>() {

    override fun create(): HeadsUpLayoutNode = HeadsUpLayoutNode(view)

    override fun update(node: HeadsUpLayoutNode) {
        check(view == node.view) { "Trying to reuse the node with a new View." }
    }
}

private class HeadsUpLayoutNode(val view: NotificationScrollView) :
    LayoutModifierNode, Modifier.Node() {

    private val headsUpHeightChangedListener = Runnable { invalidateMeasureIfAttached() }

    override fun onAttach() {
        super.onAttach()
        view.addHeadsUpHeightChangedListener(headsUpHeightChangedListener)
    }

    override fun onDetach() {
        super.onDetach()
        view.removeHeadsUpHeightChangedListener(headsUpHeightChangedListener)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        // TODO(b/339181697) make sure, that the row is already measured.
        val contentHeight = view.topHeadsUpHeight
        val placeable =
            measurable.measure(
                constraints.copy(minHeight = contentHeight, maxHeight = contentHeight)
            )
        return layout(placeable.width, placeable.height) { placeable.place(IntOffset.Zero) }
    }

    override fun toString(): String {
        return "HeadsUpLayoutNode(view=$view)"
    }

    fun invalidateMeasureIfAttached() {
        if (isAttached) {
            this.invalidateMeasurement()
        }
    }
}
