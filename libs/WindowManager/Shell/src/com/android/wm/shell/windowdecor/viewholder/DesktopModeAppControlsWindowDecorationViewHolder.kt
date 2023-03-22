package com.android.wm.shell.windowdecor.viewholder

import android.app.ActivityManager.RunningTaskInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.Log
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
        onCaptionButtonClickListener: View.OnClickListener
) : DesktopModeWindowDecorationViewHolder(rootView) {

    private val captionView: View = rootView.findViewById(R.id.desktop_mode_caption)
    private val captionHandle: View = rootView.findViewById(R.id.caption_handle)
    private val openMenuButton: View = rootView.findViewById(R.id.open_menu_button)
    private val closeWindowButton: ImageButton = rootView.findViewById(R.id.close_window)
    private val expandMenuButton: ImageButton = rootView.findViewById(R.id.expand_menu_button)
    private val appNameTextView: TextView = rootView.findViewById(R.id.application_name)
    private val appIconImageView: ImageView = rootView.findViewById(R.id.application_icon)

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        openMenuButton.setOnClickListener(onCaptionButtonClickListener)
        closeWindowButton.setOnClickListener(onCaptionButtonClickListener)
    }

    override fun bindData(taskInfo: RunningTaskInfo) {
        bindAppInfo(taskInfo)

        val captionDrawable = captionView.background as GradientDrawable
        captionDrawable.setColor(taskInfo.taskDescription.statusBarColor)

        closeWindowButton.imageTintList = ColorStateList.valueOf(
                getCaptionCloseButtonColor(taskInfo))
        expandMenuButton.imageTintList = ColorStateList.valueOf(
                getCaptionExpandButtonColor(taskInfo))
        appNameTextView.setTextColor(getCaptionAppNameTextColor(taskInfo))
    }

    private fun bindAppInfo(taskInfo: RunningTaskInfo) {
        val packageName: String = taskInfo.realActivity.packageName
        val pm: PackageManager = context.applicationContext.packageManager
        try {
            // TODO(b/268363572): Use IconProvider or BaseIconCache to set drawable/name.
            val applicationInfo = pm.getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(0))
            appNameTextView.text = pm.getApplicationLabel(applicationInfo)
            appIconImageView.setImageDrawable(pm.getApplicationIcon(applicationInfo))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $packageName", e)
        }
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
