package com.android.wm.shell.windowdecor

import android.animation.ValueAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceControl
import com.android.internal.policy.ScreenDecorationsUtils

/**
 * Creates an animator to shrink and position task after a user drags a fullscreen task from
 * the top of the screen to transition it into freeform and before the user releases the task. The
 * MoveToDesktopAnimator object also holds information about the state of the task that are
 * accessed by the EnterDesktopTaskTransitionHandler.
 */
class MoveToDesktopAnimator @JvmOverloads constructor(
        private val context: Context,
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
                val cornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)
                addUpdateListener { animation ->
                    val animatorValue = animation.animatedValue as Float
                    t.setScale(taskSurface, animatorValue, animatorValue)
                            .setCornerRadius(taskSurface, cornerRadius)
                            .apply()
                }
            }

    val taskId get() = taskInfo.taskId
    val position: PointF = PointF(0.0f, 0.0f)

    /**
     * Whether motion events from the drag gesture should affect the dragged surface or not. Used
     * to disallow moving the surface's position prematurely since it should not start moving at
     * all until the drag-to-desktop transition is ready to animate and the wallpaper/home are
     * ready to be revealed behind the dragged/scaled task.
     */
    private var allowSurfaceChangesOnMove = false

    /**
     * Starts the animation that scales the task down.
     */
    fun startAnimation() {
        allowSurfaceChangesOnMove = true
        dragToDesktopAnimator.start()
    }

    /**
     * Uses the position of the motion event of the drag-to-desktop gesture to update the dragged
     * task's position on screen to follow the touch point. Note that the position change won't
     * be applied immediately always, such as near the beginning where it waits until the wallpaper
     * or home are visible behind it. Once they're visible the surface will catch-up to the most
     * recent touch position.
     */
    fun updatePosition(ev: MotionEvent) {
        // Using rawX/Y because when dragging a task in split, the local X/Y is relative to the
        // split stages, but the split task surface is re-parented to the task display area to
        // allow dragging beyond its stage across any region of the display. Because of that, the
        // rawX/Y are more true to where the gesture is on screen and where the surface should be
        // positioned.
        position.x = ev.rawX - animatedTaskWidth / 2
        position.y = ev.rawY

        if (!allowSurfaceChangesOnMove) {
            return
        }

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