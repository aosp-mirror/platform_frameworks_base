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
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import com.android.systemui.util.CarrierConfigTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface MobileIconInteractor {
    /** Identifier for RAT type indicator */
    val iconGroup: Flow<SignalIcon.MobileIconGroup>
    /** True if this line of service is emergency-only */
    val isEmergencyOnly: Flow<Boolean>
    /** Int describing the connection strength. 0-4 OR 1-5. See [numberOfLevels] */
    val level: Flow<Int>
    /** Based on [CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL], either 4 or 5 */
    val numberOfLevels: Flow<Int>
    /** True when we want to draw an icon that makes room for the exclamation mark */
    val cutOut: Flow<Boolean>
}

/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
class MobileIconInteractorImpl(
    mobileStatusInfo: Flow<MobileSubscriptionModel>,
) : MobileIconInteractor {
    override val iconGroup: Flow<SignalIcon.MobileIconGroup> = flowOf(TelephonyIcons.THREE_G)
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

    /** Whether or not to draw the mobile triangle as "cut out", i.e., with the exclamation mark */
    // TODO: find a better name for this?
    override val cutOut: Flow<Boolean> = flowOf(false)
}
