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
import android.animation.AnimatorListenerAdapter
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.android.internal.logging.UiEventLogger
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS
import com.android.systemui.screenshot.LogConfig.DEBUG_INPUT
import com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW
import com.android.systemui.screenshot.ScreenshotController.SavedImageData
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER
import com.android.systemui.screenshot.scroll.ScrollCaptureController
import com.android.systemui.screenshot.ui.ScreenshotAnimationController
import com.android.systemui.screenshot.ui.ScreenshotShelfView
import com.android.systemui.screenshot.ui.binder.ScreenshotShelfViewBinder
import com.android.systemui.screenshot.ui.viewmodel.AnimationState
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Controls the screenshot view and viewModel. */
class ScreenshotShelfViewProxy
@AssistedInject
constructor(
    private val logger: UiEventLogger,
    private val viewModel: ScreenshotViewModel,
    private val windowManager: WindowManager,
    private val thumbnailObserver: ThumbnailObserver,
    @Assisted private val context: Context,
    @Assisted private val displayId: Int
) : ScreenshotViewProxy {
    override val view: ScreenshotShelfView =
        LayoutInflater.from(context).inflate(R.layout.screenshot_shelf, null) as ScreenshotShelfView
    override val screenshotPreview: View
    override var packageName: String = ""
    override var callbacks: ScreenshotView.ScreenshotViewCallback? = null
    override var screenshot: ScreenshotData? = null
        set(value) {
            viewModel.setScreenshotBitmap(value?.bitmap)
            field = value
        }

    override val isAttachedToWindow
        get() = view.isAttachedToWindow
    override var isDismissing = false
    override var isPendingSharedTransition = false

    private val animationController = ScreenshotAnimationController(view)

    init {
        ScreenshotShelfViewBinder.bind(
            view,
            viewModel,
            LayoutInflater.from(context),
            onDismissalRequested = { event, velocity -> requestDismissal(event, velocity) },
            onDismissalCancelled = { animationController.getSwipeReturnAnimation().start() },
            onUserInteraction = { callbacks?.onUserInteraction() }
        )
        view.updateInsets(windowManager.currentWindowMetrics.windowInsets)
        addPredictiveBackListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        setOnKeyListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        debugLog(DEBUG_WINDOW) { "adding OnComputeInternalInsetsListener" }
        view.viewTreeObserver.addOnComputeInternalInsetsListener { info ->
            val touchableRegion =
                view.getTouchRegion(
                    windowManager.currentWindowMetrics.windowInsets.getInsets(
                        WindowInsets.Type.systemGestures()
                    )
                )
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(touchableRegion)
        }
        screenshotPreview = view.screenshotPreview
        thumbnailObserver.setViews(
            view.blurredScreenshotPreview,
            view.requireViewById(R.id.screenshot_preview_border)
        )
    }

    override fun reset() {
        animationController.cancel()
        isPendingSharedTransition = false
        viewModel.reset()
    }
    override fun updateInsets(insets: WindowInsets) {
        view.updateInsets(insets)
    }
    override fun updateOrientation(insets: WindowInsets) {}

    override fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator {
        val entrance =
            animationController.getEntranceAnimation(screenRect, showFlash) {
                viewModel.setAnimationState(AnimationState.ENTRANCE_REVEAL)
            }
        entrance.doOnStart {
            thumbnailObserver.onEntranceStarted()
            viewModel.setAnimationState(AnimationState.ENTRANCE_STARTED)
        }
        entrance.doOnEnd {
            // reset the timeout when animation finishes
            callbacks?.onUserInteraction()
            thumbnailObserver.onEntranceComplete()
            viewModel.setAnimationState(AnimationState.ENTRANCE_COMPLETE)
        }
        return entrance
    }

    override fun addQuickShareChip(quickShareAction: Notification.Action) {}

    override fun setChipIntents(imageData: SavedImageData) {}

    override fun requestDismissal(event: ScreenshotEvent?) {
        requestDismissal(event, null)
    }

    private fun requestDismissal(event: ScreenshotEvent?, velocity: Float?) {
        debugLog(DEBUG_DISMISS) { "screenshot dismissal requested: $event" }

        // If we're already animating out, don't restart the animation
        if (isDismissing) {
            debugLog(DEBUG_DISMISS) { "Already dismissing, ignoring duplicate command $event" }
            return
        }
        event?.let { logger.log(it, 0, packageName) }
        val animator = animationController.getSwipeDismissAnimation(velocity)
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    isDismissing = true
                }
                override fun onAnimationEnd(animator: Animator) {
                    isDismissing = false
                    callbacks?.onDismiss()
                }
            }
        )
        animator.start()
    }

    override fun showScrollChip(packageName: String, onClick: Runnable) {}

    override fun hideScrollChip() {}

    override fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        screenBitmap: Bitmap,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean,
        onTransitionPrepared: Runnable,
    ) {
        onTransitionPrepared.run()
    }

    override fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot
    ) {
        onTransitionEnd.run()
        callbacks?.onDismiss()
    }

    override fun restoreNonScrollingUi() {}

    override fun stopInputListening() {}

    override fun requestFocus() {
        view.requestFocus()
    }

    override fun announceForAccessibility(string: String) = view.announceForAccessibility(string)

    override fun prepareEntranceAnimation(runnable: Runnable) {
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    debugLog(DEBUG_WINDOW) { "onPreDraw: startAnimation" }
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    runnable.run()
                    return true
                }
            }
        )
    }

    private fun addPredictiveBackListener(onDismissRequested: (ScreenshotEvent) -> Unit) {
        val onBackInvokedCallback = OnBackInvokedCallback {
            debugLog(DEBUG_INPUT) { "Predictive Back callback dispatched" }
            onDismissRequested.invoke(SCREENSHOT_DISMISSED_OTHER)
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    debugLog(DEBUG_INPUT) { "Registering Predictive Back callback" }
                    view
                        .findOnBackInvokedDispatcher()
                        ?.registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            onBackInvokedCallback
                        )
                }

                override fun onViewDetachedFromWindow(view: View) {
                    debugLog(DEBUG_INPUT) { "Unregistering Predictive Back callback" }
                    view
                        .findOnBackInvokedDispatcher()
                        ?.unregisterOnBackInvokedCallback(onBackInvokedCallback)
                }
            }
        )
    }

    private fun setOnKeyListener(onDismissRequested: (ScreenshotEvent) -> Unit) {
        view.setOnKeyListener(
            object : View.OnKeyListener {
                override fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        debugLog(DEBUG_INPUT) { "onKeyEvent: $keyCode" }
                        onDismissRequested.invoke(SCREENSHOT_DISMISSED_OTHER)
                        return true
                    }
                    return false
                }
            }
        )
    }

    @AssistedFactory
    interface Factory : ScreenshotViewProxy.Factory {
        override fun getProxy(context: Context, displayId: Int): ScreenshotShelfViewProxy
    }
}
