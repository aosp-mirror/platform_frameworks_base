package com.android.wm.shell.windowdecor

import android.animation.ValueAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceControl

/**
 * Creates an animator to shrink and position task after a user drags a fullscreen task from
 * the top of the screen to transition it into freeform and before the user releases the task. The
 * MoveToDesktopAnimator object also holds information about the state of the task that are
 * accessed by the EnterDesktopTaskTransitionHandler.
 */
class MoveToDesktopAnimator @JvmOverloads constructor(
        private val startBounds: Rect,
        private val taskInfo: RunningTaskInfo,
        private val taskSurface: SurfaceControl,
        private val transactionFactory: () -> SurfaceControl.Transaction =
                SurfaceControl::Transaction
) {
    companion object {
        // The size of the screen during drag relative to the fullscreen size
        const val DRAG_FREEFORM_SCALE: Float = 0.4f
        const val ANIMATION_DURATION = 336
    }

    private val animatedTaskWidth
        get() = dragToDesktopAnimator.animatedValue as Float * startBounds.width()
    private val dragToDesktopAnimator: ValueAnimator = ValueAnimator.ofFloat(1f,
            DRAG_FREEFORM_SCALE)
            .setDuration(ANIMATION_DURATION.toLong())
            .apply {
                val t = SurfaceControl.Transaction()
                addUpdateListener { animation ->
                    val animatorValue = animation.animatedValue as Float
                    t.setScale(taskSurface, animatorValue, animatorValue)
                            .apply()
                }
            }

    val taskId get() = taskInfo.taskId
    val position: PointF = PointF(0.0f, 0.0f)

    /**
     * Starts the animation that scales the task down.
     */
    fun startAnimation() {
        dragToDesktopAnimator.start()
    }

    /**
     * Uses the position of the motion event and the current scale of the task as defined by the
     * ValueAnimator to update the local position variable and set the task surface's position
     */
    fun updatePosition(ev: MotionEvent) {
        position.x = ev.x - animatedTaskWidth / 2
        position.y = ev.y

        val t = transactionFactory()
        t.setPosition(taskSurface, position.x, position.y)
        t.apply()
    }

    /**
     * Ends the animation, setting the scale and position to the final animation value
     */
    fun endAnimator() {
        dragToDesktopAnimator.end()
    }
}