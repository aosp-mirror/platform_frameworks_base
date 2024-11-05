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
fun Modifier.notificationScrimClip(clipParams: () -> NotificationScrimClipParams): Modifier {
    return this then NotificationScrimClipElement(clipParams)
}

private class NotificationScrimClipNode(var clipParams: () -> NotificationScrimClipParams) :
    DrawModifierNode, Modifier.Node() {
    private val path = Path()

    private var lastClipParams = NotificationScrimClipParams()

    override fun ContentDrawScope.draw() {
        val newClipParams = clipParams()
        if (newClipParams != lastClipParams) {
            lastClipParams = newClipParams
            applyClipParams(path, lastClipParams)
        }
        clipPath(path, ClipOp.Difference) { this@draw.drawContent() }
    }

    private fun ContentDrawScope.applyClipParams(
        path: Path,
        clipParams: NotificationScrimClipParams,
    ) {
        with(clipParams) {
            path.rewind()
            path
                .asAndroidPath()
                .addRoundRect(
                    -leftInset.toFloat(),
                    top.toFloat(),
                    size.width + rightInset,
                    bottom.toFloat(),
                    radius.toFloat(),
                    radius.toFloat(),
                    android.graphics.Path.Direction.CW,
                )
        }
    }
}

private data class NotificationScrimClipElement(val clipParams: () -> NotificationScrimClipParams) :
    ModifierNodeElement<NotificationScrimClipNode>() {
    override fun create(): NotificationScrimClipNode {
        return NotificationScrimClipNode(clipParams)
    }

    override fun update(node: NotificationScrimClipNode) {
        node.clipParams = clipParams
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "notificationScrimClip"
        with(clipParams()) {
            properties["leftInset"] = leftInset
            properties["top"] = top
            properties["rightInset"] = rightInset
            properties["bottom"] = bottom
            properties["radius"] = radius
        }
    }
}

/** Params for [notificationScrimClip]. */
data class NotificationScrimClipParams(
    val top: Int = 0,
    val bottom: Int = 0,
    val leftInset: Int = 0,
    val rightInset: Int = 0,
    val radius: Int = 0,
)
