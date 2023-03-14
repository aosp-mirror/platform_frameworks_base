package com.android.systemui.statusbar.notification.fsi

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Binder
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Config for adding the FsiChromeView window to WindowManager and starting the FSI activity.
 */
class FsiTaskViewConfig {

    companion object {

        private const val classTag = "FsiTaskViewConfig"

        fun getWmLayoutParams(packageName: String): WindowManager.LayoutParams {
            val params: WindowManager.LayoutParams?
            params =
                WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
                    PixelFormat.TRANSLUCENT
                )
            params.setTrustedOverlay()
            params.fitInsetsTypes = 0
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            params.token = Binder()
            params.packageName = packageName
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            params.privateFlags =
                params.privateFlags or WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            return params
        }

        fun getFillInIntent(): Intent {
            val fillInIntent = Intent()
            fillInIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            fillInIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            // FLAG_ACTIVITY_NEW_TASK is auto-applied because
            // we're starting the FSI activity from a non-Activity context
            return fillInIntent
        }

        fun getLaunchBounds(windowManager: WindowManager): Rect {
            // TODO(b/243421660) check this works for non-resizeable activity
            return Rect()
        }

        fun getActivityOptions(context: Context, windowManager: WindowManager): ActivityOptions {
            // Custom options so there is no activity transition animation
            val options =
                ActivityOptions.makeCustomAnimation(context, 0 /* enterResId */, 0 /* exitResId */)

            options.taskAlwaysOnTop = true

            options.pendingIntentLaunchFlags =
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_TASK

            options.launchBounds = getLaunchBounds(windowManager)
            return options
        }
    }
}
