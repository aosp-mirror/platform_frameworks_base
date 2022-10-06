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
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMobileSubscriptionRepository : MobileSubscriptionRepository {
    private val _subscriptionsFlow = MutableStateFlow<List<SubscriptionInfo>>(listOf())
    override val subscriptionsFlow: Flow<List<SubscriptionInfo>> = _subscriptionsFlow

    private val _activeMobileDataSubscriptionId =
        MutableStateFlow(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    override val activeMobileDataSubscriptionId = _activeMobileDataSubscriptionId

    private val _defaultDataSubRatConfig = MutableStateFlow(Config())
    override val defaultDataSubRatConfig = _defaultDataSubRatConfig

    private val subIdFlows = mutableMapOf<Int, MutableStateFlow<MobileSubscriptionModel>>()
    override fun getFlowForSubId(subId: Int): Flow<MobileSubscriptionModel> {
        return subIdFlows[subId]
            ?: MutableStateFlow(MobileSubscriptionModel()).also { subIdFlows[subId] = it }
    }

    fun setSubscriptions(subs: List<SubscriptionInfo>) {
        _subscriptionsFlow.value = subs
    }

    fun setDefaultDataSubRatConfig(config: Config) {
        _defaultDataSubRatConfig.value = config
    }

    fun setActiveMobileDataSubscriptionId(subId: Int) {
        _activeMobileDataSubscriptionId.value = subId
    }

    fun setMobileSubscriptionModel(model: MobileSubscriptionModel, subId: Int) {
        val subscription = subIdFlows[subId] ?: throw Exception("no flow exists for this subId yet")
        subscription.value = model
    }
}
