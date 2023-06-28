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

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import kotlinx.coroutines.flow.StateFlow

/** Provides data related to the wifi state. */
interface WifiRepository {
    /** Observable for the current wifi enabled status. */
    val isWifiEnabled: StateFlow<Boolean>

    /** Observable for the current wifi default status. */
    val isWifiDefault: StateFlow<Boolean>

    /** Observable for the current wifi network. */
    val wifiNetwork: StateFlow<WifiNetworkModel>

    /** Observable for the current wifi network activity. */
    val wifiActivity: StateFlow<DataActivityModel>
}

/**
 * A no-op interface used for Dagger bindings.
 *
 * [WifiRepositorySwitcher] needs to inject the "real" wifi repository, which could either be the
 * full [WifiRepositoryImpl] or just [DisabledWifiRepository]. Having this interface lets us bind
 * [RealWifiRepository], and then [WifiRepositorySwitcher] will automatically get the correct real
 * repository.
 */
interface RealWifiRepository : WifiRepository
