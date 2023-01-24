package com.android.systemui.screenshot

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Rect
import android.os.UserHandle
import android.view.WindowManager.ScreenshotSource
import android.view.WindowManager.ScreenshotType
import com.android.internal.util.ScreenshotRequest

/** ScreenshotData represents the current state of a single screenshot being acquired. */
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
) {
    val packageNameString: String
        get() = if (topComponent == null) "" else topComponent!!.packageName

    companion object {
        @JvmStatic
        fun fromRequest(request: ScreenshotRequest): ScreenshotData {
            return ScreenshotData(
                request.type,
                request.source,
                if (request.userId >= 0) UserHandle.of(request.userId) else null,
                request.topComponent,
                request.boundsInScreen,
                request.taskId,
                request.insets,
                request.bitmap,
            )
        }
    }
}
