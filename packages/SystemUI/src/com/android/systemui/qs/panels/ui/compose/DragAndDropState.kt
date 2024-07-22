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
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec

@Composable
fun rememberDragAndDropState(listState: EditTileListState): DragAndDropState {
    val sourceSpec: MutableState<EditTileViewModel?> = remember { mutableStateOf(null) }
    return remember(listState) { DragAndDropState(sourceSpec, listState) }
}

/**
 * Holds the [TileSpec] of the tile being moved and modify the [EditTileListState] based on drag and
 * drop events.
 */
class DragAndDropState(
    val sourceSpec: MutableState<EditTileViewModel?>,
    private val listState: EditTileListState
) {
    val dragInProgress: Boolean
        get() = sourceSpec.value != null

    /** Returns index of the dragged tile if it's present in the list. Returns -1 if not. */
    fun currentPosition(): Int {
        return sourceSpec.value?.let { listState.indexOf(it.tileSpec) } ?: -1
    }

    fun isMoving(tileSpec: TileSpec): Boolean {
        return sourceSpec.value?.let { it.tileSpec == tileSpec } ?: false
    }

    fun onStarted(tile: EditTileViewModel) {
        sourceSpec.value = tile
    }

    fun onMoved(targetSpec: TileSpec) {
        sourceSpec.value?.let { listState.move(it, targetSpec) }
    }

    fun movedOutOfBounds() {
        // Removing the tiles from the current tile grid if it moves out of bounds. This clears
        // the spacer and makes it apparent that dropping the tile at that point would remove it.
        sourceSpec.value?.let { listState.remove(it.tileSpec) }
    }

    fun onDrop() {
        sourceSpec.value = null
    }
}

/**
 * Registers a tile as a [DragAndDropTarget] to receive drag events and update the
 * [DragAndDropState] with the tile's position, which can be used to insert a temporary placeholder.
 *
 * @param dragAndDropState The [DragAndDropState] using the tiles list
 * @param tileSpec The [TileSpec] of the tile
 * @param acceptDrops Whether the tile should accept a drop based on a given [TileSpec]
 * @param onDrop Action to be executed when a [TileSpec] is dropped on the tile
 */
@Composable
fun Modifier.dragAndDropTile(
    dragAndDropState: DragAndDropState,
    tileSpec: TileSpec,
    acceptDrops: (TileSpec) -> Boolean,
    onDrop: (TileSpec, Int) -> Unit,
): Modifier {
    val target =
        remember(dragAndDropState) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    return dragAndDropState.sourceSpec.value?.let {
                        onDrop(it.tileSpec, dragAndDropState.currentPosition())
                        dragAndDropState.onDrop()
                        true
                    } ?: false
                }

                override fun onEntered(event: DragAndDropEvent) {
                    dragAndDropState.onMoved(tileSpec)
                }
            }
        }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.mimeTypes().contains(QsDragAndDrop.TILESPEC_MIME_TYPE) &&
                dragAndDropState.sourceSpec.value?.let { acceptDrops(it.tileSpec) } ?: false
        },
        target = target,
    )
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
                    return dragAndDropState.sourceSpec.value?.let {
                        onDrop(it.tileSpec)
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
 * Registers a tile list as a [DragAndDropTarget] to receive drop events. Use this on list
 * containers to catch drops outside of tiles.
 *
 * @param dragAndDropState The [DragAndDropState] using the tiles list
 * @param acceptDrops Whether the tile should accept a drop based on a given [TileSpec]
 * @param onDrop Action to be executed when a [TileSpec] is dropped on the tile
 */
@Composable
fun Modifier.dragAndDropTileList(
    dragAndDropState: DragAndDropState,
    acceptDrops: (TileSpec) -> Boolean,
    onDrop: (TileSpec, Int) -> Unit,
): Modifier {
    val target =
        remember(dragAndDropState) {
            object : DragAndDropTarget {
                override fun onEnded(event: DragAndDropEvent) {
                    dragAndDropState.onDrop()
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    return dragAndDropState.sourceSpec.value?.let {
                        onDrop(it.tileSpec, dragAndDropState.currentPosition())
                        dragAndDropState.onDrop()
                        true
                    } ?: false
                }
            }
        }
    return dragAndDropTarget(
        target = target,
        shouldStartDragAndDrop = { event ->
            event.mimeTypes().contains(QsDragAndDrop.TILESPEC_MIME_TYPE) &&
                dragAndDropState.sourceSpec.value?.let { acceptDrops(it.tileSpec) } ?: false
        },
    )
}

fun Modifier.dragAndDropTileSource(
    tile: EditTileViewModel,
    onTap: (TileSpec) -> Unit,
    onDoubleTap: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState
): Modifier {
    return dragAndDropSource {
        detectTapGestures(
            onTap = { onTap(tile.tileSpec) },
            onDoubleTap = { onDoubleTap(tile.tileSpec) },
            onLongPress = {
                dragAndDropState.onStarted(tile)

                // The tilespec from the ClipData transferred isn't actually needed as we're moving
                // a tile within the same application. We're using a custom MIME type to limit the
                // drag event to QS.
                startTransfer(
                    DragAndDropTransferData(
                        ClipData(
                            QsDragAndDrop.CLIPDATA_LABEL,
                            arrayOf(QsDragAndDrop.TILESPEC_MIME_TYPE),
                            ClipData.Item(tile.tileSpec.spec)
                        )
                    )
                )
            }
        )
    }
}

private object QsDragAndDrop {
    const val CLIPDATA_LABEL = "tilespec"
    const val TILESPEC_MIME_TYPE = "qstile/tilespec"
}
