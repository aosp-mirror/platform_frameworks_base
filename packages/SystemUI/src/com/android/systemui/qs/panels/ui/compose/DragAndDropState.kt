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

package com.android.systemui.qs.panels.ui.compose

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.toRect
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec

/** Holds the [TileSpec] of the tile being moved and receives drag and drop events. */
interface DragAndDropState {
    val draggedCell: SizedTile<EditTileViewModel>?
    val dragInProgress: Boolean

    fun isMoving(tileSpec: TileSpec): Boolean

    fun onStarted(cell: SizedTile<EditTileViewModel>)

    fun onMoved(target: Int, insertAfter: Boolean)

    fun movedOutOfBounds()

    fun onDrop()
}

/**
 * Registers a composable as a [DragAndDropTarget] to receive drop events. Use this outside the tile
 * grid to catch out of bounds drops.
 *
 * @param dragAndDropState The [DragAndDropState] using the tiles list
 * @param onDrop Action to be executed when a [TileSpec] is dropped on the composable
 */
@Composable
fun Modifier.dragAndDropRemoveZone(
    dragAndDropState: DragAndDropState,
    onDrop: (TileSpec) -> Unit,
): Modifier {
    val target =
        remember(dragAndDropState) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    return dragAndDropState.draggedCell?.let {
                        onDrop(it.tile.tileSpec)
                        dragAndDropState.onDrop()
                        true
                    } ?: false
                }

                override fun onEntered(event: DragAndDropEvent) {
                    dragAndDropState.movedOutOfBounds()
                }
            }
        }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.mimeTypes().contains(QsDragAndDrop.TILESPEC_MIME_TYPE)
        },
        target = target,
    )
}

/**
 * Registers a tile list as a [DragAndDropTarget] to receive drop events. Use this on the lazy tile
 * grid to receive drag and drops events.
 *
 * @param gridState The [LazyGridState] of the tile list
 * @param contentOffset The [Offset] of the tile list
 * @param dragAndDropState The [DragAndDropState] using the tiles list
 * @param onDrop Callback when a tile is dropped
 */
@Composable
fun Modifier.dragAndDropTileList(
    gridState: LazyGridState,
    contentOffset: () -> Offset,
    dragAndDropState: DragAndDropState,
    onDrop: (TileSpec) -> Unit,
): Modifier {
    val target =
        remember(dragAndDropState) {
            object : DragAndDropTarget {
                override fun onEnded(event: DragAndDropEvent) {
                    dragAndDropState.onDrop()
                }

                override fun onMoved(event: DragAndDropEvent) {
                    // Drag offset relative to the list's top left corner
                    val relativeDragOffset = event.dragOffsetRelativeTo(contentOffset())
                    val targetItem =
                        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            // Check if the drag is on this item
                            IntRect(item.offset, item.size).toRect().contains(relativeDragOffset)
                        }

                    targetItem?.let {
                        dragAndDropState.onMoved(it.index, insertAfter(it, relativeDragOffset))
                    }
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    return dragAndDropState.draggedCell?.let {
                        onDrop(it.tile.tileSpec)
                        dragAndDropState.onDrop()
                        true
                    } ?: false
                }
            }
        }
    return dragAndDropTarget(
        target = target,
        shouldStartDragAndDrop = { event ->
            event.mimeTypes().contains(QsDragAndDrop.TILESPEC_MIME_TYPE)
        },
    )
}

private fun DragAndDropEvent.dragOffsetRelativeTo(offset: Offset): Offset {
    return toAndroidDragEvent().run { Offset(x, y) } - offset
}

private fun insertAfter(item: LazyGridItemInfo, offset: Offset): Boolean {
    // We want to insert the tile after the target if we're aiming at the right side of a large tile
    // TODO(ostonge): Verify this behavior in RTL
    val itemCenter = item.offset + item.size.center
    return item.span != 1 && offset.x > itemCenter.x
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.dragAndDropTileSource(
    sizedTile: SizedTile<EditTileViewModel>,
    dragAndDropState: DragAndDropState,
    onDragStart: () -> Unit,
): Modifier {
    val dragState by rememberUpdatedState(dragAndDropState)
    @Suppress("DEPRECATION") // b/368361871
    return dragAndDropSource(
        block = {
            detectDragGesturesAfterLongPress(
                onDrag = { _, _ -> },
                onDragStart = {
                    dragState.onStarted(sizedTile)
                    onDragStart()

                    // The tilespec from the ClipData transferred isn't actually needed as we're
                    // moving a tile within the same application. We're using a custom MIME type to
                    // limit the drag event to QS.
                    startTransfer(
                        DragAndDropTransferData(
                            ClipData(
                                QsDragAndDrop.CLIPDATA_LABEL,
                                arrayOf(QsDragAndDrop.TILESPEC_MIME_TYPE),
                                ClipData.Item(sizedTile.tile.tileSpec.spec),
                            )
                        )
                    )
                },
            )
        }
    )
}

private object QsDragAndDrop {
    const val CLIPDATA_LABEL = "tilespec"
    const val TILESPEC_MIME_TYPE = "qstile/tilespec"
}
