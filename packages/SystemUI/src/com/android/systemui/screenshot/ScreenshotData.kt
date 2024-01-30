package com.android.systemui.screenshot

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Rect
import android.net.Uri
import android.os.UserHandle
import android.view.Display
import android.view.WindowManager.ScreenshotSource
import android.view.WindowManager.ScreenshotType
import androidx.annotation.VisibleForTesting
import com.android.internal.util.ScreenshotRequest

/** [ScreenshotData] represents the current state of a single screenshot being acquired. */
data class ScreenshotData(
    @ScreenshotType var type: Int,
    @ScreenshotSource var source: Int,
    /** UserHandle for the owner of the app being screenshotted, if known. */
    var userHandle: UserHandle?,
    /** ComponentName of the top-most app in the screenshot. */
    var topComponent: ComponentName?,
    var screenBounds: Rect?,
    var taskId: Int,
    var insets: Insets,
    var bitmap: Bitmap?,
    var displayId: Int,
    /** App-provided URL representing the content the user was looking at in the screenshot. */
    var contextUrl: Uri? = null,
) {
    val packageNameString: String
        get() = if (topComponent == null) "" else topComponent!!.packageName

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
        fun forTesting() =
            ScreenshotData(
                type = 0,
                source = 0,
                userHandle = null,
                topComponent = null,
                screenBounds = null,
                taskId = 0,
                insets = Insets.NONE,
                bitmap = null,
                displayId = Display.DEFAULT_DISPLAY,
            )
    }
}
