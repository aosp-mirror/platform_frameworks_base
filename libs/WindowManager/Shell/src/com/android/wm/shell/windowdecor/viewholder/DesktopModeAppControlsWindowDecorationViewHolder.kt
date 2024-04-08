package com.android.wm.shell.windowdecor.viewholder

import android.app.ActivityManager.RunningTaskInfo
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.android.wm.shell.R

/**
 * A desktop mode window decoration used when the window is floating (i.e. freeform). It hosts
 * finer controls such as a close window button and an "app info" section to pull up additional
 * controls.
 */
internal class DesktopModeAppControlsWindowDecorationViewHolder(
        rootView: View,
        onCaptionTouchListener: View.OnTouchListener,
        onCaptionButtonClickListener: View.OnClickListener,
        appName: CharSequence,
        appIcon: Drawable
) : DesktopModeWindowDecorationViewHolder(rootView) {

    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: View = rootView.requireViewById(R.id.caption_handle)
    private val openMenuButton: View = rootView.requireViewById(R.id.open_menu_button)
    private val closeWindowButton: ImageButton = rootView.requireViewById(R.id.close_window)
    private val expandMenuButton: ImageButton = rootView.requireViewById(R.id.expand_menu_button)
    private val appNameTextView: TextView = rootView.requireViewById(R.id.application_name)
    private val appIconImageView: ImageView = rootView.requireViewById(R.id.application_icon)

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        openMenuButton.setOnClickListener(onCaptionButtonClickListener)
        openMenuButton.setOnTouchListener(onCaptionTouchListener)
        closeWindowButton.setOnClickListener(onCaptionButtonClickListener)
        closeWindowButton.setOnTouchListener(onCaptionTouchListener)
        appNameTextView.text = appName
        appIconImageView.setImageDrawable(appIcon)
    }

    override fun bindData(taskInfo: RunningTaskInfo) {

        val captionDrawable = captionView.background as GradientDrawable
        taskInfo.taskDescription?.statusBarColor?.let {
            captionDrawable.setColor(it)
        }

        closeWindowButton.imageTintList = ColorStateList.valueOf(
                getCaptionCloseButtonColor(taskInfo))
        expandMenuButton.imageTintList = ColorStateList.valueOf(
                getCaptionExpandButtonColor(taskInfo))
        appNameTextView.setTextColor(getCaptionAppNameTextColor(taskInfo))
    }

    private fun getCaptionAppNameTextColor(taskInfo: RunningTaskInfo): Int {
        return if (shouldUseLightCaptionColors(taskInfo)) {
            context.getColor(R.color.desktop_mode_caption_app_name_light)
        } else {
            context.getColor(R.color.desktop_mode_caption_app_name_dark)
        }
    }

    private fun getCaptionCloseButtonColor(taskInfo: RunningTaskInfo): Int {
        return if (shouldUseLightCaptionColors(taskInfo)) {
            context.getColor(R.color.desktop_mode_caption_close_button_light)
        } else {
            context.getColor(R.color.desktop_mode_caption_close_button_dark)
        }
    }

    private fun getCaptionExpandButtonColor(taskInfo: RunningTaskInfo): Int {
        return if (shouldUseLightCaptionColors(taskInfo)) {
            context.getColor(R.color.desktop_mode_caption_expand_button_light)
        } else {
            context.getColor(R.color.desktop_mode_caption_expand_button_dark)
        }
    }

    companion object {
        private const val TAG = "DesktopModeAppControlsWindowDecorationViewHolder"
    }
}
