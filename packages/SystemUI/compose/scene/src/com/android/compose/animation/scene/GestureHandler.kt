package com.android.compose.animation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import kotlinx.coroutines.CoroutineScope

interface GestureHandler {
    val draggable: DraggableHandler
    val nestedScroll: NestedScrollHandler
}

interface DraggableHandler {
    suspend fun onDragStarted(coroutineScope: CoroutineScope, startedPosition: Offset)
    fun onDelta(pixels: Float)
    suspend fun onDragStopped(coroutineScope: CoroutineScope, velocity: Float)
}

interface NestedScrollHandler {
    val connection: NestedScrollConnection
}
