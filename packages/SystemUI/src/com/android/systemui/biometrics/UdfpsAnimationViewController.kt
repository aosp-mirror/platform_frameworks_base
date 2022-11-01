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
package com.android.systemui.biometrics

import android.animation.ValueAnimator
import android.graphics.PointF
import android.graphics.RectF
import com.android.systemui.Dumpable
import com.android.systemui.animation.Interpolators
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionListener
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.util.ViewController
import java.io.PrintWriter

/**
 * Handles:
 * 1. registering for listeners when its view is attached and unregistering on view detached
 * 2. pausing UDFPS when FingerprintManager may still be running but we temporarily want to hide
 * the affordance. this allows us to fade the view in and out nicely (see shouldPauseAuth)
 * 3. sending events to its view including:
 * - enabling and disabling of the UDFPS display mode
 * - sensor position changes
 * - doze time event
 */
abstract class UdfpsAnimationViewController<T : UdfpsAnimationView>(
    view: T,
    protected val statusBarStateController: StatusBarStateController,
    protected val panelExpansionStateManager: PanelExpansionStateManager,
    protected val dialogManager: SystemUIDialogManager,
    private val dumpManager: DumpManager
) : ViewController<T>(view), Dumpable {

    protected abstract val tag: String

    private val view: T
        get() = mView!!

    private var dialogAlphaAnimator: ValueAnimator? = null
    private val dialogListener = SystemUIDialogManager.Listener { runDialogAlphaAnimator() }

    private val panelExpansionListener = PanelExpansionListener { event ->
        // Notification shade can be expanded but not visible (fraction: 0.0), for example
        // when a heads-up notification (HUN) is showing.
        notificationShadeVisible = event.expanded && event.fraction > 0f
        view.onExpansionChanged(event.fraction)
        updatePauseAuth()
    }

    /** If the notification shade is visible. */
    var notificationShadeVisible: Boolean = false

    /**
     * The amount of translation needed if the view currently requires the user to touch
     * somewhere other than the exact center of the sensor. For example, this can happen
     * during guided enrollment.
     */
    open val touchTranslation: PointF = PointF(0f, 0f)

    /**
     * X-Padding to add to left and right of the sensor rectangle area to increase the size of our
     * window to draw within.
     */
    open val paddingX: Int = 0

    /**
     * Y-Padding to add to top and bottom of the sensor rectangle area to increase the size of our
     * window to draw within.
     */
    open val paddingY: Int = 0

    open fun updateAlpha() {
        view.updateAlpha()
    }

    fun runDialogAlphaAnimator() {
        val hideAffordance = dialogManager.shouldHideAffordance()
        dialogAlphaAnimator?.cancel()
        dialogAlphaAnimator = ValueAnimator.ofFloat(
                view.calculateAlpha() / 255f,
                if (hideAffordance) 0f else 1f)
                .apply {
            duration = if (hideAffordance) 83L else 200L
            interpolator = if (hideAffordance) Interpolators.LINEAR else Interpolators.ALPHA_IN

            addUpdateListener { animatedValue ->
                view.setDialogSuggestedAlpha(animatedValue.animatedValue as Float)
                updateAlpha()
                updatePauseAuth()
            }
            start()
        }
    }

    override fun onViewAttached() {
        panelExpansionStateManager.addExpansionListener(panelExpansionListener)
        dialogManager.registerListener(dialogListener)
        dumpManager.registerDumpable(dumpTag, this)
    }

    override fun onViewDetached() {
        panelExpansionStateManager.removeExpansionListener(panelExpansionListener)
        dialogManager.unregisterListener(dialogListener)
        dumpManager.unregisterDumpable(dumpTag)
    }

    /**
     * in some cases, onViewAttached is called for the newly added view using an instance of
     * this controller before onViewDetached is called on the previous view, so we must have a
     * unique [dumpTag] per instance of this class.
     */
    private val dumpTag = "$tag ($this)"

    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("mNotificationShadeVisible=$notificationShadeVisible")
        pw.println("shouldPauseAuth()=" + shouldPauseAuth())
        pw.println("isPauseAuth=" + view.isPauseAuth)
        pw.println("dialogSuggestedAlpha=" + view.dialogSuggestedAlpha)
    }

    /**
     * Returns true if the fingerprint manager is running, but we want to temporarily pause
     * authentication.
     */
    open fun shouldPauseAuth(): Boolean {
        return notificationShadeVisible || dialogManager.shouldHideAffordance()
    }

    /**
     * Send pause auth update to our view.
     */
    fun updatePauseAuth() {
        if (view.setPauseAuth(shouldPauseAuth())) {
            view.postInvalidate()
        }
    }

    /**
     * Send sensor position change to our view. This rect contains paddingX and paddingY.
     */
    fun onSensorRectUpdated(sensorRect: RectF) {
        view.onSensorRectUpdated(sensorRect)
    }

    /**
     * Send dozeTimeTick to view in case it wants to handle its burn-in offset.
     */
    fun dozeTimeTick() {
        if (view.dozeTimeTick()) {
            view.postInvalidate()
        }
    }

    /**
     * The display began transitioning into the UDFPS mode and the fingerprint manager started
     * authenticating.
     */
    fun onDisplayConfiguring() {
        view.onDisplayConfiguring()
        view.postInvalidate()
    }

    /**
     * The display transitioned away from the UDFPS mode and the fingerprint manager stopped
     * authenticating.
     */
    fun onDisplayUnconfigured() {
        view.onDisplayUnconfigured()
        view.postInvalidate()
    }

    /**
     * Whether to listen for touches outside of the view.
     */
    open fun listenForTouchesOutsideView(): Boolean = false

    /**
     * Called on touches outside of the view if listenForTouchesOutsideView returns true
     */
    open fun onTouchOutsideView() {}

    /**
     * Called when a view should announce an accessibility event.
     */
    open fun doAnnounceForAccessibility(str: String) {}
}
