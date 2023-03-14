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
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
import androidx.annotation.CallSuper
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.wakelock.WakeLock
import java.io.PrintWriter

/**
 * A generic controller that can temporarily display a new view in a new window.
 *
 * Subclasses need to override and implement [updateView], which is where they can control what
 * gets displayed to the user.
 *
 * The generic type T is expected to contain all the information necessary for the subclasses to
 * display the view in a certain state, since they receive <T> in [updateView].
 *
 * Some information about display ordering:
 *
 * [ViewPriority] defines different priorities for the incoming views. The incoming view will be
 * displayed so long as its priority is equal to or greater than the currently displayed view.
 * (Concretely, this means that a [ViewPriority.NORMAL] won't be displayed if a
 * [ViewPriority.CRITICAL] is currently displayed. But otherwise, the incoming view will get
 * displayed and kick out the old view).
 *
 * Once the currently displayed view times out, we *may* display a previously requested view if it
 * still has enough time left before its own timeout. The same priority ordering applies.
 *
 * Note: [TemporaryViewInfo.id] is the identifier that we use to determine if a call to
 * [displayView] will just update the current view with new information, or display a completely new
 * view. This means that you *cannot* change the [TemporaryViewInfo.priority] or
 * [TemporaryViewInfo.windowTitle] while using the same ID.
 */
