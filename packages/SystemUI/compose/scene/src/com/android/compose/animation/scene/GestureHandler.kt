package com.android.compose.animation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

interface DraggableHandler {
    fun onDragStarted(startedPosition: Offset, overSlop: Float, pointersDown: Int = 1)
    fun onDelta(pixels: Float)
    fun onDragStopped(velocity: Float)
}

interface NestedScrollHandler {
    val connection: NestedScrollConnection
}
