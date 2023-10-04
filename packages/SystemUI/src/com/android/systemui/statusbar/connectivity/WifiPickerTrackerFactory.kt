/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.SimpleClock
import androidx.lifecycle.Lifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.ThreadFactory
import com.android.systemui.util.time.SystemClock
import com.android.wifitrackerlib.WifiPickerTracker
import com.android.wifitrackerlib.WifiPickerTracker.WifiPickerTrackerCallback
import java.time.Clock
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Factory for creating [WifiPickerTracker] for SysUI.
 *
 * Uses the same time intervals as the Settings page for Wifi.
 */
@SysUISingleton
class WifiPickerTrackerFactory
@Inject
constructor(
    private val context: Context,
    private val wifiManager: WifiManager?,
    private val connectivityManager: ConnectivityManager,
    private val systemClock: SystemClock,
    @Main private val mainHandler: Handler,
    private val threadFactory: ThreadFactory,
) {
    private val clock: Clock =
        object : SimpleClock(ZoneOffset.UTC) {
            override fun millis(): Long {
                return systemClock.elapsedRealtime()
            }
        }
    val isSupported: Boolean
        get() = wifiManager != null

    /**
     * Creates a [WifiPickerTracker] instance.
     *
     * @param name a name to identify the worker thread used for [WifiPickerTracker] operations.
     * @return a new [WifiPickerTracker] or null if [WifiManager] is null.
     */
    fun create(
        lifecycle: Lifecycle,
        listener: WifiPickerTrackerCallback,
        name: String,
    ): WifiPickerTracker? {
        return if (wifiManager == null) {
            null
        } else
            WifiPickerTracker(
                lifecycle,
                context,
                wifiManager,
                connectivityManager,
                mainHandler,
                // WifiPickerTracker can take tens of seconds to finish operations, so it can't use
                // the default background handler (it would block all other background operations).
                // Use a custom handler instead.
                threadFactory.buildHandlerOnNewThread("WifiPickerTracker-$name"),
                clock,
                MAX_SCAN_AGE_MILLIS,
                SCAN_INTERVAL_MILLIS,
                listener,
            )
    }

    companion object {
        /** Max age of tracked WifiEntries. */
        private const val MAX_SCAN_AGE_MILLIS: Long = 15000
        /** Interval between initiating WifiPickerTracker scans. */
        private const val SCAN_INTERVAL_MILLIS: Long = 10000
    }
}
