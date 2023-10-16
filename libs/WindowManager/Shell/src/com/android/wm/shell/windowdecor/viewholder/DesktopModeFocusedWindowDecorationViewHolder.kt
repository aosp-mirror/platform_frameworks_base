package com.android.wm.shell.windowdecor.viewholder

import android.app.ActivityManager.RunningTaskInfo
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
import com.android.wm.shell.R

/**
 * A desktop mode window decoration used when the window is in full "focus" (i.e. fullscreen). It
 * hosts a simple handle bar from which to initiate a drag motion to enter desktop mode.
 */
internal class DesktopModeFocusedWindowDecorationViewHolder(
        rootView: View,
        onCaptionTouchListener: View.OnTouchListener,
        onCaptionButtonClickListener: View.OnClickListener
) : DesktopModeWindowDecorationViewHolder(rootView) {

    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: ImageButton = rootView.requireViewById(R.id.caption_handle)

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnClickListener(onCaptionButtonClickListener)
    }

    override fun bindData(taskInfo: RunningTaskInfo) {
        taskInfo.taskDescription?.statusBarColor?.let { captionColor ->
            val captionDrawable = captionView.background as GradientDrawable
            captionDrawable.setColor(captionColor)
        }

        captionHandle.imageTintList = ColorStateList.valueOf(getCaptionHandleBarColor(taskInfo))
    }

    private fun getCaptionHandleBarColor(taskInfo: RunningTaskInfo): Int {
        return if (shouldUseLightCaptionColors(taskInfo)) {
            context.getColor(R.color.desktop_mode_caption_handle_bar_light)
        } else {
            context.getColor(R.color.desktop_mode_caption_handle_bar_dark)
        }
    }
}
