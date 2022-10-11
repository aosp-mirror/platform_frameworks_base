package com.android.systemui.testing.screenshot

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.concurrent.futures.ResolvableFuture

/*
 * This file was forked from androidx/test/core/view/WindowCapture.kt.
 * TODO(b/195673633): Remove this fork and use the AndroidX version instead.
 */
fun Window.generateBitmapFromPixelCopy(
    boundsInWindow: Rect? = null,
    destBitmap: Bitmap,
    bitmapFuture: ResolvableFuture<Bitmap>
) {
    val onCopyFinished =
        PixelCopy.OnPixelCopyFinishedListener { result ->
            if (result == PixelCopy.SUCCESS) {
                bitmapFuture.set(destBitmap)
            } else {
                bitmapFuture.setException(
                    RuntimeException(String.format("PixelCopy failed: %d", result))
                )
            }
        }
    PixelCopy.request(
        this,
        boundsInWindow,
        destBitmap,
        onCopyFinished,
        Handler(Looper.getMainLooper())
    )
}
