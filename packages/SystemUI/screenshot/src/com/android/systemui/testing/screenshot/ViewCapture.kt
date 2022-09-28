package com.android.systemui.testing.screenshot

import android.annotation.WorkerThread
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.concurrent.futures.ResolvableFuture
import androidx.test.annotation.ExperimentalTestApi
import androidx.test.core.internal.os.HandlerExecutor
import androidx.test.espresso.Espresso
import androidx.test.platform.graphics.HardwareRendererCompat
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking

/*
 * This file was forked from androidx/test/core/view/ViewCapture.kt to add [Window] parameter to
 * [View.captureToBitmap].
 * TODO(b/195673633): Remove this fork and use the AndroidX version instead.
 */

/**
 * Asynchronously captures an image of the underlying view into a [Bitmap].
 *
 * For devices below [Build.VERSION_CODES#O] (or if the view's window cannot be determined), the
 * image is obtained using [View#draw]. Otherwise, [PixelCopy] is used.
 *
 * This method will also enable [HardwareRendererCompat#setDrawingEnabled(boolean)] if required.
 *
 * This API is primarily intended for use in lower layer libraries or frameworks. For test authors,
 * its recommended to use espresso or compose's captureToImage.
 *
 * This API is currently experimental and subject to change or removal.
 */
@ExperimentalTestApi
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
fun View.captureToBitmap(window: Window? = null): ListenableFuture<Bitmap> {
    val bitmapFuture: ResolvableFuture<Bitmap> = ResolvableFuture.create()
    val mainExecutor = HandlerExecutor(Handler(Looper.getMainLooper()))

    // disable drawing again if necessary once work is complete
    if (!HardwareRendererCompat.isDrawingEnabled()) {
        HardwareRendererCompat.setDrawingEnabled(true)
        bitmapFuture.addListener({ HardwareRendererCompat.setDrawingEnabled(false) }, mainExecutor)
    }

    mainExecutor.execute {
        val forceRedrawFuture = forceRedraw()
        forceRedrawFuture.addListener({ generateBitmap(bitmapFuture, window) }, mainExecutor)
    }

    return bitmapFuture
}

/**
 * Synchronously captures an image of the view into a [Bitmap]. Synchronous equivalent of
 * [captureToBitmap].
 */
@WorkerThread
@ExperimentalTestApi
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
fun View.toBitmap(window: Window? = null): Bitmap {
    if (Looper.getMainLooper() == Looper.myLooper()) {
        error("toBitmap() can't be called from the main thread")
    }

    if (!HardwareRenderer.isDrawingEnabled()) {
        error("Hardware rendering is not enabled")
    }

    // Make sure we are idle.
    Espresso.onIdle()

    val mainExecutor = context.mainExecutor
    return runBlocking {
        suspendCoroutine { continuation ->
            Futures.addCallback(
                captureToBitmap(window),
                object : FutureCallback<Bitmap> {
                    override fun onSuccess(result: Bitmap?) {
                        continuation.resumeWith(Result.success(result!!))
                    }

                    override fun onFailure(t: Throwable) {
                        continuation.resumeWith(Result.failure(t))
                    }
                },
                // We know that we are not on the main thread, so we can block the current
                // thread and wait for the result in the main thread.
                mainExecutor,
            )
        }
    }
}

/**
 * Trigger a redraw of the given view.
 *
 * Should only be called on UI thread.
 *
 * @return a [ListenableFuture] that will be complete once ui drawing is complete
 */
// NoClassDefFoundError occurs on API 15
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
// @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@ExperimentalTestApi
fun View.forceRedraw(): ListenableFuture<Void> {
    val future: ResolvableFuture<Void> = ResolvableFuture.create()

    if (Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated) {
        viewTreeObserver.registerFrameCommitCallback() { future.set(null) }
    } else {
        viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                var handled = false
                override fun onDraw() {
                    if (!handled) {
                        handled = true
                        future.set(null)
                        // cannot remove on draw listener inside of onDraw
                        Handler(Looper.getMainLooper()).post {
                            viewTreeObserver.removeOnDrawListener(this)
                        }
                    }
                }
            }
        )
    }
    invalidate()
    return future
}

private fun View.generateBitmap(
    bitmapFuture: ResolvableFuture<Bitmap>,
    window: Window? = null,
) {
    if (bitmapFuture.isCancelled) {
        return
    }
    val destBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    when {
        Build.VERSION.SDK_INT < 26 -> generateBitmapFromDraw(destBitmap, bitmapFuture)
        this is SurfaceView -> generateBitmapFromSurfaceViewPixelCopy(destBitmap, bitmapFuture)
        else -> {
            val window = window ?: getActivity()?.window
            if (window != null) {
                generateBitmapFromPixelCopy(window, destBitmap, bitmapFuture)
            } else {
                Log.i(
                    "View.captureToImage",
                    "Could not find window for view. Falling back to View#draw instead of PixelCopy"
                )
                generateBitmapFromDraw(destBitmap, bitmapFuture)
            }
        }
    }
}

@SuppressWarnings("NewApi")
private fun SurfaceView.generateBitmapFromSurfaceViewPixelCopy(
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
    PixelCopy.request(this, null, destBitmap, onCopyFinished, handler)
}

internal fun View.generateBitmapFromDraw(
    destBitmap: Bitmap,
    bitmapFuture: ResolvableFuture<Bitmap>
) {
    destBitmap.density = resources.displayMetrics.densityDpi
    computeScroll()
    val canvas = Canvas(destBitmap)
    canvas.translate((-scrollX).toFloat(), (-scrollY).toFloat())
    draw(canvas)
    bitmapFuture.set(destBitmap)
}

private fun View.getActivity(): Activity? {
    fun Context.getActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is ContextWrapper -> this.baseContext.getActivity()
            else -> null
        }
    }
    return context.getActivity()
}

private fun View.generateBitmapFromPixelCopy(
    window: Window,
    destBitmap: Bitmap,
    bitmapFuture: ResolvableFuture<Bitmap>
) {
    val locationInWindow = intArrayOf(0, 0)
    getLocationInWindow(locationInWindow)
    val x = locationInWindow[0]
    val y = locationInWindow[1]
    val boundsInWindow = Rect(x, y, x + width, y + height)

    return window.generateBitmapFromPixelCopy(boundsInWindow, destBitmap, bitmapFuture)
}
