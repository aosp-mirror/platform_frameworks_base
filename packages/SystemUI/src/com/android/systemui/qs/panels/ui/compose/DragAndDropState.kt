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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.android.systemui.qs.pipeline.shared.TileSpec

@Composable
fun rememberDragAndDropState(listState: EditTileListState): DragAndDropState {
    val sourceSpec: MutableState<TileSpec?> = remember { mutableStateOf(null) }
    return remember(listState) { DragAndDropState(sourceSpec, listState) }
}

/**
 * Holds the [TileSpec] of the tile being moved and modify the [EditTileListState] based on drag and
 * drop events.
 */
class DragAndDropState(
    val sourceSpec: MutableState<TileSpec?>,
    private val listState: EditTileListState
) {
    /** Returns index of the dragged tile if it's present in the list. Returns -1 if not. */
    fun currentPosition(): Int {
        return sourceSpec.value?.let { listState.indexOf(it) } ?: -1
    }

    fun isMoving(tileSpec: TileSpec): Boolean {
        return sourceSpec.value?.let { it == tileSpec } ?: false
    }

    fun onStarted(spec: TileSpec) {
        sourceSpec.value = spec
    }

    fun onMoved(targetSpec: TileSpec) {
        sourceSpec.value?.let { listState.move(it, targetSpec) }
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
                        onDrop(it, dragAndDropState.currentPosition())
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
                dragAndDropState.sourceSpec.value?.let { acceptDrops(it) } ?: false
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
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    return dragAndDropState.sourceSpec.value?.let {
                        onDrop(it, dragAndDropState.currentPosition())
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
                dragAndDropState.sourceSpec.value?.let { acceptDrops(it) } ?: false
        },
    )
}

fun Modifier.dragAndDropTileSource(
    tileSpec: TileSpec,
    onTap: (TileSpec) -> Unit,
    dragAndDropState: DragAndDropState
): Modifier {
    return dragAndDropSource {
        detectTapGestures(
            onTap = { onTap(tileSpec) },
            onLongPress = {
                dragAndDropState.onStarted(tileSpec)

                // The tilespec from the ClipData transferred isn't actually needed as we're moving
                // a tile within the same application. We're using a custom MIME type to limit the
                // drag event to QS.
                startTransfer(
                    DragAndDropTransferData(
                        ClipData(
                            QsDragAndDrop.CLIPDATA_LABEL,
                            arrayOf(QsDragAndDrop.TILESPEC_MIME_TYPE),
                            ClipData.Item(tileSpec.spec)
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
