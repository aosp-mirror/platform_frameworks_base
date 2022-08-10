/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.charging

import android.content.Context
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.UiEventLogger
import com.android.systemui.charging.WirelessChargingView.WirelessChargingRippleEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.ChargingLog
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

const val UNKNOWN_BATTERY_LEVEL = -1
const val DEFAULT_DURATION: Long = 1500

/**
 * Controls the wireless charging animation.
 */
@SysUISingleton
class WirelessChargingRippleController @Inject constructor(
        context: Context,
        private val uiEventLogger: UiEventLogger,
        @Main private val delayableExecutor: DelayableExecutor,
        @ChargingLog private val logBuffer: LogBuffer
) {
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE)
            as WindowManager

    @VisibleForTesting
    var wirelessChargingView: WirelessChargingView? = null
    private var callback: Callback? = null

    companion object {
        private const val TAG = "WirelessChargingRippleController"
    }

    /**
     * Shows the wireless charging view with the given delay.
     *
     * If there's already the animation is playing, the new request will be disregarded.
     * @param wirelessChargingView WirelessChargingView to display.
     * @param delay the start delay of the WirelessChargingView.
     * @param callback optional callback that is triggered on animations start and end.
     */
    fun show(wirelessChargingView: WirelessChargingView, delay: Long, callback: Callback? = null) {
        // Avoid multiple animation getting triggered.
        if (this.wirelessChargingView != null) {
            logBuffer.log(TAG, LogLevel.INFO, "Already playing animation, disregard " +
                    "$wirelessChargingView")
            return
        }

        this.wirelessChargingView = wirelessChargingView
        this.callback = callback

        logBuffer.log(TAG, LogLevel.DEBUG, "SHOW: $wirelessChargingView")
        delayableExecutor.executeDelayed({ showInternal() }, delay)

        logBuffer.log(TAG, LogLevel.DEBUG, "HIDE: $wirelessChargingView")
        delayableExecutor.executeDelayed({ hideInternal() }, delay + wirelessChargingView.duration)
    }

    private fun showInternal() {
        if (wirelessChargingView == null) {
            return
        }

        val chargingLayout = wirelessChargingView!!.getWirelessChargingLayout()
        try {
            callback?.onAnimationStarting()
            windowManager.addView(chargingLayout, wirelessChargingView!!.wmLayoutParams)
            uiEventLogger.log(WirelessChargingRippleEvent.WIRELESS_RIPPLE_PLAYED)
        } catch (e: WindowManager.BadTokenException) {
            logBuffer.log(TAG, LogLevel.ERROR, "Unable to add wireless charging view. $e")
        }
    }

    private fun hideInternal() {
        wirelessChargingView?.getWirelessChargingLayout().let {
            callback?.onAnimationEnded()
            if (it?.parent != null) {
                windowManager.removeViewImmediate(it)
            }
        }
        wirelessChargingView = null
        callback = null
    }

    /**
     * Callbacks that are triggered on animation events.
     */
    interface Callback {
        /** Triggered when the animation starts playing. */
        fun onAnimationStarting()
        /** Triggered when the animation ends playing. */
        fun onAnimationEnded()
    }
}
