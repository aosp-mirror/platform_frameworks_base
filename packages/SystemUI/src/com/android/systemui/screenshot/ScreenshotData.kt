package com.android.systemui.screenshot

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Rect
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
    val userHandle: UserHandle,
    /** ComponentName of the top-most app in the screenshot. */
    val topComponent: ComponentName?,
    val taskId: Int,
    val originalScreenBounds: Rect?,
    val originalInsets: Insets,
    var bitmap: Bitmap?,
    val displayId: Int,
) {
    val packageNameString
        get() = topComponent?.packageName ?: ""

    companion object {
        @JvmStatic
        fun fromRequest(request: ScreenshotRequest, displayId: Int = Display.DEFAULT_DISPLAY) =
            ScreenshotData(
                type = request.type,
                source = request.source,
                userHandle = UserHandle.of(request.userId),
                topComponent = request.topComponent,
                originalScreenBounds = request.boundsInScreen,
                taskId = request.taskId,
                originalInsets = request.insets,
                bitmap = request.bitmap,
                displayId = displayId,
            )

        @VisibleForTesting
        fun forTesting(
            userHandle: UserHandle = UserHandle.CURRENT,
            source: Int = ScreenshotSource.SCREENSHOT_KEY_CHORD,
            topComponent: ComponentName? = null,
            bitmap: Bitmap? = null,
        ) =
            ScreenshotData(
                type = WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                source = source,
                userHandle = userHandle,
                topComponent = topComponent,
                originalScreenBounds = null,
                taskId = 0,
                originalInsets = Insets.NONE,
                bitmap = bitmap,
                displayId = Display.DEFAULT_DISPLAY,
            )
    }
}
