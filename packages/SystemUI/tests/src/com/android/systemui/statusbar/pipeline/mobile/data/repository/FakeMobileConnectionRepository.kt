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

import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMobileConnectionRepository(override val subId: Int) : MobileConnectionRepository {
    private val _subscriptionsModelFlow = MutableStateFlow(MobileSubscriptionModel())
    override val subscriptionModelFlow = _subscriptionsModelFlow

    private val _dataEnabled = MutableStateFlow(true)
    override val dataEnabled = _dataEnabled

    private val _isDefaultDataSubscription = MutableStateFlow(true)
    override val isDefaultDataSubscription = _isDefaultDataSubscription

    fun setMobileSubscriptionModel(model: MobileSubscriptionModel) {
        _subscriptionsModelFlow.value = model
    }

    fun setDataEnabled(enabled: Boolean) {
        _dataEnabled.value = enabled
    }

    fun setIsDefaultDataSubscription(isDefault: Boolean) {
        _isDefaultDataSubscription.value = isDefault
    }
}
