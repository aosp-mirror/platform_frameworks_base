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

package com.android.systemui.qs.composefragment.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Clipping modifier for clipping out the notification scrim as it slides over QS. It will clip out
 * ([ClipOp.Difference]) a `RoundRect(-leftInset, top, width + rightInset, bottom, radius, radius)`
 * from the QS container.
 */
fun Modifier.notificationScrimClip(
    leftInset: Int,
    top: Int,
    rightInset: Int,
    bottom: Int,
    radius: Int
): Modifier {
    return this then NotificationScrimClipElement(leftInset, top, rightInset, bottom, radius)
}

private class NotificationScrimClipNode(
    var leftInset: Float,
    var top: Float,
    var rightInset: Float,
    var bottom: Float,
    var radius: Float,
) : DrawModifierNode, Modifier.Node() {
    private val path = Path()

    var invalidated = true

    override fun ContentDrawScope.draw() {
        if (invalidated) {
            path.rewind()
            path
                .asAndroidPath()
                .addRoundRect(
                    -leftInset,
                    top,
                    size.width + rightInset,
                    bottom,
                    radius,
                    radius,
                    android.graphics.Path.Direction.CW
                )
            invalidated = false
        }
        clipPath(path, ClipOp.Difference) { this@draw.drawContent() }
    }
}

private data class NotificationScrimClipElement(
    val leftInset: Int,
    val top: Int,
    val rightInset: Int,
    val bottom: Int,
    val radius: Int,
) : ModifierNodeElement<NotificationScrimClipNode>() {
    override fun create(): NotificationScrimClipNode {
        return NotificationScrimClipNode(
            leftInset.toFloat(),
            top.toFloat(),
            rightInset.toFloat(),
            bottom.toFloat(),
            radius.toFloat(),
        )
    }

    override fun update(node: NotificationScrimClipNode) {
        val changed =
            node.leftInset != leftInset.toFloat() ||
                node.top != top.toFloat() ||
                node.rightInset != rightInset.toFloat() ||
                node.bottom != bottom.toFloat() ||
                node.radius != radius.toFloat()
        if (changed) {
            node.leftInset = leftInset.toFloat()
            node.top = top.toFloat()
            node.rightInset = rightInset.toFloat()
            node.bottom = bottom.toFloat()
            node.radius = radius.toFloat()
            node.invalidated = true
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "notificationScrimClip"
        properties["leftInset"] = leftInset
        properties["top"] = top
        properties["rightInset"] = rightInset
        properties["bottom"] = bottom
        properties["radius"] = radius
    }
}
