/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade

import android.hardware.display.AmbientDisplayConfiguration
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.Dumpable
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.LOW_PENALTY
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import java.io.PrintWriter
import javax.inject.Inject

/**
 * If tap and/or double tap to wake is enabled, this gestureListener will wake the display on
 * tap/double tap when the device is pulsing (AoD2) or transitioning to AoD. Taps are gated by the
 * proximity sensor and falsing manager.
 *
 * Touches go through the [NotificationShadeWindowViewController] when the device is dozing but the
 * screen is still ON and not in the true AoD display state. When the device is in the true AoD
 * display state, wake-ups are handled by [com.android.systemui.doze.DozeSensors].
 */
@CentralSurfacesComponent.CentralSurfacesScope
class PulsingGestureListener @Inject constructor(
        private val notificationShadeWindowView: NotificationShadeWindowView,
        private val falsingManager: FalsingManager,
        private val dockManager: DockManager,
        private val centralSurfaces: CentralSurfaces,
        private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
        private val statusBarStateController: StatusBarStateController,
        private val shadeLogger: ShadeLogger,
        tunerService: TunerService,
        dumpManager: DumpManager
) : GestureDetector.SimpleOnGestureListener(), Dumpable {
    private var doubleTapEnabled = false
    private var singleTapEnabled = false

    init {
        val tunable = Tunable { key: String?, _: String? ->
            when (key) {
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE ->
                    doubleTapEnabled = ambientDisplayConfiguration.doubleTapGestureEnabled(
                            UserHandle.USER_CURRENT)
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE ->
                    singleTapEnabled = ambientDisplayConfiguration.tapGestureEnabled(
                            UserHandle.USER_CURRENT)
            }
        }
        tunerService.addTunable(tunable,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE)

        dumpManager.registerDumpable(this)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val isNotDocked = !dockManager.isDocked
        shadeLogger.logSingleTapUp(statusBarStateController.isDozing, singleTapEnabled, isNotDocked)
        if (statusBarStateController.isDozing && singleTapEnabled && isNotDocked) {
            val proximityIsNotNear = !falsingManager.isProximityNear
            val isNotAFalseTap = !falsingManager.isFalseTap(LOW_PENALTY)
            shadeLogger.logSingleTapUpFalsingState(proximityIsNotNear, isNotAFalseTap)
            if (proximityIsNotNear && isNotAFalseTap) {
                shadeLogger.d("Single tap handled, requesting centralSurfaces.wakeUpIfDozing")
                centralSurfaces.wakeUpIfDozing(
                    SystemClock.uptimeMillis(),
                    notificationShadeWindowView,
                    "PULSING_SINGLE_TAP",
                    PowerManager.WAKE_REASON_TAP
                )
            }
            return true
        }
        shadeLogger.d("onSingleTapUp event ignored")
        return false
    }

    /**
     * Receives [MotionEvent.ACTION_DOWN], [MotionEvent.ACTION_MOVE], and [MotionEvent.ACTION_UP]
     * motion events for a double tap.
     */
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        // React to the [MotionEvent.ACTION_UP] event after double tap is detected. Falsing
        // checks MUST be on the ACTION_UP event.
        if (e.actionMasked == MotionEvent.ACTION_UP &&
                statusBarStateController.isDozing &&
                (doubleTapEnabled || singleTapEnabled) &&
                !falsingManager.isProximityNear &&
                !falsingManager.isFalseDoubleTap
        ) {
            centralSurfaces.wakeUpIfDozing(
                    SystemClock.uptimeMillis(),
                    notificationShadeWindowView,
                    "PULSING_DOUBLE_TAP",
                    PowerManager.WAKE_REASON_TAP
            )
            return true
        }
        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("singleTapEnabled=$singleTapEnabled")
        pw.println("doubleTapEnabled=$doubleTapEnabled")
        pw.println("isDocked=${dockManager.isDocked}")
        pw.println("isProxCovered=${falsingManager.isProximityNear}")
    }
}
