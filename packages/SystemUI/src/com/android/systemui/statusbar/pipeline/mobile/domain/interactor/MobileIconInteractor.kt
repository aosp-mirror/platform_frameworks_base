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
import com.android.systemui.statusbar.pipeline.mobile.data.model.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.util.CarrierConfigTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface MobileIconInteractor {
    // TODO(b/256839546): clarify naming of default vs active
    /** True if we want to consider the data connection enabled */
    val isDefaultDataEnabled: Flow<Boolean>

    /** Observable for the data enabled state of this connection */
    val isDataEnabled: Flow<Boolean>

    /** Observable for RAT type (network type) indicator */
    val networkTypeIconGroup: Flow<MobileIconGroup>

    /** True if this line of service is emergency-only */
    val isEmergencyOnly: Flow<Boolean>

    /** Int describing the connection strength. 0-4 OR 1-5. See [numberOfLevels] */
    val level: Flow<Int>

    /** Based on [CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL], either 4 or 5 */
    val numberOfLevels: Flow<Int>
}

/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
class MobileIconInteractorImpl(
    defaultSubscriptionHasDataEnabled: Flow<Boolean>,
    defaultMobileIconMapping: Flow<Map<String, MobileIconGroup>>,
    defaultMobileIconGroup: Flow<MobileIconGroup>,
    mobileMappingsProxy: MobileMappingsProxy,
    connectionRepository: MobileConnectionRepository,
) : MobileIconInteractor {
    private val mobileStatusInfo = connectionRepository.subscriptionModelFlow

    override val isDataEnabled: Flow<Boolean> = connectionRepository.dataEnabled

    override val isDefaultDataEnabled = defaultSubscriptionHasDataEnabled

    /** Observable for the current RAT indicator icon ([MobileIconGroup]) */
    override val networkTypeIconGroup: Flow<MobileIconGroup> =
        combine(
            mobileStatusInfo,
            defaultMobileIconMapping,
            defaultMobileIconGroup,
        ) { info, mapping, defaultGroup ->
            val lookupKey =
                when (val resolved = info.resolvedNetworkType) {
                    is DefaultNetworkType -> mobileMappingsProxy.toIconKey(resolved.type)
                    is OverrideNetworkType -> mobileMappingsProxy.toIconKeyOverride(resolved.type)
                }
            mapping[lookupKey] ?: defaultGroup
        }

    override val isEmergencyOnly: Flow<Boolean> = mobileStatusInfo.map { it.isEmergencyOnly }

    override val level: Flow<Int> =
        mobileStatusInfo.map { mobileModel ->
            // TODO: incorporate [MobileMappings.Config.alwaysShowCdmaRssi]
            if (mobileModel.isGsm) {
                mobileModel.primaryLevel
            } else {
                mobileModel.cdmaLevel
            }
        }

    /**
     * This will become variable based on [CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL]
     * once it's wired up inside of [CarrierConfigTracker]
     */
    override val numberOfLevels: Flow<Int> = flowOf(4)
}
