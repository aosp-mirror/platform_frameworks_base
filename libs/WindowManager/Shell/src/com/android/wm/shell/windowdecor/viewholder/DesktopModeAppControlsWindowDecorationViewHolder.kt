package com.android.wm.shell.windowdecor.viewholder

import android.annotation.ColorInt
import android.app.ActivityManager.RunningTaskInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import com.android.internal.R.attr.materialColorOnSecondaryContainer
import com.android.internal.R.attr.materialColorOnSurface
import com.android.internal.R.attr.materialColorSecondaryContainer
import com.android.internal.R.attr.materialColorSurfaceContainerHigh
import com.android.internal.R.attr.materialColorSurfaceContainerLow
import com.android.internal.R.attr.materialColorSurfaceDim
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
        onLongClickListener: OnLongClickListener,
        appName: CharSequence,
        appIconBitmap: Bitmap
) : DesktopModeWindowDecorationViewHolder(rootView) {

    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: View = rootView.requireViewById(R.id.caption_handle)
    private val openMenuButton: View = rootView.requireViewById(R.id.open_menu_button)
    private val closeWindowButton: ImageButton = rootView.requireViewById(R.id.close_window)
    private val expandMenuButton: ImageButton = rootView.requireViewById(R.id.expand_menu_button)
    private val maximizeWindowButton: ImageButton = rootView.requireViewById(R.id.maximize_window)
    private val appNameTextView: TextView = rootView.requireViewById(R.id.application_name)
    private val appIconImageView: ImageView = rootView.requireViewById(R.id.application_icon)

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        openMenuButton.setOnClickListener(onCaptionButtonClickListener)
        openMenuButton.setOnTouchListener(onCaptionTouchListener)
        closeWindowButton.setOnClickListener(onCaptionButtonClickListener)
        maximizeWindowButton.setOnClickListener(onCaptionButtonClickListener)
        maximizeWindowButton.setOnTouchListener(onCaptionTouchListener)
        maximizeWindowButton.onLongClickListener = onLongClickListener
        closeWindowButton.setOnTouchListener(onCaptionTouchListener)
        appNameTextView.text = appName
        appIconImageView.setImageBitmap(appIconBitmap)
    }

    override fun bindData(taskInfo: RunningTaskInfo) {
        captionView.setBackgroundColor(getCaptionBackgroundColor(taskInfo))
        val color = getAppNameAndButtonColor(taskInfo)
        val alpha = Color.alpha(color)
        closeWindowButton.imageTintList = ColorStateList.valueOf(color)
        maximizeWindowButton.imageTintList = ColorStateList.valueOf(color)
        expandMenuButton.imageTintList = ColorStateList.valueOf(color)
        appNameTextView.setTextColor(color)
        appIconImageView.imageAlpha = alpha
        maximizeWindowButton.imageAlpha = alpha
        closeWindowButton.imageAlpha = alpha
        expandMenuButton.imageAlpha = alpha
    }

    override fun onHandleMenuOpened() {}

    override fun onHandleMenuClosed() {}

    @ColorInt
    private fun getCaptionBackgroundColor(taskInfo: RunningTaskInfo): Int {
        val materialColorAttr: Int =
            if (isDarkMode()) {
                if (!taskInfo.isFocused) {
                    materialColorSurfaceContainerHigh
                } else {
                    materialColorSurfaceDim
                }
            } else {
                if (!taskInfo.isFocused) {
                    materialColorSurfaceContainerLow
                } else {
                    materialColorSecondaryContainer
                }
        }
        context.withStyledAttributes(null, intArrayOf(materialColorAttr), 0, 0) {
            return getColor(0, 0)
        }
        return 0
    }

    @ColorInt
    private fun getAppNameAndButtonColor(taskInfo: RunningTaskInfo): Int {
        val materialColorAttr = when {
            isDarkMode() -> materialColorOnSurface
            else -> materialColorOnSecondaryContainer
        }
        val appDetailsOpacity = when {
            isDarkMode() && !taskInfo.isFocused -> DARK_THEME_UNFOCUSED_OPACITY
            !isDarkMode() && !taskInfo.isFocused -> LIGHT_THEME_UNFOCUSED_OPACITY
            else -> FOCUSED_OPACITY
        }
        context.withStyledAttributes(null, intArrayOf(materialColorAttr), 0, 0) {
            val color = getColor(0, 0)
            return if (appDetailsOpacity == FOCUSED_OPACITY) {
                color
            } else {
                Color.argb(
                    appDetailsOpacity,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }
        }
        return 0
    }

    private fun isDarkMode(): Boolean {
        return context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "DesktopModeAppControlsWindowDecorationViewHolder"
        private const val DARK_THEME_UNFOCUSED_OPACITY = 140 // 55%
        private const val LIGHT_THEME_UNFOCUSED_OPACITY = 166 // 65%
        private const val FOCUSED_OPACITY = 255
    }
}
