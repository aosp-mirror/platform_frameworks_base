/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.animation.Animator
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import com.android.systemui.screenshot.scroll.ScrollCaptureController

/** Abstraction of the surface between ScreenshotController and ScreenshotView */
interface ScreenshotViewProxy {
    val view: ViewGroup
    val screenshotPreview: View

    var packageName: String
    var callbacks: ScreenshotView.ScreenshotViewCallback?
    var screenshot: ScreenshotData?

    val isAttachedToWindow: Boolean
    val isDismissing: Boolean
    val isPendingSharedTransition: Boolean

    fun reset()
    fun updateInsets(insets: WindowInsets)
    fun updateOrientation(insets: WindowInsets)
    fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator
    fun addQuickShareChip(quickShareAction: Notification.Action)
    fun setChipIntents(imageData: ScreenshotController.SavedImageData)
    fun requestDismissal(event: ScreenshotEvent?)

    fun showScrollChip(packageName: String, onClick: Runnable)
    fun hideScrollChip()
    fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        screenBitmap: Bitmap,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean,
        onTransitionPrepared: Runnable,
    )
    fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot
    )
    fun restoreNonScrollingUi()
    fun fadeForSharedTransition()

    fun stopInputListening()
    fun requestFocus()
    fun announceForAccessibility(string: String)
    fun prepareEntranceAnimation(runnable: Runnable)

    interface Factory {
        fun getProxy(context: Context, displayId: Int): ScreenshotViewProxy
    }
}
