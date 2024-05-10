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

import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.customization.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.DisplaySpecific
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.REGION_SAMPLING
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.KeyguardLargeClockLog
import com.android.systemui.log.dagger.KeyguardSmallClockLog
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockTickRate
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.plugins.clocks.ZenData.ZenMode
import com.android.systemui.res.R as SysuiR
import com.android.systemui.shared.regionsampling.RegionSampler
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.concurrency.DelayableExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import javax.inject.Inject

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
    @DisplaySpecific private val resources: Resources,
    private val context: Context,
    @Main private val mainExecutor: DelayableExecutor,
    @Background private val bgExecutor: Executor,
    @KeyguardSmallClockLog private val smallLogBuffer: LogBuffer?,
    @KeyguardLargeClockLog private val largeLogBuffer: LogBuffer?,
    private val featureFlags: FeatureFlags,
    private val zenModeController: ZenModeController,
) {
    var clock: ClockController? = null
        set(value) {
            smallClockOnAttachStateChangeListener?.let {
                field?.smallClock?.view?.removeOnAttachStateChangeListener(it)
                smallClockFrame?.viewTreeObserver
                        ?.removeOnGlobalLayoutListener(onGlobalLayoutListener)
            }
            largeClockOnAttachStateChangeListener?.let {
                field?.largeClock?.view?.removeOnAttachStateChangeListener(it)
            }

            field = value
            if (value != null) {
                smallLogBuffer?.log(TAG, DEBUG, {}, { "New Clock" })
                value.smallClock.messageBuffer = smallLogBuffer
                largeLogBuffer?.log(TAG, DEBUG, {}, { "New Clock" })
                value.largeClock.messageBuffer = largeLogBuffer

                value.initialize(resources, dozeAmount, 0f)

                if (!regionSamplingEnabled) {
                    updateColors()
                } else {
                    clock?.let {
                        smallRegionSampler = createRegionSampler(
                                it.smallClock.view,
                                mainExecutor,
                                bgExecutor,
                                regionSamplingEnabled,
                                isLockscreen = true,
                                ::updateColors
                        )?.apply { startRegionSampler() }

                        largeRegionSampler = createRegionSampler(
                                it.largeClock.view,
                                mainExecutor,
                                bgExecutor,
                                regionSamplingEnabled,
                                isLockscreen = true,
                                ::updateColors
                        )?.apply { startRegionSampler() }

                        updateColors()
                    }
                }
                updateFontSizes()
                updateTimeListeners()
                weatherData?.let {
                    if (WeatherData.DEBUG) {
                        Log.i(TAG, "Pushing cached weather data to new clock: $it")
                    }
                    value.events.onWeatherDataChanged(it)
                }
                zenData?.let {
                    value.events.onZenDataChanged(it)
                }
                alarmData?.let {
                    value.events.onAlarmDataChanged(it)
                }

                smallClockOnAttachStateChangeListener =
                    object : OnAttachStateChangeListener {
                        var pastVisibility: Int? = null
                        override fun onViewAttachedToWindow(view: View) {
                            value.events.onTimeFormatChanged(DateFormat.is24HourFormat(context))
                            // Match the asing for view.parent's layout classes.
                            smallClockFrame = view.parent as ViewGroup
                            smallClockFrame?.let { frame ->
                                pastVisibility = frame.visibility
                                onGlobalLayoutListener = OnGlobalLayoutListener {
                                    val currentVisibility = frame.visibility
                                    if (pastVisibility != currentVisibility) {
                                        pastVisibility = currentVisibility
                                        // when small clock is visible,
                                        // recalculate bounds and sample
                                        if (currentVisibility == View.VISIBLE) {
                                            smallRegionSampler?.stopRegionSampler()
                                            smallRegionSampler?.startRegionSampler()
                                        }
                                    }
                                }
                                frame.viewTreeObserver
                                        .addOnGlobalLayoutListener(onGlobalLayoutListener)
                            }
                        }

                        override fun onViewDetachedFromWindow(p0: View) {
                            smallClockFrame?.viewTreeObserver
                                    ?.removeOnGlobalLayoutListener(onGlobalLayoutListener)
                        }
                }
                value.smallClock.view
                        .addOnAttachStateChangeListener(smallClockOnAttachStateChangeListener)

                largeClockOnAttachStateChangeListener =
                    object : OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(p0: View) {
                            value.events.onTimeFormatChanged(DateFormat.is24HourFormat(context))
                        }
                        override fun onViewDetachedFromWindow(p0: View) {
                        }
                }
                value.largeClock.view
                        .addOnAttachStateChangeListener(largeClockOnAttachStateChangeListener)
            }
        }

    @VisibleForTesting
    var smallClockOnAttachStateChangeListener: OnAttachStateChangeListener? = null
    @VisibleForTesting
    var largeClockOnAttachStateChangeListener: OnAttachStateChangeListener? = null
    private var smallClockFrame: ViewGroup? = null
    private var onGlobalLayoutListener: OnGlobalLayoutListener? = null

    private var isDozing = false
        private set

    private var isCharging = false
    private var dozeAmount = 0f
    private var isKeyguardVisible = false
    private var isRegistered = false
    private var disposableHandle: DisposableHandle? = null
    private val regionSamplingEnabled = featureFlags.isEnabled(REGION_SAMPLING)
    private var largeClockOnSecondaryDisplay = false

    private fun updateColors() {
        if (regionSamplingEnabled) {
            clock?.let { clock ->
                smallRegionSampler?.let {
                    val isRegionDark = it.currentRegionDarkness().isDark
                    clock.smallClock.events.onRegionDarknessChanged(isRegionDark)
                }

                largeRegionSampler?.let {
                    val isRegionDark = it.currentRegionDarkness().isDark
                    clock.largeClock.events.onRegionDarknessChanged(isRegionDark)
                }
            }
            return
        }

        val isLightTheme = TypedValue()
        context.theme.resolveAttribute(android.R.attr.isLightTheme, isLightTheme, true)
        val isRegionDark = isLightTheme.data == 0

        clock?.run {
            Log.i(TAG, "Region isDark: $isRegionDark")
            smallClock.events.onRegionDarknessChanged(isRegionDark)
            largeClock.events.onRegionDarknessChanged(isRegionDark)
        }
    }
    protected open fun createRegionSampler(
        sampledView: View,
        mainExecutor: Executor?,
        bgExecutor: Executor?,
        regionSamplingEnabled: Boolean,
        isLockscreen: Boolean,
        updateColors: () -> Unit
    ): RegionSampler? {
        return RegionSampler(
            sampledView,
            mainExecutor,
            bgExecutor,
            regionSamplingEnabled,
            isLockscreen,
        ) { updateColors() }
    }

    var smallRegionSampler: RegionSampler? = null
        private set
    var largeRegionSampler: RegionSampler? = null
        private set
    var smallTimeListener: TimeListener? = null
    var largeTimeListener: TimeListener? = null
    val shouldTimeListenerRun: Boolean
        get() = isKeyguardVisible && dozeAmount < DOZE_TICKRATE_THRESHOLD

    private var weatherData: WeatherData? = null
    private var zenData: ZenData? = null
    private var alarmData: AlarmData? = null

    private val configListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() {
                clock?.run { events.onColorPaletteChanged(resources) }
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
                    clock?.run {
                        smallClock.animations.charge()
                        largeClock.animations.charge()
                    }
                }
                isCharging = charging
            }
        }

    private val localeBroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                clock?.run { events.onLocaleChanged(Locale.getDefault()) }
            }
        }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onKeyguardVisibilityChanged(visible: Boolean) {
                isKeyguardVisible = visible
                if (!KeyguardShadeMigrationNssl.isEnabled) {
                    if (!isKeyguardVisible) {
                        clock?.run {
                            smallClock.animations.doze(if (isDozing) 1f else 0f)
                            largeClock.animations.doze(if (isDozing) 1f else 0f)
                        }
                    }
                }

                smallTimeListener?.update(shouldTimeListenerRun)
                largeTimeListener?.update(shouldTimeListenerRun)
            }

            override fun onTimeFormatChanged(timeFormat: String?) {
                clock?.run { events.onTimeFormatChanged(DateFormat.is24HourFormat(context)) }
            }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                clock?.run { events.onTimeZoneChanged(timeZone) }
            }

            override fun onUserSwitchComplete(userId: Int) {
                clock?.run { events.onTimeFormatChanged(DateFormat.is24HourFormat(context)) }
                zenModeCallback.onNextAlarmChanged()
            }

            override fun onWeatherDataChanged(data: WeatherData) {
                weatherData = data
                clock?.run { events.onWeatherDataChanged(data) }
            }
        }

    private val zenModeCallback = object : ZenModeController.Callback {
        override fun onZenChanged(zen: Int) {
            var mode = ZenMode.fromInt(zen)
            if (mode == null) {
                Log.e(TAG, "Failed to get zen mode from int: $zen")
                return
            }

            zenData = ZenData(
                mode,
                if (mode == ZenMode.OFF) SysuiR.string::dnd_is_off.name
                    else SysuiR.string::dnd_is_on.name
            ).also { data ->
                clock?.run { events.onZenDataChanged(data) }
            }
        }

        override fun onNextAlarmChanged() {
            val nextAlarmMillis = zenModeController.getNextAlarm()
            alarmData = AlarmData(
                if (nextAlarmMillis > 0) nextAlarmMillis else null,
                SysuiR.string::status_bar_alarm.name
            ).also { data ->
                clock?.run { events.onAlarmDataChanged(data) }
            }
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
        zenModeController.addCallback(zenModeCallback)
        disposableHandle =
            parent.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    listenForDozing(this)
                    if (KeyguardShadeMigrationNssl.isEnabled) {
                        listenForDozeAmountTransition(this)
                        listenForAnyStateToAodTransition(this)
                    } else {
                        listenForDozeAmount(this)
                    }
                }
            }
        smallTimeListener?.update(shouldTimeListenerRun)
        largeTimeListener?.update(shouldTimeListenerRun)

        // Query ZenMode data
        zenModeCallback.onZenChanged(zenModeController.zen)
        zenModeCallback.onNextAlarmChanged()
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
        zenModeController.removeCallback(zenModeCallback)
        smallRegionSampler?.stopRegionSampler()
        largeRegionSampler?.stopRegionSampler()
        smallTimeListener?.stop()
        largeTimeListener?.stop()
        clock?.smallClock?.view
                ?.removeOnAttachStateChangeListener(smallClockOnAttachStateChangeListener)
        smallClockFrame?.viewTreeObserver
                ?.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        clock?.largeClock?.view
                ?.removeOnAttachStateChangeListener(largeClockOnAttachStateChangeListener)
    }

    /**
     * Sets this clock as showing in a secondary display.
     *
     * Not that this is not necessarily needed, as we could get the displayId from [Context]
     * directly and infere [largeClockOnSecondaryDisplay] from the id being different than the
     * default display one. However, if we do so, current screenshot tests would not work, as they
     * pass an activity context always from the default display.
     */
    fun setLargeClockOnSecondaryDisplay(onSecondaryDisplay: Boolean) {
        largeClockOnSecondaryDisplay = onSecondaryDisplay
        updateFontSizes()
    }

    private fun updateTimeListeners() {
        smallTimeListener?.stop()
        largeTimeListener?.stop()

        smallTimeListener = null
        largeTimeListener = null

        clock?.let {
            smallTimeListener = TimeListener(it.smallClock, mainExecutor).apply {
                update(shouldTimeListenerRun)
            }
            largeTimeListener = TimeListener(it.largeClock, mainExecutor).apply {
                update(shouldTimeListenerRun)
            }
        }
    }

    private fun updateFontSizes() {
        clock?.run {
            smallClock.events.onFontSettingChanged(
                resources.getDimensionPixelSize(R.dimen.small_clock_text_size).toFloat()
            )
            largeClock.events.onFontSettingChanged(getLargeClockSizePx())
        }
    }

    private fun getLargeClockSizePx(): Float {
        return if (largeClockOnSecondaryDisplay) {
            resources.getDimensionPixelSize(R.dimen.presentation_clock_text_size).toFloat()
        } else {
            resources.getDimensionPixelSize(R.dimen.large_clock_text_size).toFloat()
        }
    }

    private fun handleDoze(doze: Float) {
        dozeAmount = doze
        clock?.run {
            smallClock.animations.doze(dozeAmount)
            largeClock.animations.doze(dozeAmount)
        }
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
            keyguardTransitionInteractor.transitionStepsToState(AOD)
                .filter { it.transitionState == TransitionState.STARTED }
                .filter { it.from != LOCKSCREEN }
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
            when (clockFace.config.tickRate) {
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
