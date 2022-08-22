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

package com.android.systemui.statusbar.pipeline.wifi.domain.interactor

import android.net.wifi.WifiManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The business logic layer for the wifi icon.
 *
 * This interactor processes information from our data layer into information that the UI layer can
 * use.
 */
@SysUISingleton
class WifiInteractor @Inject constructor(
        repository: WifiRepository,
) {
    private val ssid: Flow<String?> = repository.wifiModel.map { info ->
        when {
            info == null -> null
            info.isPasspointAccessPoint || info.isOnlineSignUpForPasspointAccessPoint ->
                info.passpointProviderFriendlyName
            info.ssid != WifiManager.UNKNOWN_SSID -> info.ssid
            else -> null
        }
    }

    val hasActivityIn: Flow<Boolean> = combine(repository.wifiActivity, ssid) { activity, ssid ->
            activity.hasActivityIn && ssid != null
        }
}
