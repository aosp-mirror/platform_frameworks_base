/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.data.repository

import android.telephony.SubscriptionInfo
import com.android.systemui.bouncer.data.model.SimPukInputModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fakes the SimBouncerRepository. */
class FakeSimBouncerRepository : SimBouncerRepository {
    private val _subscriptionId: MutableStateFlow<Int> = MutableStateFlow(-1)
    override val subscriptionId: StateFlow<Int> = _subscriptionId
    private val _activeSubscriptionInfo: MutableStateFlow<SubscriptionInfo?> =
        MutableStateFlow(null)
    override val activeSubscriptionInfo: StateFlow<SubscriptionInfo?> = _activeSubscriptionInfo
    private val _isLockedEsim: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    override val isLockedEsim: StateFlow<Boolean?> = _isLockedEsim
    private val _isSimPukLocked: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isSimPukLocked: StateFlow<Boolean> = _isSimPukLocked
    private val _errorDialogMessage: MutableStateFlow<String?> = MutableStateFlow(null)
    override val errorDialogMessage: StateFlow<String?> = _errorDialogMessage
    private var _simPukInputModel = SimPukInputModel()
    override val simPukInputModel: SimPukInputModel
        get() = _simPukInputModel

    fun setSubscriptionId(subId: Int) {
        _subscriptionId.value = subId
    }

    fun setActiveSubscriptionInfo(subscriptioninfo: SubscriptionInfo) {
        _activeSubscriptionInfo.value = subscriptioninfo
    }

    fun setLockedEsim(isLockedEsim: Boolean) {
        _isLockedEsim.value = isLockedEsim
    }

    fun setSimPukLocked(isSimPukLocked: Boolean) {
        _isSimPukLocked.value = isSimPukLocked
    }

    fun setErrorDialogMessage(msg: String?) {
        _errorDialogMessage.value = msg
    }

    override fun setSimPukUserInput(enteredSimPuk: String?, enteredSimPin: String?) {
        _simPukInputModel = SimPukInputModel(enteredSimPuk, enteredSimPin)
    }

    override fun setSimVerificationErrorMessage(msg: String?) {
        _errorDialogMessage.value = msg
    }
}