abstract class TemporaryViewDisplayController<T : TemporaryViewInfo, U : TemporaryViewLogger<T>>(
    internal val context: Context,
    internal val logger: U,
    internal val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    private val accessibilityManager: AccessibilityManager,
    private val configurationController: ConfigurationController,
    private val dumpManager: DumpManager,
    private val powerManager: PowerManager,
    @LayoutRes private val viewLayoutRes: Int,
    private val wakeLockBuilder: WakeLock.Builder,
    private val systemClock: SystemClock,
) : CoreStartable, Dumpable {
    /**
     * Window layout params that will be used as a starting point for the [windowLayoutParams] of
     * all subclasses.
     */
    internal val commonWindowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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

    /**
     * A list of the currently active views, ordered from highest priority in the beginning to
     * lowest priority at the end.
     *
     * Whenever the current view disappears, the next-priority view will be displayed if it's still
     * valid.
     */
    internal val activeViews: MutableList<DisplayInfo> = mutableListOf()

    private fun getCurrentDisplayInfo(): DisplayInfo? {
        return activeViews.getOrNull(0)
    }

    @CallSuper
    override fun start() {
        dumpManager.registerNormalDumpable(this)
    }

    /**
     * Displays the view with the provided [newInfo].
     *
     * This method handles inflating and attaching the view, then delegates to [updateView] to
     * display the correct information in the view.
     * @param onViewTimeout a runnable that runs after the view timeout.
     */
    @Synchronized
    fun displayView(newInfo: T, onViewTimeout: Runnable? = null) {
        val timeout = accessibilityManager.getRecommendedTimeoutMillis(
            newInfo.timeoutMs,
            // Not all views have controls so FLAG_CONTENT_CONTROLS might be superfluous, but
            // include it just to be safe.
            FLAG_CONTENT_ICONS or FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS
        )
        val timeExpirationMillis = systemClock.currentTimeMillis() + timeout

        val currentDisplayInfo = getCurrentDisplayInfo()

        // We're current displaying a chipbar with the same ID, we just need to update its info
        if (currentDisplayInfo != null && currentDisplayInfo.info.id == newInfo.id) {
            val view = checkNotNull(currentDisplayInfo.view) {
                "First item in activeViews list must have a valid view"
            }
            logger.logViewUpdate(newInfo)
            currentDisplayInfo.info = newInfo
            currentDisplayInfo.timeExpirationMillis = timeExpirationMillis
            updateTimeout(currentDisplayInfo, timeout, onViewTimeout)
            updateView(newInfo, view)
            return
        }

        val newDisplayInfo = DisplayInfo(
            info = newInfo,
            onViewTimeout = onViewTimeout,
            timeExpirationMillis = timeExpirationMillis,
            // Null values will be updated to non-null if/when this view actually gets displayed
            view = null,
            wakeLock = null,
            cancelViewTimeout = null,
        )

        // We're not displaying anything, so just render this new info
        if (currentDisplayInfo == null) {
            addCallbacks()
            activeViews.add(newDisplayInfo)
            showNewView(newDisplayInfo, timeout)
            return
        }

        // The currently displayed info takes higher priority than the new one.
        // So, just store the new one in case the current one disappears.
        if (currentDisplayInfo.info.priority > newInfo.priority) {
            logger.logViewAdditionDelayed(newInfo)
            // Remove any old information for this id (if it exists) and re-add it to the list in
            // the right priority spot
            removeFromActivesIfNeeded(newInfo.id)
            var insertIndex = 0
            while (insertIndex < activeViews.size &&
                activeViews[insertIndex].info.priority > newInfo.priority) {
                insertIndex++
            }
            activeViews.add(insertIndex, newDisplayInfo)
            return
        }

        // Else: The newInfo should be displayed and the currentInfo should be hidden
        hideView(currentDisplayInfo)
        // Remove any old information for this id (if it exists) and put this info at the beginning
        removeFromActivesIfNeeded(newDisplayInfo.info.id)
        activeViews.add(0, newDisplayInfo)
        showNewView(newDisplayInfo, timeout)
    }

    private fun showNewView(newDisplayInfo: DisplayInfo, timeout: Int) {
        logger.logViewAddition(newDisplayInfo.info)
        createAndAcquireWakeLock(newDisplayInfo)
        updateTimeout(newDisplayInfo, timeout, newDisplayInfo.onViewTimeout)
        inflateAndUpdateView(newDisplayInfo)
    }

    private fun createAndAcquireWakeLock(displayInfo: DisplayInfo) {
        // TODO(b/262009503): Migrate off of isScrenOn, since it's deprecated.
        val newWakeLock = if (!powerManager.isScreenOn) {
            // If the screen is off, fully wake it so the user can see the view.
            wakeLockBuilder
                .setTag(displayInfo.info.windowTitle)
                .setLevelsAndFlags(
                    PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP
                )
                .build()
        } else {
            // Per b/239426653, we want the view to show over the dream state.
            // If the screen is on, using screen bright level will leave screen on the dream
            // state but ensure the screen will not go off before wake lock is released.
            wakeLockBuilder
                .setTag(displayInfo.info.windowTitle)
                .setLevelsAndFlags(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)
                .build()
        }
        displayInfo.wakeLock = newWakeLock
        newWakeLock.acquire(displayInfo.info.wakeReason)
    }

    /**
     * Creates a runnable that will remove [displayInfo] in [timeout] ms from now.
     *
     * @param onViewTimeout an optional runnable that will be run if the view times out.
     * @return a runnable that, when run, will *cancel* the view's timeout.
     */
    private fun updateTimeout(displayInfo: DisplayInfo, timeout: Int, onViewTimeout: Runnable?) {
        val cancelViewTimeout = mainExecutor.executeDelayed(
            {
                removeView(displayInfo.info.id, REMOVAL_REASON_TIMEOUT)
                onViewTimeout?.run()
            },
            timeout.toLong()
        )

        displayInfo.onViewTimeout = onViewTimeout
        // Cancel old view timeout and re-set it.
        displayInfo.cancelViewTimeout?.run()
        displayInfo.cancelViewTimeout = cancelViewTimeout
    }

    /** Inflates a new view, updates it with [DisplayInfo.info], and adds the view to the window. */
    private fun inflateAndUpdateView(displayInfo: DisplayInfo) {
        val newInfo = displayInfo.info
        val newView = LayoutInflater
                .from(context)
                .inflate(viewLayoutRes, null) as ViewGroup
        displayInfo.view = newView

        // We don't need to hold on to the view controller since we never set anything additional
        // on it -- it will be automatically cleaned up when the view is detached.
        val newViewController = TouchableRegionViewController(newView, this::getTouchableRegion)
        newViewController.init()

        updateView(newInfo, newView)

        val paramsWithTitle = WindowManager.LayoutParams().also {
            it.copyFrom(windowLayoutParams)
            it.title = newInfo.windowTitle
        }
        newView.keepScreenOn = true
        windowManager.addView(newView, paramsWithTitle)
        animateViewIn(newView)
    }

    /** Removes then re-inflates the view. */
    @Synchronized
    private fun reinflateView() {
        val currentDisplayInfo = getCurrentDisplayInfo() ?: return

        val view = checkNotNull(currentDisplayInfo.view) {
            "First item in activeViews list must have a valid view"
        }
        windowManager.removeView(view)
        inflateAndUpdateView(currentDisplayInfo)
    }

    private val displayScaleListener = object : ConfigurationController.ConfigurationListener {
        override fun onDensityOrFontScaleChanged() {
            reinflateView()
        }
    }

    private fun addCallbacks() {
        configurationController.addCallback(displayScaleListener)
    }

    private fun removeCallbacks() {
        configurationController.removeCallback(displayScaleListener)
    }

    /**
     * Completely removes the view for the given [id], both visually and from our internal store.
     *
     * @param id the id of the device responsible of displaying the temp view.
     * @param removalReason a short string describing why the view was removed (timeout, state
     *     change, etc.)
     */
    @Synchronized
    fun removeView(id: String, removalReason: String) {
        logger.logViewRemoval(id, removalReason)

        val displayInfo = activeViews.firstOrNull { it.info.id == id }
        if (displayInfo == null) {
            logger.logViewRemovalIgnored(id, "View not found in list")
            return
        }

        val currentlyDisplayedView = activeViews[0]
        // Remove immediately (instead as part of the animation end runnable) so that if a new view
        // event comes in while this view is animating out, we still display the new view
        // appropriately.
        activeViews.remove(displayInfo)

        // No need to time the view out since it's already gone
        displayInfo.cancelViewTimeout?.run()

        if (displayInfo.view == null) {
            logger.logViewRemovalIgnored(id, "No view to remove")
            return
        }

        if (currentlyDisplayedView.info.id != id) {
            logger.logViewRemovalIgnored(id, "View isn't the currently displayed view")
            return
        }

        removeViewFromWindow(displayInfo, removalReason)

        // Prune anything that's already timed out before determining if we should re-display a
        // different chipbar.
        removeTimedOutViews()
        val newViewToDisplay = getCurrentDisplayInfo()

        if (newViewToDisplay != null) {
            val timeout = newViewToDisplay.timeExpirationMillis - systemClock.currentTimeMillis()
            // TODO(b/258019006): We may want to have a delay before showing the new view so
            // that the UI translation looks a bit smoother. But, we expect this to happen
            // rarely so it may not be worth the extra complexity.
            showNewView(newViewToDisplay, timeout.toInt())
        } else {
            removeCallbacks()
        }
    }

    /**
     * Hides the view from the window, but keeps [displayInfo] around in [activeViews] in case it
     * should be re-displayed later.
     */
    private fun hideView(displayInfo: DisplayInfo) {
        logger.logViewHidden(displayInfo.info)
        removeViewFromWindow(displayInfo)
    }

    private fun removeViewFromWindow(displayInfo: DisplayInfo, removalReason: String? = null) {
        val view = displayInfo.view
        if (view == null) {
            logger.logViewRemovalIgnored(displayInfo.info.id, "View is null")
            return
        }
        displayInfo.view = null // Need other places??
        animateViewOut(view, removalReason) {
            windowManager.removeView(view)
            displayInfo.wakeLock?.release(displayInfo.info.wakeReason)
        }
    }

    @Synchronized
    private fun removeTimedOutViews() {
        val invalidViews = activeViews
            .filter { it.timeExpirationMillis <
                systemClock.currentTimeMillis() + MIN_REQUIRED_TIME_FOR_REDISPLAY }

        invalidViews.forEach {
            activeViews.remove(it)
            logger.logViewExpiration(it.info)
        }
    }

    @Synchronized
    private fun removeFromActivesIfNeeded(id: String) {
        val toRemove = activeViews.find { it.info.id == id }
        toRemove?.let {
            it.cancelViewTimeout?.run()
            activeViews.remove(it)
        }
    }

    @Synchronized
    @CallSuper
    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Current time millis: ${systemClock.currentTimeMillis()}")
        pw.println("Active views size: ${activeViews.size}")
        activeViews.forEachIndexed { index, displayInfo ->
            pw.println("View[$index]:")
            pw.println("  info=${displayInfo.info}")
            pw.println("  hasView=${displayInfo.view != null}")
            pw.println("  timeExpiration=${displayInfo.timeExpirationMillis}")
        }
    }

    /**
     * A method implemented by subclasses to update [currentView] based on [newInfo].
     */
    abstract fun updateView(newInfo: T, currentView: ViewGroup)

    /**
     * Fills [outRect] with the touchable region of this view. This will be used by WindowManager
     * to decide which touch events go to the view.
     */
    abstract fun getTouchableRegion(view: View, outRect: Rect)

    /**
     * A method that can be implemented by subclasses to do custom animations for when the view
     * appears.
     */
    internal open fun animateViewIn(view: ViewGroup) {}

    /**
     * A method that can be implemented by subclasses to do custom animations for when the view
     * disappears.
     *
     * @param onAnimationEnd an action that *must* be run once the animation finishes successfully.
     */
    internal open fun animateViewOut(
        view: ViewGroup,
        removalReason: String? = null,
        onAnimationEnd: Runnable
    ) {
        onAnimationEnd.run()
    }

    /** A container for all the display-related state objects. */
    inner class DisplayInfo(
        /**
         * The view currently being displayed.
         *
         * Null if this info isn't currently being displayed.
         */
        var view: ViewGroup?,

        /** The info that should be displayed if/when this is the highest priority view. */
        var info: T,

        /**
         * The system time at which this display info should expire and never be displayed again.
         */
        var timeExpirationMillis: Long,

        /**
         * The wake lock currently held by this view. Must be released when the view disappears.
         *
         * Null if this info isn't currently being displayed.
         */
        var wakeLock: WakeLock?,

        /**
         * See [displayView].
         */
        var onViewTimeout: Runnable?,

        /**
         * A runnable that, when run, will cancel this view's timeout.
         *
         * Null if this info isn't currently being displayed.
         */
        var cancelViewTimeout: Runnable?,
    )
}

private const val REMOVAL_REASON_TIMEOUT = "TIMEOUT"
private const val MIN_REQUIRED_TIME_FOR_REDISPLAY = 1000

private data class IconInfo(
    val iconName: String,
    val icon: Drawable,
    /** True if [icon] is the app's icon, and false if [icon] is some generic default icon. */
    val isAppIcon: Boolean
)
