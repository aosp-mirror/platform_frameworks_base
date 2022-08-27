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
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.systemui.ripple.RippleShader.RippleShape

/**
 * WirelessChargingView that encapsulates the current and next [WirelessChargingLayout]s.
 */
class WirelessChargingView (
        context: Context,
        transmittingBatteryLevel: Int,
        batteryLevel: Int,
        isDozing: Boolean,
        rippleShape: RippleShape,
        val duration: Long,
) {
    companion object {
        @JvmStatic
        fun create(
                context: Context,
                transmittingBatteryLevel: Int,
                batteryLevel: Int,
                isDozing: Boolean,
                rippleShape: RippleShape,
                duration: Long = DEFAULT_DURATION
        ): WirelessChargingView {
            return WirelessChargingView(context, transmittingBatteryLevel, batteryLevel, isDozing,
                    rippleShape, duration)
        }

        @JvmStatic
        fun createWithNoBatteryLevel(
                context: Context,
                rippleShape: RippleShape,
                duration: Long = DEFAULT_DURATION
        ): WirelessChargingView {
            return create(context,
                    UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, false, rippleShape,
                    duration)
        }
    }

    val wmLayoutParams = WindowManager.LayoutParams().apply {
        height = WindowManager.LayoutParams.MATCH_PARENT
        width = WindowManager.LayoutParams.MATCH_PARENT
        format = PixelFormat.TRANSLUCENT
        type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
        title = "Charging Animation"
        layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        packageName = context.applicationContext.opPackageName
        setTrustedOverlay()
    }

    private val wirelessChargingLayout: WirelessChargingLayout =
            WirelessChargingLayout(context, transmittingBatteryLevel, batteryLevel, isDozing,
                    rippleShape, duration)
    fun getWirelessChargingLayout(): View = wirelessChargingLayout

    internal enum class WirelessChargingRippleEvent(private val mInt: Int) : UiEventEnum {
        @UiEvent(doc = "Wireless charging ripple effect played")
        WIRELESS_RIPPLE_PLAYED(830);

        override fun getId(): Int {
            return mInt
        }
    }
}
