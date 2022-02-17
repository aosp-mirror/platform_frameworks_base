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

package com.android.systemui.idle

import android.content.res.Resources
import android.util.Log
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

/**
 * An algorithm that receives light sensor events, debounces the signals, and transforms into an
 * ambient light mode: light, dark, or undecided.
 *
 * More about the algorithm at go/titan-light-sensor-debouncer.
 */
class LightSensorEventsDebounceAlgorithm @Inject constructor(
    @Main private val executor: DelayableExecutor,
    @Main resources: Resources
) : AmbientLightModeMonitor.DebounceAlgorithm {
    companion object {
        private const val TAG = "LightDebounceAlgorithm"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }

    // The ambient mode is considered light mode when the light sensor value increases exceeding
    // this value.
    private val lightModeThreshold =
            resources.getInteger(R.integer.config_ambientLightModeThreshold)

    // The ambient mode is considered dark mode when the light sensor value drops below this
    // value.
    private val darkModeThreshold = resources.getInteger(R.integer.config_ambientDarkModeThreshold)

    // Each sample for calculating light mode contains light sensor events collected for this
    // duration of time in milliseconds.
    private val lightSamplingSpanMillis =
            resources.getInteger(R.integer.config_ambientLightModeSamplingSpanMillis)

    // Each sample for calculating dark mode contains light sensor events collected for this
    // duration of time in milliseconds.
    private val darkSamplingSpanMillis =
            resources.getInteger(R.integer.config_ambientDarkModeSamplingSpanMillis)

    // The calculations for light mode is performed at this frequency in milliseconds.
    private val lightSamplingFrequencyMillis =
            resources.getInteger(R.integer.config_ambientLightModeSamplingFrequencyMillis)

    // The calculations for dark mode is performed at this frequency in milliseconds.
    private val darkSamplingFrequencyMillis =
            resources.getInteger(R.integer.config_ambientDarkModeSamplingFrequencyMillis)

    // Registered callback, which gets triggered when the ambient light mode changes.
    private var callback: AmbientLightModeMonitor.Callback? = null

    // Queue of bundles used for calculating [isLightMode], ordered from oldest to latest.
    val bundlesQueueLightMode = ArrayList<ArrayList<Float>>()

    // Queue of bundles used for calculating [isDarkMode], ordered from oldest to latest
    val bundlesQueueDarkMode = ArrayList<ArrayList<Float>>()

    // The current ambient light mode.
    @AmbientLightModeMonitor.AmbientLightMode
    var mode = AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED
        set(value) {
            if (field == value) return
            field = value

            if (DEBUG) Log.d(TAG, "ambient light mode changed to $value")

            callback?.onChange(value)
        }

    // The latest claim of whether it should be light mode.
    var isLightMode = false
        set(value) {
            if (field == value) return
            field = value

            if (DEBUG) Log.d(TAG, "isLightMode: $value")

            mode = when {
                isDarkMode -> AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK
                value -> AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT
                else -> AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED
            }
        }

    // The latest claim of whether it should be dark mode.
    var isDarkMode = false
        set(value) {
            if (field == value) return
            field = value

            if (DEBUG) Log.d(TAG, "isDarkMode: $value")

            mode = when {
                value -> AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK
                isLightMode -> AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT
                else -> AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED
            }
        }

    // The latest average of the light mode bundle.
    var bundleAverageLightMode = 0.0
        set(value) {
            field = value

            if (DEBUG) Log.d(TAG, "light mode average: $value")

            isLightMode = value > lightModeThreshold
        }

    // The latest average of the dark mode bundle.
    var bundleAverageDarkMode = 0.0
        set(value) {
            field = value

            if (DEBUG) Log.d(TAG, "dark mode average: $value")

            isDarkMode = value < darkModeThreshold
        }

    // The latest bundle for calculating light mode claim.
    var bundleLightMode = ArrayList<Float>()
        set(value) {
            field = value

            val average = value.average()

            if (!average.isNaN()) {
                bundleAverageLightMode = average
            }
        }

    // The latest bundle for calculating dark mode claim.
    var bundleDarkMode = ArrayList<Float>()
        set(value) {
            field = value

            val average = value.average()

            if (!average.isNaN()) {
                bundleAverageDarkMode = average
            }
        }

    // The latest light level from light sensor event updates.
    var lightSensorLevel = 0.0f
        set(value) {
            field = value

            bundlesQueueLightMode.forEach { bundle -> bundle.add(value) }
            bundlesQueueDarkMode.forEach { bundle -> bundle.add(value) }
        }

    // Creates a new bundle that collects light sensor events for calculating the light mode claim,
    // and adds it to the end of the queue. It schedules a call to dequeue this bundle after
    // [LIGHT_SAMPLING_SPAN_MILLIS]. Once started, it also repeatedly calls itself at
    // [LIGHT_SAMPLING_FREQUENCY_MILLIS].
    val enqueueLightModeBundle: Runnable = object : Runnable {
        override fun run() {
            if (DEBUG) Log.d(TAG, "enqueueing a light mode bundle")

            bundlesQueueLightMode.add(ArrayList())

            executor.executeDelayed(dequeueLightModeBundle, lightSamplingSpanMillis.toLong())
            executor.executeDelayed(this, lightSamplingFrequencyMillis.toLong())
        }
    }

    // Creates a new bundle that collects light sensor events for calculating the dark mode claim,
    // and adds it to the end of the queue. It schedules a call to dequeue this bundle after
    // [DARK_SAMPLING_SPAN_MILLIS]. Once started, it also repeatedly calls itself at
    // [DARK_SAMPLING_FREQUENCY_MILLIS].
    val enqueueDarkModeBundle: Runnable = object : Runnable {
        override fun run() {
            if (DEBUG) Log.d(TAG, "enqueueing a dark mode bundle")

            bundlesQueueDarkMode.add(ArrayList())

            executor.executeDelayed(dequeueDarkModeBundle, darkSamplingSpanMillis.toLong())
            executor.executeDelayed(this, darkSamplingFrequencyMillis.toLong())
        }
    }

    // Collects the oldest bundle from the light mode bundles queue, and as a result triggering a
    // calculation of the light mode claim.
    val dequeueLightModeBundle: Runnable = object : Runnable {
        override fun run() {
            if (bundlesQueueLightMode.isEmpty()) return

            bundleLightMode = bundlesQueueLightMode.removeAt(0)

            if (DEBUG) Log.d(TAG, "dequeued a light mode bundle of size ${bundleLightMode.size}")
        }
    }

    // Collects the oldest bundle from the dark mode bundles queue, and as a result triggering a
    // calculation of the dark mode claim.
    val dequeueDarkModeBundle: Runnable = object : Runnable {
        override fun run() {
            if (bundlesQueueDarkMode.isEmpty()) return

            bundleDarkMode = bundlesQueueDarkMode.removeAt(0)

            if (DEBUG) Log.d(TAG, "dequeued a dark mode bundle of size ${bundleDarkMode.size}")
        }
    }

    /**
     * Start the algorithm.
     *
     * @param callback callback that gets triggered when the ambient light mode changes.
     */
    override fun start(callback: AmbientLightModeMonitor.Callback?) {
        if (DEBUG) Log.d(TAG, "start algorithm")

        if (callback == null) {
            if (DEBUG) Log.w(TAG, "callback is null")
            return
        }

        if (this.callback != null) {
            if (DEBUG) Log.w(TAG, "already started")
            return
        }

        this.callback = callback

        executor.execute(enqueueLightModeBundle)
        executor.execute(enqueueDarkModeBundle)
    }

    /**
     * Stop the algorithm.
     */
    override fun stop() {
        if (DEBUG) Log.d(TAG, "stop algorithm")

        if (callback == null) {
            if (DEBUG) Log.w(TAG, "haven't started")
            return
        }

        callback = null

        // Resets bundle queues.
        bundlesQueueLightMode.clear()
        bundlesQueueDarkMode.clear()

        // Resets ambient light mode.
        mode = AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED
    }

    /**
     * Update the light sensor event value.
     *
     * @param value light sensor update value.
     */
    override fun onUpdateLightSensorEvent(value: Float) {
        if (callback == null) {
            if (DEBUG) Log.w(TAG, "ignore light sensor event because algorithm not started")
            return
        }

        lightSensorLevel = value
    }
}