/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.demomode

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Class to track the availability of [DemoMode]. Use this class to track the availability and
 * on/off state for DemoMode
 *
 * This class works by wrapping a content observer for the relevant keys related to DemoMode
 * availability and current on/off state, and triggering callbacks.
 */
abstract class DemoModeAvailabilityTracker(val context: Context) {
    var isInDemoMode = false
    var isDemoModeAvailable = false

    init {
        isInDemoMode = checkIsDemoModeOn()
        isDemoModeAvailable = checkIsDemoModeAllowed()
    }

    fun startTracking() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(
                Settings.Global.getUriFor(DEMO_MODE_ALLOWED), false, allowedObserver)
        resolver.registerContentObserver(
                Settings.Global.getUriFor(DEMO_MODE_ON), false, onObserver)
    }

    fun stopTracking() {
        val resolver = context.contentResolver
        resolver.unregisterContentObserver(allowedObserver)
        resolver.unregisterContentObserver(onObserver)
    }

    abstract fun onDemoModeAvailabilityChanged()
    abstract fun onDemoModeStarted()
    abstract fun onDemoModeFinished()

    private fun checkIsDemoModeAllowed(): Boolean {
        return Settings.Global
                .getInt(context.contentResolver, DEMO_MODE_ALLOWED, 0) != 0
    }

    private fun checkIsDemoModeOn(): Boolean {
        return Settings.Global.getInt(context.contentResolver, DEMO_MODE_ON, 0) != 0
    }

    private val allowedObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val allowed = checkIsDemoModeAllowed()
            if (DEBUG) {
                android.util.Log.d(TAG, "onChange: DEMO_MODE_ALLOWED changed: $allowed")
            }

            if (isDemoModeAvailable == allowed) {
                return
            }

            isDemoModeAvailable = allowed
            onDemoModeAvailabilityChanged()
        }
    }

    private val onObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val on = checkIsDemoModeOn()

            if (DEBUG) {
                android.util.Log.d(TAG, "onChange: DEMO_MODE_ON changed: $on")
            }

            if (isInDemoMode == on) {
                return
            }

            isInDemoMode = on
            if (on) {
                onDemoModeStarted()
            } else {
                onDemoModeFinished()
            }
        }
    }
}

private const val TAG = "DemoModeAvailabilityTracker"
private const val DEMO_MODE_ALLOWED = "sysui_demo_allowed"
private const val DEMO_MODE_ON = "sysui_tuner_demo_on"
private const val DEBUG = false
