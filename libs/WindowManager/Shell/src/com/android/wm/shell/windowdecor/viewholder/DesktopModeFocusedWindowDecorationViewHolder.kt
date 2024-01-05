package com.android.wm.shell.windowdecor.viewholder

import android.animation.ObjectAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.view.View
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.widget.ImageButton
import com.android.wm.shell.R
import com.android.wm.shell.animation.Interpolators

/**
 * A desktop mode window decoration used when the window is in full "focus" (i.e. fullscreen). It
 * hosts a simple handle bar from which to initiate a drag motion to enter desktop mode.
 */
internal class DesktopModeFocusedWindowDecorationViewHolder(
        rootView: View,
        onCaptionTouchListener: View.OnTouchListener,
        onCaptionButtonClickListener: View.OnClickListener
) : DesktopModeWindowDecorationViewHolder(rootView) {

    companion object {
        private const val CAPTION_HANDLE_ANIMATION_DURATION: Long = 100
    }

    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: ImageButton = rootView.requireViewById(R.id.caption_handle)

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnClickListener(onCaptionButtonClickListener)
    }

    override fun bindData(taskInfo: RunningTaskInfo) {
        captionHandle.imageTintList = ColorStateList.valueOf(getCaptionHandleBarColor(taskInfo))
    }

    override fun onHandleMenuOpened() {
        animateCaptionHandleAlpha(startValue = 1f, endValue = 0f)
    }

    override fun onHandleMenuClosed() {
        animateCaptionHandleAlpha(startValue = 0f, endValue = 1f)
    }

    /**
     * Returns true if input point is in the caption's view.
     * @param inputPoint the input point relative to the task in full "focus" (i.e. fullscreen).
     */
    fun pointInCaption(inputPoint: PointF, captionX: Int): Boolean {
        return inputPoint.x >= captionX &&
                inputPoint.x <= captionX + captionView.width &&
                inputPoint.y >= 0 &&
                inputPoint.y <= captionView.height
    }

    private fun getCaptionHandleBarColor(taskInfo: RunningTaskInfo): Int {
        return if (shouldUseLightCaptionColors(taskInfo)) {
            context.getColor(R.color.desktop_mode_caption_handle_bar_light)
        } else {
            context.getColor(R.color.desktop_mode_caption_handle_bar_dark)
        }
    }

    /**
     * Whether the caption items should use the 'light' color variant so that there's good contrast
     * with the caption background color.
     */
    private fun shouldUseLightCaptionColors(taskInfo: RunningTaskInfo): Boolean {
        return taskInfo.taskDescription
            ?.let { taskDescription ->
                if (Color.alpha(taskDescription.statusBarColor) != 0 &&
                    taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
                    Color.valueOf(taskDescription.statusBarColor).luminance() < 0.5
                } else {
                    taskDescription.statusBarAppearance and APPEARANCE_LIGHT_STATUS_BARS == 0
                }
            } ?: false
    }

    /** Animate appearance/disappearance of caption handle as the handle menu is animated. */
    private fun animateCaptionHandleAlpha(startValue: Float, endValue: Float) {
        val animator =
            ObjectAnimator.ofFloat(captionHandle, View.ALPHA, startValue, endValue).apply {
                duration = CAPTION_HANDLE_ANIMATION_DURATION
                interpolator = Interpolators.FAST_OUT_SLOW_IN
            }
        animator.start()
    }
}
