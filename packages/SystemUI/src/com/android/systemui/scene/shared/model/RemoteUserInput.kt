package com.android.systemui.scene.shared.model

import android.view.MotionEvent

/** A representation of user input that is used by the scene framework. */
data class RemoteUserInput(
    val x: Float,
    val y: Float,
    val action: RemoteUserInputAction,
) {
    companion object {
        fun translateMotionEvent(event: MotionEvent): RemoteUserInput {
            return RemoteUserInput(
                x = event.x,
                y = event.y,
                action =
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> RemoteUserInputAction.DOWN
                        MotionEvent.ACTION_MOVE -> RemoteUserInputAction.MOVE
                        MotionEvent.ACTION_UP -> RemoteUserInputAction.UP
                        MotionEvent.ACTION_CANCEL -> RemoteUserInputAction.CANCEL
                        else -> RemoteUserInputAction.UNKNOWN
                    }
            )
        }
    }
}

enum class RemoteUserInputAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
    UNKNOWN,
}
