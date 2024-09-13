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

import android.R
import android.annotation.MainThread
import android.content.Context
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewRootImpl
import android.view.ViewTreeObserver.OnWindowAttachListener
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.window.WindowContext
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.internal.policy.PhoneWindow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Creates and manages the window in which the screenshot UI is displayed. */
class ScreenshotWindow
@AssistedInject
constructor(
    private val windowManager: WindowManager,
    private val viewCaptureAwareWindowManager: ViewCaptureAwareWindowManager,
    private val context: Context,
    @Assisted private val display: Display,
) {

    val window: PhoneWindow =
        PhoneWindow(
            context
                .createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_SCREENSHOT, null)
        )
    private val params =
        WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, /* xpos */
                0, /* ypos */
                WindowManager.LayoutParams.TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
            )
            .apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                // This is needed to let touches pass through outside the touchable areas
                privateFlags =
                    privateFlags or WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                title = "ScreenshotUI"
            }
    private var attachRequested: Boolean = false
    private var detachRequested: Boolean = false

    init {
        window.requestFeature(Window.FEATURE_NO_TITLE)
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        window.setBackgroundDrawableResource(R.color.transparent)
        window.setWindowManager(windowManager, null, null)
    }

    @MainThread
    fun attachWindow() {
        val decorView: View = window.getDecorView()
        if (decorView.isAttachedToWindow || attachRequested) {
            return
        }
        if (LogConfig.DEBUG_WINDOW) {
            Log.d(TAG, "attachWindow")
        }
        attachRequested = true
        viewCaptureAwareWindowManager.addView(decorView, params)

        decorView.requestApplyInsets()
        decorView.requireViewById<ViewGroup>(R.id.content).apply {
            clipChildren = false
            clipToPadding = false
            // ignore system bar insets for the purpose of window layout
            setOnApplyWindowInsetsListener { _, _ -> WindowInsets.CONSUMED }
        }
    }

    fun whenWindowAttached(action: Runnable) {
        val decorView: View = window.getDecorView()
        if (decorView.isAttachedToWindow) {
            action.run()
        } else {
            decorView
                .getViewTreeObserver()
                .addOnWindowAttachListener(
                    object : OnWindowAttachListener {
                        override fun onWindowAttached() {
                            attachRequested = false
                            decorView.getViewTreeObserver().removeOnWindowAttachListener(this)
                            action.run()
                        }

                        override fun onWindowDetached() {}
                    }
                )
        }
    }

    fun removeWindow() {
        val decorView: View? = window.peekDecorView()
        if (decorView != null && decorView.isAttachedToWindow) {
            if (LogConfig.DEBUG_WINDOW) {
                Log.d(TAG, "Removing screenshot window")
            }
            viewCaptureAwareWindowManager.removeViewImmediate(decorView)
            detachRequested = false
        }
        if (attachRequested && !detachRequested) {
            detachRequested = true
            whenWindowAttached { removeWindow() }
        }
    }

    /**
     * Updates the window focusability. If the window is already showing, then it updates the window
     * immediately, otherwise the layout params will be applied when the window is next shown.
     */
    fun setFocusable(focusable: Boolean) {
        if (LogConfig.DEBUG_WINDOW) {
            Log.d(TAG, "setWindowFocusable: $focusable")
        }
        val flags: Int = params.flags
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (params.flags == flags) {
            if (LogConfig.DEBUG_WINDOW) {
                Log.d(TAG, "setWindowFocusable: skipping, already $focusable")
            }
            return
        }
        window.peekDecorView()?.also {
            if (it.isAttachedToWindow) {
                windowManager.updateViewLayout(it, params)
            }
        }
    }

    fun getContext(): WindowContext = window.context as WindowContext

    fun getWindowToken(): IBinder = window.decorView.windowToken

    fun getWindowInsets(): WindowInsets = windowManager.currentWindowMetrics.windowInsets

    fun setContentView(view: View) {
        window.setContentView(view)
    }

    fun setActivityConfigCallback(callback: ViewRootImpl.ActivityConfigCallback) {
        window.peekDecorView().viewRootImpl.setActivityConfigCallback(callback)
    }

    @AssistedFactory
    interface Factory {
        fun create(display: Display): ScreenshotWindow
    }

    companion object {
        private const val TAG = "ScreenshotWindow"
    }
}
