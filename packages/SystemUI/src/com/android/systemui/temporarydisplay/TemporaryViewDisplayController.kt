/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.temporarydisplay

import android.annotation.LayoutRes
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
import androidx.annotation.CallSuper
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor

/**
 * A generic controller that can temporarily display a new view in a new window.
 *
 * Subclasses need to override and implement [updateView], which is where they can control what
 * gets displayed to the user.
 *
 * The generic type T is expected to contain all the information necessary for the subclasses to
 * display the view in a certain state, since they receive <T> in [updateView].
 *
 * @property windowTitle the title to use for the window that displays the temporary view. Should be
 *   normally cased, like "Window Title".
 * @property wakeReason a string used for logging if we needed to wake the screen in order to
 *   display the temporary view. Should be screaming snake cased, like WAKE_REASON.
 */
abstract class TemporaryViewDisplayController<T : TemporaryViewInfo, U : TemporaryViewLogger>(
    internal val context: Context,
    internal val logger: U,
    internal val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    private val accessibilityManager: AccessibilityManager,
    private val configurationController: ConfigurationController,
    private val powerManager: PowerManager,
    @LayoutRes private val viewLayoutRes: Int,
    private val windowTitle: String,
    private val wakeReason: String,
) {
    /**
     * Window layout params that will be used as a starting point for the [windowLayoutParams] of
     * all subclasses.
     */
    @SuppressLint("WrongConstant") // We're allowed to use TYPE_VOLUME_OVERLAY
    internal val commonWindowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        title = windowTitle
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /**
     * The window layout parameters we'll use when attaching the view to a window.
     *
     * Subclasses must override this to provide their specific layout params, and they should use
     * [commonWindowLayoutParams] as part of their layout params.
     */
    internal abstract val windowLayoutParams: WindowManager.LayoutParams

    /** The view currently being displayed. Null if the view is not being displayed. */
    private var view: ViewGroup? = null

    /** The info currently being displayed. Null if the view is not being displayed. */
    internal var info: T? = null

    /** A [Runnable] that, when run, will cancel the pending timeout of the view. */
    private var cancelViewTimeout: Runnable? = null

    /**
     * Displays the view with the provided [newInfo].
     *
     * This method handles inflating and attaching the view, then delegates to [updateView] to
     * display the correct information in the view.
     */
    fun displayView(newInfo: T) {
        val currentView = view

        if (currentView != null) {
            updateView(newInfo, currentView)
        } else {
            // The view is new, so set up all our callbacks and inflate the view
            configurationController.addCallback(displayScaleListener)
            // Wake the screen if necessary so the user will see the view. (Per b/239426653, we want
            // the view to show over the dream state, so we should only wake up if the screen is
            // completely off.)
            if (!powerManager.isScreenOn) {
                powerManager.wakeUp(
                        SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_APPLICATION,
                        "com.android.systemui:$wakeReason",
                )
            }
            logger.logChipAddition()
            inflateAndUpdateView(newInfo)
        }

        // Cancel and re-set the view timeout each time we get a new state.
        val timeout = accessibilityManager.getRecommendedTimeoutMillis(
            newInfo.getTimeoutMs().toInt(),
            // Not all views have controls so FLAG_CONTENT_CONTROLS might be superfluous, but
            // include it just to be safe.
            FLAG_CONTENT_ICONS or FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS
       )
        cancelViewTimeout?.run()
        cancelViewTimeout = mainExecutor.executeDelayed(
            { removeView(TemporaryDisplayRemovalReason.REASON_TIMEOUT) },
            timeout.toLong()
        )
    }

    /** Inflates a new view, updates it with [newInfo], and adds the view to the window. */
    private fun inflateAndUpdateView(newInfo: T) {
        val newView = LayoutInflater
                .from(context)
                .inflate(viewLayoutRes, null) as ViewGroup
        view = newView
        updateView(newInfo, newView)
        windowManager.addView(newView, windowLayoutParams)
        animateViewIn(newView)
    }

    /** Removes then re-inflates the view. */
    private fun reinflateView() {
        val currentInfo = info
        if (view == null || currentInfo == null) { return }

        windowManager.removeView(view)
        inflateAndUpdateView(currentInfo)
    }

    private val displayScaleListener = object : ConfigurationController.ConfigurationListener {
        override fun onDensityOrFontScaleChanged() {
            reinflateView()
        }
    }

    /**
     * Hides the view.
     *
     * @param removalReason a short string describing why the view was removed (timeout, state
     *     change, etc.)
     */
    open fun removeView(removalReason: String) {
        if (view == null) { return }
        logger.logChipRemoval(removalReason)
        configurationController.removeCallback(displayScaleListener)
        windowManager.removeView(view)
        view = null
        info = null
        // No need to time the view out since it's already gone
        cancelViewTimeout?.run()
    }

    /**
     * A method implemented by subclasses to update [currentView] based on [newInfo].
     */
    @CallSuper
    open fun updateView(newInfo: T, currentView: ViewGroup) {
        info = newInfo
    }

    /**
     * A method that can be implemented by subclasses to do custom animations for when the view
     * appears.
     */
    open fun animateViewIn(view: ViewGroup) {}
}

object TemporaryDisplayRemovalReason {
    const val REASON_TIMEOUT = "TIMEOUT"
    const val REASON_SCREEN_TAP = "SCREEN_TAP"
}

private data class IconInfo(
    val iconName: String,
    val icon: Drawable,
    /** True if [icon] is the app's icon, and false if [icon] is some generic default icon. */
    val isAppIcon: Boolean
)
