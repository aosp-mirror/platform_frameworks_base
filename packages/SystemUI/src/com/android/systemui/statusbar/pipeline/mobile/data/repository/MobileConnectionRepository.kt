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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every mobile line of service can be identified via a [SubscriptionInfo] object. We set up a
 * repository for each individual, tracked subscription via [MobileConnectionsRepository], and this
 * repository is responsible for setting up a [TelephonyManager] object tied to its subscriptionId
 *
 * There should only ever be one [MobileConnectionRepository] per subscription, since
 * [TelephonyManager] limits the number of callbacks that can be registered per process.
 *
 * This repository should have all of the relevant information for a single line of service, which
 * eventually becomes a single icon in the status bar.
 */
interface MobileConnectionRepository {
    /** The subscriptionId that this connection represents */
    val subId: Int
    /**
     * A flow that aggregates all necessary callbacks from [TelephonyCallback] into a single
     * listener + model.
     */
    val connectionInfo: Flow<MobileConnectionModel>
    /** Observable tracking [TelephonyManager.isDataConnectionAllowed] */
    val dataEnabled: StateFlow<Boolean>
    /**
     * True if this connection represents the default subscription per
     * [SubscriptionManager.getDefaultDataSubscriptionId]
     */
    val isDefaultDataSubscription: StateFlow<Boolean>
}
