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
package com.android.keyguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shared.regionsampling.RegionSamplingInstance
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback
import com.android.systemui.statusbar.policy.ConfigurationController
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Controller for a Clock provided by the registry and used on the keyguard. Instantiated by
 * [KeyguardClockSwitchController]. Functionality is forked from [AnimatableClockController].
 */
open class ClockEventController @Inject constructor(
    private val statusBarStateController: StatusBarStateController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val batteryController: BatteryController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val configurationController: ConfigurationController,
    @Main private val resources: Resources,
    private val context: Context,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor,
    private val featureFlags: FeatureFlags,
) {
    var clock: ClockController? = null
        set(value) {
            field = value
            if (value != null) {
                value.initialize(resources, dozeAmount, 0f)
                updateRegionSamplers(value)
            }
        }

    private var isDozing = false
        private set

    private var isCharging = false
    private var dozeAmount = 0f
    private var isKeyguardVisible = false

    private val regionSamplingEnabled =
            featureFlags.isEnabled(com.android.systemui.flags.Flags.REGION_SAMPLING)

    private fun updateColors() {
        if (regionSamplingEnabled && smallRegionSampler != null && largeRegionSampler != null) {
            smallClockIsDark = smallRegionSampler!!.currentRegionDarkness().isDark
            largeClockIsDark = largeRegionSampler!!.currentRegionDarkness().isDark
        } else {
            val isLightTheme = TypedValue()
            context.theme.resolveAttribute(android.R.attr.isLightTheme, isLightTheme, true)
            smallClockIsDark = isLightTheme.data == 0
            largeClockIsDark = isLightTheme.data == 0
        }

        clock?.smallClock?.events?.onRegionDarknessChanged(smallClockIsDark)
        clock?.largeClock?.events?.onRegionDarknessChanged(largeClockIsDark)
    }

    private fun updateRegionSamplers(currentClock: ClockController?) {
        smallRegionSampler?.stopRegionSampler()
        largeRegionSampler?.stopRegionSampler()

        smallRegionSampler = createRegionSampler(
                currentClock?.smallClock?.view,
                mainExecutor,
                bgExecutor,
                regionSamplingEnabled,
                ::updateColors
        )

        largeRegionSampler = createRegionSampler(
                currentClock?.largeClock?.view,
                mainExecutor,
                bgExecutor,
                regionSamplingEnabled,
                ::updateColors
        )

        smallRegionSampler!!.startRegionSampler()
        largeRegionSampler!!.startRegionSampler()

        updateColors()
    }

    protected open fun createRegionSampler(
            sampledView: View?,
            mainExecutor: Executor?,
            bgExecutor: Executor?,
            regionSamplingEnabled: Boolean,
            updateColors: () -> Unit
    ): RegionSamplingInstance {
        return RegionSamplingInstance(
            sampledView,
            mainExecutor,
            bgExecutor,
            regionSamplingEnabled,
            object : RegionSamplingInstance.UpdateColorCallback {
                override fun updateColors() {
                    updateColors()
                }
            })
    }

    var smallRegionSampler: RegionSamplingInstance? = null
    var largeRegionSampler: RegionSamplingInstance? = null

    private var smallClockIsDark = true
    private var largeClockIsDark = true

    private val configListener = object : ConfigurationController.ConfigurationListener {
        override fun onThemeChanged() {
            clock?.events?.onColorPaletteChanged(resources)
        }

        override fun onDensityOrFontScaleChanged() {
            clock?.events?.onFontSettingChanged()
        }
    }

    private val batteryCallback = object : BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            if (isKeyguardVisible && !isCharging && charging) {
                clock?.animations?.charge()
            }
            isCharging = charging
        }
    }

    private val localeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            clock?.events?.onLocaleChanged(Locale.getDefault())
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            clock?.animations?.doze(linear)

            isDozing = linear > dozeAmount
            dozeAmount = linear
        }
    }

    private val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onKeyguardVisibilityChanged(visible: Boolean) {
            isKeyguardVisible = visible
            if (!isKeyguardVisible) {
                clock?.animations?.doze(if (isDozing) 1f else 0f)
            }
        }

        override fun onTimeFormatChanged(timeFormat: String) {
            clock?.events?.onTimeFormatChanged(DateFormat.is24HourFormat(context))
        }

        override fun onTimeZoneChanged(timeZone: TimeZone) {
            clock?.events?.onTimeZoneChanged(timeZone)
        }

        override fun onUserSwitchComplete(userId: Int) {
            clock?.events?.onTimeFormatChanged(DateFormat.is24HourFormat(context))
        }
    }

    init {
        isDozing = statusBarStateController.isDozing
    }

    fun registerListeners() {
        dozeAmount = statusBarStateController.dozeAmount
        isDozing = statusBarStateController.isDozing || dozeAmount != 0f

        broadcastDispatcher.registerReceiver(
            localeBroadcastReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED)
        )
        configurationController.addCallback(configListener)
        batteryController.addCallback(batteryCallback)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        statusBarStateController.addCallback(statusBarStateListener)
        smallRegionSampler?.startRegionSampler()
        largeRegionSampler?.startRegionSampler()
    }

    fun unregisterListeners() {
        broadcastDispatcher.unregisterReceiver(localeBroadcastReceiver)
        configurationController.removeCallback(configListener)
        batteryController.removeCallback(batteryCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        statusBarStateController.removeCallback(statusBarStateListener)
        smallRegionSampler?.stopRegionSampler()
        largeRegionSampler?.stopRegionSampler()
    }

    /**
     * Dump information for debugging
     */
    fun dump(pw: PrintWriter) {
        pw.println(this)
        clock?.dump(pw)
        smallRegionSampler?.dump(pw)
        largeRegionSampler?.dump(pw)
    }

    companion object {
        private val TAG = ClockEventController::class.simpleName
        private const val FORMAT_NUMBER = 1234567890
    }
}
