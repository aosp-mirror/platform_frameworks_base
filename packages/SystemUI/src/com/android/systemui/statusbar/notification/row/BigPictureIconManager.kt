/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.row

import android.annotation.WorkerThread
import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Dumpable
import android.util.Log
import android.util.Size
import androidx.annotation.MainThread
import com.android.internal.R
import com.android.internal.widget.NotificationDrawableConsumer
import com.android.internal.widget.NotificationIconManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.Empty
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.FullImage
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.Initial
import com.android.systemui.statusbar.notification.row.BigPictureIconManager.DrawableState.PlaceHolder
import com.android.systemui.util.Assert
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BigPicImageLoader"
private const val DEBUG = false
private const val FREE_IMAGE_DELAY_MS = 3000L

/**
 * A helper class for [com.android.internal.widget.BigPictureNotificationImageView] to lazy-load
 * images from SysUI. It replaces the placeholder image with the fully loaded one, and vica versa.
 *
 * TODO(b/283082473) move the logs to a [com.android.systemui.log.LogBuffer]
 */
@SuppressWarnings("DumpableNotRegistered")
class BigPictureIconManager
@Inject
constructor(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val statsManager: BigPictureStatsManager,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val bgDispatcher: CoroutineDispatcher
) : NotificationIconManager, Dumpable {

    private var lastLoadingJob: Job? = null
    private var drawableConsumer: NotificationDrawableConsumer? = null
    private var displayedState: DrawableState = Initial
    private var viewShown = false

    private var maxWidth = getMaxWidth()
    private var maxHeight = getMaxHeight()

    /**
     * Called when the displayed state changes of the view.
     *
     * @param shown true if the view is shown, and the image needs to be displayed.
     */
    fun onViewShown(shown: Boolean) {
        log("onViewShown:$shown")

        if (this.viewShown != shown) {
            this.viewShown = shown

            val state = displayedState

            this.lastLoadingJob?.cancel()
            this.lastLoadingJob =
                when {
                    skipLazyLoading(state.icon) -> null
                    state is PlaceHolder && shown -> startLoadingJob(state.icon)
                    state is FullImage && !shown ->
                        startFreeImageJob(state.icon, state.drawableSize)
                    else -> null
                }
        }
    }

    /**
     * Update the maximum width and height allowed for bitmaps, ex. after a configuration change.
     */
    fun updateMaxImageSizes() {
        log("updateMaxImageSizes")
        maxWidth = getMaxWidth()
        maxHeight = getMaxHeight()
    }

    /** Cancels all currently running jobs. */
    fun cancelJobs() {
        lastLoadingJob?.cancel()
    }

    @WorkerThread
    override fun updateIcon(drawableConsumer: NotificationDrawableConsumer, icon: Icon?): Runnable {
        this.drawableConsumer = drawableConsumer
        this.lastLoadingJob?.cancel()

        val drawableAndState = loadImageOrPlaceHolderSync(icon)
        log("icon updated")

        return Runnable { applyDrawableAndState(drawableAndState) }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>?) {
        pw.println("BigPictureIconManager ${getDebugString()}")
    }

    @WorkerThread
    private fun loadImageOrPlaceHolderSync(icon: Icon?): Pair<Drawable, DrawableState>? {
        icon ?: return null

        if (viewShown || skipLazyLoading(icon)) {
            return loadImageSync(icon)
        }

        return loadPlaceHolderSync(icon) ?: loadImageSync(icon)
    }

    @WorkerThread
    private fun loadImageSync(icon: Icon): Pair<Drawable, DrawableState>? {
        return imageLoader.loadDrawableSync(icon, context, maxWidth, maxHeight)?.let { drawable ->
            checkPlaceHolderSizeForDrawable(this.displayedState, drawable)
            Pair(drawable, FullImage(icon, drawable.intrinsicSize))
        }
    }

    private fun checkPlaceHolderSizeForDrawable(
        displayedState: DrawableState,
        newDrawable: Drawable
    ) {
        if (displayedState is PlaceHolder) {
            val (oldWidth, oldHeight) = displayedState.drawableSize
            val (newWidth, newHeight) = newDrawable.intrinsicSize

            if (oldWidth != newWidth || oldHeight != newHeight) {
                Log.e(
                    TAG,
                    "Mismatch in dimensions, when replacing PlaceHolder " +
                        "$oldWidth X $oldHeight with Drawable $newWidth X $newHeight."
                )
            }
        }
    }

    @WorkerThread
    private fun loadPlaceHolderSync(icon: Icon): Pair<Drawable, DrawableState>? {
        return imageLoader
            .loadSizeSync(icon, context)
            ?.resizeToMax(maxWidth, maxHeight) // match the dimensions of the fully loaded drawable
            ?.let { size -> createPlaceHolder(icon, size) }
    }

    @MainThread
    private fun applyDrawableAndState(drawableAndState: Pair<Drawable, DrawableState>?) {
        Assert.isMainThread()
        drawableConsumer?.setImageDrawable(drawableAndState?.first)
        displayedState = drawableAndState?.second ?: Empty
    }

    private fun startLoadingJob(icon: Icon): Job = scope.launch {
        statsManager.measure { loadImage(icon) }
    }

    private suspend fun loadImage(icon: Icon) {
        val drawableAndState = withContext(bgDispatcher) { loadImageSync(icon) }
        withContext(mainDispatcher) { applyDrawableAndState(drawableAndState) }
        log("full image loaded")
    }

    private fun startFreeImageJob(icon: Icon, drawableSize: Size): Job =
        scope.launch {
            delay(FREE_IMAGE_DELAY_MS)
            val drawableAndState = createPlaceHolder(icon, drawableSize)
            withContext(mainDispatcher) { applyDrawableAndState(drawableAndState) }
            log("placeholder loaded")
        }

    private fun createPlaceHolder(icon: Icon, size: Size): Pair<Drawable, DrawableState> {
        val drawable = PlaceHolderDrawable(width = size.width, height = size.height)
        val state = PlaceHolder(icon, drawable.intrinsicSize)
        return Pair(drawable, state)
    }

    private fun isLowRam(): Boolean {
        return ActivityManager.isLowRamDeviceStatic()
    }

    private fun getMaxWidth() =
        context.resources.getDimensionPixelSize(
            if (isLowRam()) {
                R.dimen.notification_big_picture_max_width_low_ram
            } else {
                R.dimen.notification_big_picture_max_width
            }
        )

    private fun getMaxHeight() =
        context.resources.getDimensionPixelSize(
            if (isLowRam()) {
                R.dimen.notification_big_picture_max_height_low_ram
            } else {
                R.dimen.notification_big_picture_max_height
            }
        )

    /**
     * We don't support lazy-loading or set placeholders for bitmap and data based icons, because
     * they gonna stay in memory anyways.
     */
    private fun skipLazyLoading(icon: Icon?): Boolean =
        when (icon?.type) {
            Icon.TYPE_BITMAP,
            Icon.TYPE_ADAPTIVE_BITMAP,
            Icon.TYPE_DATA,
            null -> true
            else -> false
        }

    private fun log(msg: String) {
        if (DEBUG) {
            Log.d(TAG, "$msg state=${getDebugString()}")
        }
    }

    private fun getDebugString() =
        "{ state:$displayedState, hasConsumer:${drawableConsumer != null}, viewShown:$viewShown}"

    private sealed class DrawableState(open val icon: Icon?) {
        data object Initial : DrawableState(null)
        data object Empty : DrawableState(null)
        data class PlaceHolder(override val icon: Icon, val drawableSize: Size) :
            DrawableState(icon)
        data class FullImage(override val icon: Icon, val drawableSize: Size) : DrawableState(icon)
    }
}

/**
 * @return an image size that conforms to the maxWidth / maxHeight parameters. It can be the same
 *   instance, if the provided size was already small enough.
 */
private fun Size.resizeToMax(maxWidth: Int, maxHeight: Int): Size {
    if (width <= maxWidth && height <= maxHeight) {
        return this
    }

    // Calculate the scale factor for both dimensions
    val wScale =
        if (maxWidth <= 0) {
            1.0f
        } else {
            maxWidth.toFloat() / width.toFloat()
        }

    val hScale =
        if (maxHeight <= 0) {
            1.0f
        } else {
            maxHeight.toFloat() / height.toFloat()
        }

    // Scale down to the smaller scale factor
    val scale = min(wScale, hScale)
    if (scale < 1.0f) {
        val targetWidth = (width * scale).toInt()
        val targetHeight = (height * scale).toInt()

        return Size(targetWidth, targetHeight)
    }

    return this
}

private val Drawable.intrinsicSize
    get() = Size(/*width=*/ intrinsicWidth, /*height=*/ intrinsicHeight)

private operator fun Size.component1() = width

private operator fun Size.component2() = height
