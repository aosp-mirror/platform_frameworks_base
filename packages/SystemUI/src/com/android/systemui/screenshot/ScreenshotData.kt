package com.android.systemui.screenshot

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.view.Display
import android.view.WindowManager
import android.view.WindowManager.ScreenshotSource
import android.view.WindowManager.ScreenshotType
import androidx.annotation.VisibleForTesting
import com.android.internal.util.ScreenshotRequest

/** [ScreenshotData] represents the current state of a single screenshot being acquired. */
data class ScreenshotData(
    @ScreenshotType val type: Int,
    @ScreenshotSource val source: Int,
    /** UserHandle for the owner of the app being screenshotted, if known. */
    val userHandle: UserHandle?,
    /** ComponentName of the top-most app in the screenshot. */
    val topComponent: ComponentName?,
    var screenBounds: Rect?,
    val taskId: Int,
    var insets: Insets,
    var bitmap: Bitmap?,
    val displayId: Int,
) {
    val packageNameString
        get() = topComponent?.packageName ?: ""

    fun getUserOrDefault(): UserHandle {
        return userHandle ?: Process.myUserHandle()
    }

    companion object {
        @JvmStatic
        fun fromRequest(request: ScreenshotRequest, displayId: Int = Display.DEFAULT_DISPLAY) =
            ScreenshotData(
                type = request.type,
                source = request.source,
                userHandle = if (request.userId >= 0) UserHandle.of(request.userId) else null,
                topComponent = request.topComponent,
                screenBounds = request.boundsInScreen,
                taskId = request.taskId,
                insets = request.insets,
                bitmap = request.bitmap,
                displayId = displayId,
            )

        @VisibleForTesting
        fun forTesting(
            userHandle: UserHandle? = null,
            source: Int = ScreenshotSource.SCREENSHOT_KEY_CHORD,
            topComponent: ComponentName? = null,
            bitmap: Bitmap? = null,
        ) =
            ScreenshotData(
                type = WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                source = source,
                userHandle = userHandle,
                topComponent = topComponent,
                screenBounds = null,
                taskId = 0,
                insets = Insets.NONE,
                bitmap = bitmap,
                displayId = Display.DEFAULT_DISPLAY,
            )
    }
}
