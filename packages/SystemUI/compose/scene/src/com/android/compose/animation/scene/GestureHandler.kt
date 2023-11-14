package com.android.compose.animation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.IntSize

interface DraggableHandler {
    fun onDragStarted(layoutSize: IntSize, startedPosition: Offset, pointersDown: Int = 1)
    fun onDelta(pixels: Float)
    fun onDragStopped(velocity: Float)
}

interface NestedScrollHandler {
    val connection: NestedScrollConnection
}
