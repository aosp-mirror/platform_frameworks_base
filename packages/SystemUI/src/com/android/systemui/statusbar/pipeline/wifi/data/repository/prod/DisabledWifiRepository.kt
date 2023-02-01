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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.prod

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.RealWifiRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of wifi repository used when wifi is permanently disabled on the device.
 *
 * This repo should only exist when [WifiManager] is null, which means that we can never fetch any
 * wifi information.
 */
@SysUISingleton
class DisabledWifiRepository @Inject constructor() : RealWifiRepository {
    override val isWifiEnabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override val isWifiDefault: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override val wifiNetwork: StateFlow<WifiNetworkModel> = MutableStateFlow(NETWORK).asStateFlow()

    override val wifiActivity: StateFlow<DataActivityModel> =
        MutableStateFlow(ACTIVITY).asStateFlow()

    companion object {
        private val NETWORK = WifiNetworkModel.Unavailable
        private val ACTIVITY = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
    }
}
