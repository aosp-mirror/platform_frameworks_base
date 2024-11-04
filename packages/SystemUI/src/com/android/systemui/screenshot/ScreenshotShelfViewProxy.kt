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
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.os.Looper
import android.view.Choreographer
import android.view.InputEvent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.android.internal.logging.UiEventLogger
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS
import com.android.systemui.screenshot.LogConfig.DEBUG_INPUT
import com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER
import com.android.systemui.screenshot.scroll.ScrollCaptureController
import com.android.systemui.screenshot.ui.ScreenshotAnimationController
import com.android.systemui.screenshot.ui.ScreenshotShelfView
import com.android.systemui.screenshot.ui.binder.ScreenshotShelfViewBinder
import com.android.systemui.screenshot.ui.viewmodel.AnimationState
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
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
    shelfViewBinder: ScreenshotShelfViewBinder,
    private val thumbnailObserver: ThumbnailObserver,
    @Assisted private val context: Context,
    @Assisted private val displayId: Int,
) {

    interface ScreenshotViewCallback {
        fun onUserInteraction()

        fun onDismiss()

        /** DOWN motion event was observed outside of the touchable areas of this view. */
        fun onTouchOutside()
    }

    val view: ScreenshotShelfView =
        LayoutInflater.from(context).inflate(R.layout.screenshot_shelf, null) as ScreenshotShelfView
    val screenshotPreview: View
    var packageName: String = ""
    var callbacks: ScreenshotViewCallback? = null
    var screenshot: ScreenshotData? = null
        set(value) {
            value?.let {
                viewModel.setScreenshotBitmap(it.bitmap)
                val badgeBg =
                    AppCompatResources.getDrawable(context, R.drawable.overlay_badge_background)
                val user = it.userHandle
                if (badgeBg != null && user != null) {
                    viewModel.setScreenshotBadge(
                        context.packageManager.getUserBadgedIcon(badgeBg, user)
                    )
                }
            }
            field = value
        }

    val isAttachedToWindow
        get() = view.isAttachedToWindow

    var isDismissing = false
    var isPendingSharedTransition = false

    private val animationController = ScreenshotAnimationController(view, viewModel)
    private var inputMonitor: InputMonitorCompat? = null
    private var inputEventReceiver: InputChannelCompat.InputEventReceiver? = null

    init {
        shelfViewBinder.bind(
            view,
            viewModel,
            animationController,
            LayoutInflater.from(context),
            onDismissalRequested = { event, velocity -> requestDismissal(event, velocity) },
            onUserInteraction = { callbacks?.onUserInteraction() },
        )
        view.updateInsets(windowManager.currentWindowMetrics.windowInsets)
        addPredictiveBackListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        setOnKeyListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        debugLog(DEBUG_WINDOW) { "adding OnComputeInternalInsetsListener" }
        view.viewTreeObserver.addOnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(getTouchRegion())
        }
        screenshotPreview = view.screenshotPreview
        thumbnailObserver.setViews(
            view.blurredScreenshotPreview,
            view.requireViewById(R.id.screenshot_preview_border),
        )
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    startInputListening()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    stopInputListening()
                }
            }
        )
    }

    fun reset() {
        animationController.cancel()
        isPendingSharedTransition = false
        viewModel.reset()
    }

    fun updateInsets(insets: WindowInsets) {
        view.updateInsets(insets)
    }

    fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator {
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

    fun requestDismissal(event: ScreenshotEvent?) {
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

    fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean,
        onTransitionPrepared: Runnable,
    ) {
        viewModel.setScrollingScrimBitmap(newScreenshot)
        viewModel.setScrollableRect(scrollableAreaOnScreen(response))
        animationController.fadeForLongScreenshotTransition()
        view.post { onTransitionPrepared.run() }
    }

    private fun scrollableAreaOnScreen(response: ScrollCaptureResponse): Rect {
        val r = Rect(response.boundsInWindow)
        val windowInScreen = response.windowBounds
        r.offset(windowInScreen?.left ?: 0, windowInScreen?.top ?: 0)
        r.intersect(
            Rect(
                0,
                0,
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels,
            )
        )
        return r
    }

    fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot,
    ) {
        val transitionAnimation =
            animationController.runLongScreenshotTransition(
                transitionDestination,
                longScreenshot,
                onTransitionEnd,
            )
        transitionAnimation.doOnEnd { callbacks?.onDismiss() }
        transitionAnimation.start()
    }

    fun restoreNonScrollingUi() {
        viewModel.setScrollableRect(null)
        viewModel.setScrollingScrimBitmap(null)
        animationController.restoreUI()
        callbacks?.onUserInteraction() // reset the timeout
    }

    fun stopInputListening() {
        inputMonitor?.dispose()
        inputMonitor = null
        inputEventReceiver?.dispose()
        inputEventReceiver = null
    }

    fun requestFocus() {
        view.requestFocus()
    }

    fun announceForAccessibility(string: String) = view.announceForAccessibility(string)

    fun prepareEntranceAnimation(runnable: Runnable) {
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

    fun fadeForSharedTransition() {
        animationController.fadeForSharedTransition()
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
                            onBackInvokedCallback,
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

    private fun startInputListening() {
        stopInputListening()
        inputMonitor =
            InputMonitorCompat("Screenshot", displayId).also {
                inputEventReceiver =
                    it.getInputReceiver(Looper.getMainLooper(), Choreographer.getInstance()) {
                        ev: InputEvent? ->
                        if (
                            ev is MotionEvent &&
                                ev.actionMasked == MotionEvent.ACTION_DOWN &&
                                !getTouchRegion().contains(ev.rawX.toInt(), ev.rawY.toInt())
                        ) {
                            callbacks?.onTouchOutside()
                        }
                    }
            }
    }

    private fun getTouchRegion(): Region {
        return view.getTouchRegion(
            windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.systemGestures()
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun getProxy(context: Context, displayId: Int): ScreenshotShelfViewProxy
    }
}
