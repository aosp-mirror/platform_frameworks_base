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

package com.android.systemui.qs.panels.ui.compose.selection

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.android.systemui.qs.panels.ui.compose.selection.ResizingState.ResizeOperation.FinalResizeOperation
import com.android.systemui.qs.panels.ui.compose.selection.ResizingState.ResizeOperation.TemporaryResizeOperation
import com.android.systemui.qs.pipeline.shared.TileSpec

@Composable
fun rememberResizingState(tileSpec: TileSpec, startsAsIcon: Boolean): ResizingState {
    return remember(tileSpec) { ResizingState(tileSpec, startsAsIcon) }
}

enum class QSDragAnchor {
    Icon,
    Large,
}

class ResizingState(tileSpec: TileSpec, startsAsIcon: Boolean) {
    val anchoredDraggableState =
        AnchoredDraggableState(if (startsAsIcon) QSDragAnchor.Icon else QSDragAnchor.Large)

    val bounds by derivedStateOf {
        anchoredDraggableState.anchors.minPosition().takeIf { !it.isNaN() } to
            anchoredDraggableState.anchors.maxPosition().takeIf { !it.isNaN() }
    }

    val temporaryResizeOperation by derivedStateOf {
        TemporaryResizeOperation(
            tileSpec,
            toIcon = anchoredDraggableState.currentValue == QSDragAnchor.Icon,
        )
    }

    val finalResizeOperation by derivedStateOf {
        FinalResizeOperation(
            tileSpec,
            toIcon = anchoredDraggableState.settledValue == QSDragAnchor.Icon,
        )
    }

    fun updateAnchors(min: Float, max: Float) {
        anchoredDraggableState.updateAnchors(
            DraggableAnchors {
                QSDragAnchor.Icon at min
                QSDragAnchor.Large at max
            }
        )
    }

    suspend fun updateCurrentValue(isIcon: Boolean) {
        anchoredDraggableState.animateTo(if (isIcon) QSDragAnchor.Icon else QSDragAnchor.Large)
    }

    suspend fun toggleCurrentValue() {
        val isIcon = anchoredDraggableState.currentValue == QSDragAnchor.Icon
        updateCurrentValue(!isIcon)
    }

    fun progress(): Float = anchoredDraggableState.progress(QSDragAnchor.Icon, QSDragAnchor.Large)

    /**
     * Represents a resizing operation for a tile.
     *
     * @property spec The tile's [TileSpec]
     * @property toIcon The new size for the tile.
     */
    sealed class ResizeOperation private constructor(val spec: TileSpec, val toIcon: Boolean) {
        /** A temporary resizing operation, used while a resizing movement is in motion. */
        class TemporaryResizeOperation(spec: TileSpec, toIcon: Boolean) :
            ResizeOperation(spec, toIcon)

        /** A final resizing operation, used while a resizing movement is done. */
        class FinalResizeOperation(spec: TileSpec, toIcon: Boolean) : ResizeOperation(spec, toIcon)
    }
}
