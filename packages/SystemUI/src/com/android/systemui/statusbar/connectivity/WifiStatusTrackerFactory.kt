/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.net.NetworkScoreManager
import android.net.wifi.WifiManager
import android.os.Handler

import com.android.settingslib.wifi.WifiStatusTracker
import com.android.systemui.dagger.qualifiers.Main

import javax.inject.Inject

/**
 * Factory class for [WifiStatusTracker] which lives in SettingsLib (and thus doesn't use Dagger).
 * This enables the constructors for NetworkControllerImpl and WifiSignalController to be slightly
 * nicer.
 */
internal class WifiStatusTrackerFactory @Inject constructor(
    private val mContext: Context,
    private val mWifiManager: WifiManager?,
    private val mNetworkScoreManager: NetworkScoreManager,
    private val mConnectivityManager: ConnectivityManager,
    @Main private val mMainHandler: Handler
) {
    fun createTracker(callback: Runnable?, bgHandler: Handler?): WifiStatusTracker {
        return WifiStatusTracker(mContext,
                mWifiManager,
                mNetworkScoreManager,
                mConnectivityManager,
                callback,
                mMainHandler,
                bgHandler)
    }
}
