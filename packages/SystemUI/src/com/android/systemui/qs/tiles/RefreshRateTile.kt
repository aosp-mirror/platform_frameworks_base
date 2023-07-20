/*
 * Copyright (C) 2020 The Android Open Source Project
 * Copyright (C) 2021-2022 AOSP-Krypton Project
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
 * limitations under the License
 */

package com.android.systemui.qs.tiles

import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.DeviceConfig
import android.provider.Settings.System.MIN_REFRESH_RATE
import android.provider.Settings.System.PEAK_REFRESH_RATE
import android.service.quicksettings.Tile
import android.util.Log
import android.view.Display
import android.view.View

import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.logging.MetricsLogger
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.State
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.util.settings.SystemSettings

import javax.inject.Inject

class RefreshRateTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val systemSettings: SystemSettings,
) : QSTileImpl<State>(
    host,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    private val settingsObserver = SettingsObserver()
    private val deviceConfigListener = DeviceConfigListener()

    private val supportedRefreshRates: List<Float>
    private val defaultPeakRefreshRateOverlay = mContext.resources.getInteger(
        com.android.internal.R.integer.config_defaultPeakRefreshRate
    ).toFloat()
    private var defaultPeakRefreshRate: Float = getDefaultPeakRefreshRate()

    private var ignoreSettingsChange = false

    init {
        logD("defaultPeakRefreshRate = $defaultPeakRefreshRate")
        val display: Display? = mContext.getSystemService(
            DisplayManager::class.java
        ).getDisplay(Display.DEFAULT_DISPLAY)

        val refreshRates = mutableListOf<Float>()
        if (display != null) {
            val mode = display.mode
            display.supportedModes.forEach {
                if (it.physicalWidth == mode.physicalWidth &&
                        it.physicalHeight == mode.physicalHeight
                ) {
                    val refreshRate = refreshRateRegex.find(
                        it.refreshRate.toString()
                    )?.value?.toFloat() ?: return@forEach
                    if (!refreshRates.contains(refreshRate)) {
                        refreshRates.add(refreshRate)
                    }
                }
            }
        } else {
            Log.e(TAG, "No valid default display available")
        }
        supportedRefreshRates = refreshRates.sorted()
        logD("supportedRefreshRates = $supportedRefreshRates")
    }

    private fun getDefaultPeakRefreshRate(): Float {
        val peakRefreshRate = DeviceConfig.getFloat(
            DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
            DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT,
            INVALID_REFRESH_RATE
        )
        return if (peakRefreshRate == INVALID_REFRESH_RATE) {
            defaultPeakRefreshRateOverlay
        } else {
            peakRefreshRate
        }
    }

    override fun newTileState() = State().apply {
        icon = ResourceIcon.get(R.drawable.ic_refresh_rate)
        label = getTileLabel()
        state = Tile.STATE_ACTIVE
    }

    override fun getLongClickIntent() = displaySettingsIntent

    override fun isAvailable() = supportedRefreshRates.size > 1

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.refresh_rate_tile_label)

    override protected fun handleInitialize() {
        logD("handleInitialize")
        deviceConfigListener.startListening()
        settingsObserver.observe()
    }

    override protected fun handleClick(view: View?) {
        logD("handleClick")
        cycleToNextMode()
        refreshState()
    }

    override protected fun handleUpdateState(state: State, arg: Any?) {
        state.secondaryLabel = getTitle()
        logD("handleUpdateState: secondaryLabel = ${state.secondaryLabel}")
    }

    override fun getMetricsCategory(): Int = MetricsEvent.XTENDED

    override protected fun handleDestroy() {
        logD("handleDestroy")
        deviceConfigListener.stopListening()
        settingsObserver.unobserve()
        super.handleDestroy()
    }

    private fun cycleToNextMode() {
        val minRate = systemSettings.getFloatForUser(
            MIN_REFRESH_RATE,
            NO_CONFIG,
            UserHandle.USER_CURRENT
        )
        val maxRate = systemSettings.getFloatForUser(
            PEAK_REFRESH_RATE,
            defaultPeakRefreshRate,
            UserHandle.USER_CURRENT
        )
        logD("cycleToNextMode: minRate = $minRate, maxRate = $maxRate")
        when {
            minRate >= NO_CONFIG && maxRate > minRate -> {
                // Auto mode, cycle to force default
                updateSettings(DEFAULT_REFRESH_RATE, DEFAULT_REFRESH_RATE)
            }
            minRate >= NO_CONFIG && minRate < supportedRefreshRates.last() -> {
                // Intermediate mode, cycle to next higher mode
                val newMinRate = supportedRefreshRates.find {
                    it > minRate
                } ?: return
                updateSettings(newMinRate, newMinRate)
            }
            minRate >= supportedRefreshRates.last() -> {
                // Peak mode, cycle to auto mode
                updateSettings(DEFAULT_REFRESH_RATE, supportedRefreshRates.last())
            }
        }
    }

    private fun updateSettings(minRate: Float, maxRate: Float) {
        logD("updateSettings: minRate = $minRate, maxRate = $maxRate")
        ignoreSettingsChange = true
        systemSettings.putFloatForUser(
            MIN_REFRESH_RATE,
            minRate,
            UserHandle.USER_CURRENT
        )
        systemSettings.putFloatForUser(
            PEAK_REFRESH_RATE,
            maxRate,
            UserHandle.USER_CURRENT
        )
        ignoreSettingsChange = false
    }

    private fun getTitle(): String {
        val minRate = systemSettings.getFloatForUser(
            MIN_REFRESH_RATE,
            NO_CONFIG,
            UserHandle.USER_CURRENT
        )
        val maxRate = systemSettings.getFloatForUser(
            PEAK_REFRESH_RATE,
            defaultPeakRefreshRate,
            UserHandle.USER_CURRENT
        )
        logD("getTitle: minRate = $minRate, maxRate = $maxRate")
        return if (minRate >= NO_CONFIG && maxRate > minRate) {
            mContext.getString(
                R.string.refresh_rate_auto_mode_placeholder,
                (if (minRate < DEFAULT_REFRESH_RATE) DEFAULT_REFRESH_RATE else minRate).toInt(),
                maxRate.toInt(),
            )
        } else {
            mContext.getString(
                R.string.refresh_rate_forced_mode_placeholder,
                minRate.toInt()
            )
        }
    }

    private inner class SettingsObserver() : ContentObserver(mainHandler) {

        private var isObserving = false

        override fun onChange(selfChange: Boolean) {
            if (ignoreSettingsChange) return
            refreshState()
        }

        fun observe() {
            if (isObserving) return
            isObserving = true
            systemSettings.registerContentObserverForUser(
                MIN_REFRESH_RATE, this, UserHandle.USER_ALL)
            systemSettings.registerContentObserverForUser(
                PEAK_REFRESH_RATE, this, UserHandle.USER_ALL)
        }

        fun unobserve() {
            if (!isObserving) return
            isObserving = false
            systemSettings.unregisterContentObserver(this)
        }
    }

    private inner class DeviceConfigListener() :
            DeviceConfig.OnPropertiesChangedListener {

        fun startListening() {
            DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                {
                    mainHandler.post(it)
                } /* Executor */,
                this /* Listener */,
            )
        }

        fun stopListening() {
            DeviceConfig.removeOnPropertiesChangedListener(this)
        }

        override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
            // Got notified if any property has been changed in NAMESPACE_DISPLAY_MANAGER. The
            // KEY_PEAK_REFRESH_RATE_DEFAULT value could be added, changed, removed or unchanged.
            // Just force a UI update for any case.
            defaultPeakRefreshRate = getDefaultPeakRefreshRate()
            logD("onPropertiesChanged: defaultPeakRefreshRate = $defaultPeakRefreshRate")
            refreshState()
        }
    }

    companion object {
        private const val TAG = "RefreshRateTile"
        private val DEBUG: Boolean
            get() = Log.isLoggable(TAG, Log.DEBUG)

        private const val INVALID_REFRESH_RATE = -1f
        private const val NO_CONFIG = 0f
        private const val DEFAULT_REFRESH_RATE = 60f

        private val refreshRateRegex = Regex("[0-9]+")

        private val displaySettingsIntent = Intent().setComponent(
            ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$DisplaySettingsActivity",
            )
        )
        
        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}
