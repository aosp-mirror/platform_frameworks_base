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

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Rect
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.DOZING_MIGRATION_1
import com.android.systemui.flags.Flags.REGION_SAMPLING
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.dagger.KeyguardLargeClockLog
import com.android.systemui.log.dagger.KeyguardSmallClockLog
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockFaceController
import com.android.systemui.plugins.ClockTickRate
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.shared.regionsampling.RegionSampler
import com.android.systemui.plugins.WeatherData
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Controller for a Clock provided by the registry and used on the keyguard. Instantiated by
 * [KeyguardClockSwitchController]. Functionality is forked from [AnimatableClockController].
 */
open class ClockEventController
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val batteryController: BatteryController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val configurationController: ConfigurationController,
    @Main private val resources: Resources,
    private val context: Context,
    @Main private val mainExecutor: DelayableExecutor,
    @Background private val bgExecutor: Executor,
    @KeyguardSmallClockLog private val smallLogBuffer: LogBuffer?,
    @KeyguardLargeClockLog private val largeLogBuffer: LogBuffer?,
    private val featureFlags: FeatureFlags
) {
    var clock: ClockController? = null
        set(value) {
            field = value
            if (value != null) {
                smallLogBuffer?.log(TAG, DEBUG, {}, { "New Clock" })
                value.smallClock.logBuffer = smallLogBuffer
                largeLogBuffer?.log(TAG, DEBUG, {}, { "New Clock" })
                value.largeClock.logBuffer = largeLogBuffer

                value.initialize(resources, dozeAmount, 0f)

                if (regionSamplingEnabled) {
                    clock?.smallClock?.view?.addOnLayoutChangeListener(mLayoutChangedListener)
                    clock?.largeClock?.view?.addOnLayoutChangeListener(mLayoutChangedListener)
                } else {
                    updateColors()
                }
                updateFontSizes()
                updateTimeListeners()
            }
        }

    private var isDozing = false
        private set

    private var isCharging = false
    private var dozeAmount = 0f
    private var isKeyguardVisible = false
    private var isRegistered = false
    private var disposableHandle: DisposableHandle? = null
    private val regionSamplingEnabled = featureFlags.isEnabled(REGION_SAMPLING)

    private val mLayoutChangedListener =
        object : View.OnLayoutChangeListener {

            override fun onLayoutChange(
                view: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                view?.removeOnLayoutChangeListener(this)

                val parent = (view?.parent) as FrameLayout

                // don't pass in negative bounds when clocks are in transition state
                if (view.locationOnScreen[0] < 0 || view.locationOnScreen[1] < 0) {
                    return
                }

                val currentViewRect = Rect(left, top, right, bottom)
                val oldViewRect = Rect(oldLeft, oldTop, oldRight, oldBottom)

                if (currentViewRect.width() != oldViewRect.width() ||
                    currentViewRect.height() != oldViewRect.height()) {
                    updateRegionSampler(view)
                }
            }
        }

    private fun updateColors() {
        val wallpaperManager = WallpaperManager.getInstance(context)
        if (regionSamplingEnabled && !wallpaperManager.lockScreenWallpaperExists()) {
            if (regionSampler != null) {
                if (regionSampler?.sampledView == clock?.smallClock?.view) {
                    smallClockIsDark = regionSampler!!.currentRegionDarkness().isDark
                    clock?.smallClock?.events?.onRegionDarknessChanged(smallClockIsDark)
                    return
                } else if (regionSampler?.sampledView == clock?.largeClock?.view) {
                    largeClockIsDark = regionSampler!!.currentRegionDarkness().isDark
                    clock?.largeClock?.events?.onRegionDarknessChanged(largeClockIsDark)
                    return
                }
            }
        }

        val isLightTheme = TypedValue()
        context.theme.resolveAttribute(android.R.attr.isLightTheme, isLightTheme, true)
        smallClockIsDark = isLightTheme.data == 0
        largeClockIsDark = isLightTheme.data == 0

        clock?.smallClock?.events?.onRegionDarknessChanged(smallClockIsDark)
        clock?.largeClock?.events?.onRegionDarknessChanged(largeClockIsDark)
    }

    private fun updateRegionSampler(sampledRegion: View) {
        regionSampler?.stopRegionSampler()
        regionSampler =
            createRegionSampler(
                    sampledRegion,
                    mainExecutor,
                    bgExecutor,
                    regionSamplingEnabled,
                    ::updateColors
                )
                ?.apply { startRegionSampler() }

        updateColors()
    }

    protected open fun createRegionSampler(
        sampledView: View?,
        mainExecutor: Executor?,
        bgExecutor: Executor?,
        regionSamplingEnabled: Boolean,
        updateColors: () -> Unit
    ): RegionSampler? {
        return RegionSampler(
            sampledView,
            mainExecutor,
            bgExecutor,
            regionSamplingEnabled,
            updateColors
        )
    }

    var regionSampler: RegionSampler? = null
    var smallTimeListener: TimeListener? = null
    var largeTimeListener: TimeListener? = null
    val shouldTimeListenerRun: Boolean
        get() = isKeyguardVisible && dozeAmount < DOZE_TICKRATE_THRESHOLD

    private var smallClockIsDark = true
    private var largeClockIsDark = true

    private val configListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() {
                clock?.events?.onColorPaletteChanged(resources)
                updateColors()
            }

            override fun onDensityOrFontScaleChanged() {
                updateFontSizes()
            }
        }

    private val batteryCallback =
        object : BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                if (isKeyguardVisible && !isCharging && charging) {
                    clock?.animations?.charge()
                }
                isCharging = charging
            }
        }

    private val localeBroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                clock?.events?.onLocaleChanged(Locale.getDefault())
            }
        }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onKeyguardVisibilityChanged(visible: Boolean) {
                isKeyguardVisible = visible
                if (!featureFlags.isEnabled(DOZING_MIGRATION_1)) {
                    if (!isKeyguardVisible) {
                        clock?.animations?.doze(if (isDozing) 1f else 0f)
                    }
                }

                smallTimeListener?.update(shouldTimeListenerRun)
                largeTimeListener?.update(shouldTimeListenerRun)
            }

            override fun onTimeFormatChanged(timeFormat: String?) {
                clock?.events?.onTimeFormatChanged(DateFormat.is24HourFormat(context))
            }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                clock?.events?.onTimeZoneChanged(timeZone)
            }

            override fun onUserSwitchComplete(userId: Int) {
                clock?.events?.onTimeFormatChanged(DateFormat.is24HourFormat(context))
            }

            override fun onWeatherDataChanged(data: WeatherData) {
                clock?.events?.onWeatherDataChanged(data)
            }
        }

    fun registerListeners(parent: View) {
        if (isRegistered) {
            return
        }
        isRegistered = true

        broadcastDispatcher.registerReceiver(
            localeBroadcastReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED)
        )
        configurationController.addCallback(configListener)
        batteryController.addCallback(batteryCallback)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        disposableHandle =
            parent.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    listenForDozing(this)
                    if (featureFlags.isEnabled(DOZING_MIGRATION_1)) {
                        listenForDozeAmountTransition(this)
                        listenForAnyStateToAodTransition(this)
                    } else {
                        listenForDozeAmount(this)
                    }
                }
            }
        smallTimeListener?.update(shouldTimeListenerRun)
        largeTimeListener?.update(shouldTimeListenerRun)
    }

    fun unregisterListeners() {
        if (!isRegistered) {
            return
        }
        isRegistered = false

        disposableHandle?.dispose()
        broadcastDispatcher.unregisterReceiver(localeBroadcastReceiver)
        configurationController.removeCallback(configListener)
        batteryController.removeCallback(batteryCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        regionSampler?.stopRegionSampler()
        smallTimeListener?.stop()
        largeTimeListener?.stop()
    }

    private fun updateTimeListeners() {
        smallTimeListener?.stop()
        largeTimeListener?.stop()

        smallTimeListener = null
        largeTimeListener = null

        clock?.smallClock?.let {
            smallTimeListener = TimeListener(it, mainExecutor)
            smallTimeListener?.update(shouldTimeListenerRun)
        }
        clock?.largeClock?.let {
            largeTimeListener = TimeListener(it, mainExecutor)
            largeTimeListener?.update(shouldTimeListenerRun)
        }
    }

    private fun updateFontSizes() {
        clock
            ?.smallClock
            ?.events
            ?.onFontSettingChanged(
                resources.getDimensionPixelSize(R.dimen.small_clock_text_size).toFloat()
            )
        clock
            ?.largeClock
            ?.events
            ?.onFontSettingChanged(
                resources.getDimensionPixelSize(R.dimen.large_clock_text_size).toFloat()
            )
    }

    private fun handleDoze(doze: Float) {
        dozeAmount = doze
        clock?.animations?.doze(dozeAmount)
        smallTimeListener?.update(doze < DOZE_TICKRATE_THRESHOLD)
        largeTimeListener?.update(doze < DOZE_TICKRATE_THRESHOLD)
    }

    @VisibleForTesting
    internal fun listenForDozeAmount(scope: CoroutineScope): Job {
        return scope.launch { keyguardInteractor.dozeAmount.collect { handleDoze(it) } }
    }

    @VisibleForTesting
    internal fun listenForDozeAmountTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor.dozeAmountTransition.collect { handleDoze(it.value) }
        }
    }

    /**
     * When keyguard is displayed again after being gone, the clock must be reset to full dozing.
     */
    @VisibleForTesting
    internal fun listenForAnyStateToAodTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor.anyStateToAodTransition
                .filter { it.transitionState == TransitionState.FINISHED }
                .collect { handleDoze(1f) }
        }
    }

    @VisibleForTesting
    internal fun listenForDozing(scope: CoroutineScope): Job {
        return scope.launch {
            combine(
                    keyguardInteractor.dozeAmount,
                    keyguardInteractor.isDozing,
                ) { localDozeAmount, localIsDozing ->
                    localDozeAmount > dozeAmount || localIsDozing
                }
                .collect { localIsDozing -> isDozing = localIsDozing }
        }
    }

    class TimeListener(val clockFace: ClockFaceController, val executor: DelayableExecutor) {
        val predrawListener =
            ViewTreeObserver.OnPreDrawListener {
                clockFace.events.onTimeTick()
                true
            }

        val secondsRunnable =
            object : Runnable {
                override fun run() {
                    if (!isRunning) {
                        return
                    }

                    executor.executeDelayed(this, 990)
                    clockFace.events.onTimeTick()
                }
            }

        var isRunning: Boolean = false
            private set

        fun start() {
            if (isRunning) {
                return
            }

            isRunning = true
            when (clockFace.events.tickRate) {
                ClockTickRate.PER_MINUTE -> {
                    /* Handled by KeyguardClockSwitchController */
                }
                ClockTickRate.PER_SECOND -> executor.execute(secondsRunnable)
                ClockTickRate.PER_FRAME -> {
                    clockFace.view.viewTreeObserver.addOnPreDrawListener(predrawListener)
                    clockFace.view.invalidate()
                }
            }
        }

        fun stop() {
            if (!isRunning) {
                return
            }

            isRunning = false
            clockFace.view.viewTreeObserver.removeOnPreDrawListener(predrawListener)
        }

        fun update(shouldRun: Boolean) = if (shouldRun) start() else stop()
    }

    companion object {
        private val TAG = ClockEventController::class.simpleName!!
        private val DOZE_TICKRATE_THRESHOLD = 0.99f
    }
}
