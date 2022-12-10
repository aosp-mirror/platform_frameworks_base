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

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import android.telephony.CarrierConfigManager
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Connected
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.util.CarrierConfigTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

interface MobileIconInteractor {
    /** Only true if mobile is the default transport but is not validated, otherwise false */
    val isDefaultConnectionFailed: StateFlow<Boolean>

    /** True when telephony tells us that the data state is CONNECTED */
    val isDataConnected: StateFlow<Boolean>

    // TODO(b/256839546): clarify naming of default vs active
    /** True if we want to consider the data connection enabled */
    val isDefaultDataEnabled: StateFlow<Boolean>

    /** Observable for the data enabled state of this connection */
    val isDataEnabled: StateFlow<Boolean>

    /** Observable for RAT type (network type) indicator */
    val networkTypeIconGroup: StateFlow<MobileIconGroup>

    /** True if this line of service is emergency-only */
    val isEmergencyOnly: StateFlow<Boolean>

    /** Int describing the connection strength. 0-4 OR 1-5. See [numberOfLevels] */
    val level: StateFlow<Int>

    /** Based on [CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL], either 4 or 5 */
    val numberOfLevels: StateFlow<Int>
}

/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileIconInteractorImpl(
    @Application scope: CoroutineScope,
    defaultSubscriptionHasDataEnabled: StateFlow<Boolean>,
    defaultMobileIconMapping: StateFlow<Map<String, MobileIconGroup>>,
    defaultMobileIconGroup: StateFlow<MobileIconGroup>,
    override val isDefaultConnectionFailed: StateFlow<Boolean>,
    connectionRepository: MobileConnectionRepository,
) : MobileIconInteractor {
    private val connectionInfo = connectionRepository.connectionInfo

    override val isDataEnabled: StateFlow<Boolean> = connectionRepository.dataEnabled

    override val isDefaultDataEnabled = defaultSubscriptionHasDataEnabled

    /** Observable for the current RAT indicator icon ([MobileIconGroup]) */
    override val networkTypeIconGroup: StateFlow<MobileIconGroup> =
        combine(
                connectionInfo,
                defaultMobileIconMapping,
                defaultMobileIconGroup,
            ) { info, mapping, defaultGroup ->
                mapping[info.resolvedNetworkType.lookupKey] ?: defaultGroup
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultMobileIconGroup.value)

    override val isEmergencyOnly: StateFlow<Boolean> =
        connectionInfo
            .mapLatest { it.isEmergencyOnly }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val level: StateFlow<Int> =
        connectionInfo
            .mapLatest { connection ->
                // TODO: incorporate [MobileMappings.Config.alwaysShowCdmaRssi]
                if (connection.isGsm) {
                    connection.primaryLevel
                } else {
                    connection.cdmaLevel
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    /**
     * This will become variable based on [CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL]
     * once it's wired up inside of [CarrierConfigTracker]
     */
    override val numberOfLevels: StateFlow<Int> = MutableStateFlow(4)

    override val isDataConnected: StateFlow<Boolean> =
        connectionInfo
            .mapLatest { connection -> connection.dataConnectionState == Connected }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
}
