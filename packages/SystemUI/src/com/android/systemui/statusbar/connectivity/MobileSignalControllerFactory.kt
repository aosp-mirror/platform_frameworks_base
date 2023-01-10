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
package com.android.systemui.statusbar.connectivity

import android.content.Context
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.MobileStatusTracker
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.util.CarrierConfigTracker
import javax.inject.Inject

/**
 * Factory to make MobileSignalController injectable
 */
@SysUISingleton
internal class MobileSignalControllerFactory @Inject constructor(
    val context: Context,
    val callbackHandler: CallbackHandler,
    val carrierConfigTracker: CarrierConfigTracker,
    val mobileMappings: MobileMappingsProxy,
) {
    fun createMobileSignalController(
        config: MobileMappings.Config,
        hasMobileData: Boolean,
        phone: TelephonyManager,
        networkController: NetworkControllerImpl,
        subscriptionInfo: SubscriptionInfo,
        subscriptionDefaults: MobileStatusTracker.SubscriptionDefaults,
        receiverLooper: Looper,
    ): MobileSignalController {
        val mobileTrackerFactory = MobileStatusTrackerFactory(
            phone,
            receiverLooper,
            subscriptionInfo,
            subscriptionDefaults)

        return MobileSignalController(
            context,
            config,
            hasMobileData,
            phone,
            callbackHandler,
            networkController,
            mobileMappings,
            subscriptionInfo,
            subscriptionDefaults,
            receiverLooper,
            carrierConfigTracker,
            mobileTrackerFactory,
        )
    }
}
