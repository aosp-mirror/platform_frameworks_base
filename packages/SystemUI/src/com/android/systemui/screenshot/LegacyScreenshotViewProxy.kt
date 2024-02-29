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
import android.graphics.drawable.Drawable
import android.view.Display
import android.view.LayoutInflater
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import com.android.internal.logging.UiEventLogger
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.res.R

/**
 * Legacy implementation of screenshot view methods. Just proxies the calls down into the original
 * ScreenshotView.
 */
class LegacyScreenshotViewProxy(context: Context) : ScreenshotViewProxy {
    override val view: ScreenshotView =
        LayoutInflater.from(context).inflate(R.layout.screenshot, null) as ScreenshotView
    override val internalInsetsListener: ViewTreeObserver.OnComputeInternalInsetsListener
    override val screenshotPreview: View

    override var defaultDisplay: Int = Display.DEFAULT_DISPLAY
        set(value) {
            view.setDefaultDisplay(value)
        }
    override var defaultTimeoutMillis: Long = 6000
        set(value) {
            view.setDefaultTimeoutMillis(value)
        }
    override var onKeyListener: View.OnKeyListener? = null
        set(value) {
            view.setOnKeyListener(value)
        }
    override var flags: FeatureFlags? = null
        set(value) {
            view.setFlags(value)
        }
    override var packageName: String = ""
        set(value) {
            view.setPackageName(value)
        }
    override var logger: UiEventLogger? = null
        set(value) {
            view.setUiEventLogger(value)
        }
    override var callbacks: ScreenshotView.ScreenshotViewCallback? = null
        set(value) {
            view.setCallbacks(value)
        }
    override var screenshot: ScreenshotData? = null
        set(value) {
            view.setScreenshot(value)
        }

    override val isAttachedToWindow
        get() = view.isAttachedToWindow
    override val isDismissing
        get() = view.isDismissing
    override val isPendingSharedTransition
        get() = view.isPendingSharedTransition

    init {
        internalInsetsListener = view
        screenshotPreview = view.screenshotPreview
    }

    override fun reset() = view.reset()
    override fun updateInsets(insets: WindowInsets) = view.updateInsets(insets)
    override fun updateOrientation(insets: WindowInsets) = view.updateOrientation(insets)

    override fun badgeScreenshot(userBadgedIcon: Drawable) = view.badgeScreenshot(userBadgedIcon)

    override fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator =
        view.createScreenshotDropInAnimation(screenRect, showFlash)

    override fun addQuickShareChip(quickShareAction: Notification.Action) =
        view.addQuickShareChip(quickShareAction)

    override fun setChipIntents(imageData: ScreenshotController.SavedImageData) =
        view.setChipIntents(imageData)

    override fun animateDismissal() = view.animateDismissal()

    override fun showScrollChip(packageName: String, onClick: Runnable) =
        view.showScrollChip(packageName, onClick)

    override fun hideScrollChip() = view.hideScrollChip()

    override fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        screenBitmap: Bitmap,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean
    ) =
        view.prepareScrollingTransition(
            response,
            screenBitmap,
            newScreenshot,
            screenshotTakenInPortrait
        )

    override fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot
    ) = view.startLongScreenshotTransition(transitionDestination, onTransitionEnd, longScreenshot)

    override fun restoreNonScrollingUi() = view.restoreNonScrollingUi()

    override fun stopInputListening() = view.stopInputListening()

    override fun requestFocus() {
        view.requestFocus()
    }

    override fun announceForAccessibility(string: String) = view.announceForAccessibility(string)

    override fun addOnAttachStateChangeListener(listener: View.OnAttachStateChangeListener) =
        view.addOnAttachStateChangeListener(listener)

    override fun findOnBackInvokedDispatcher(): OnBackInvokedDispatcher? =
        view.findOnBackInvokedDispatcher()

    override fun getViewTreeObserver(): ViewTreeObserver = view.viewTreeObserver

    override fun post(runnable: Runnable) {
        view.post(runnable)
    }

    class Factory : ScreenshotViewProxy.Factory {
        override fun getProxy(context: Context): ScreenshotViewProxy {
            return LegacyScreenshotViewProxy(context)
        }
    }
}
