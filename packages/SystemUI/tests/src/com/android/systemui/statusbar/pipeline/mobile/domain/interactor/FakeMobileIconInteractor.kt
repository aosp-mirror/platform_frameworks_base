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

import android.telephony.CellSignalStrength
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.TelephonyIcons
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMobileIconInteractor : MobileIconInteractor {
    override val alwaysShowDataRatIcon = MutableStateFlow(false)

    private val _iconGroup = MutableStateFlow<SignalIcon.MobileIconGroup>(TelephonyIcons.THREE_G)
    override val networkTypeIconGroup = _iconGroup

    private val _isEmergencyOnly = MutableStateFlow(false)
    override val isEmergencyOnly = _isEmergencyOnly

    private val _isFailedConnection = MutableStateFlow(false)
    override val isDefaultConnectionFailed = _isFailedConnection

    override val isDataConnected = MutableStateFlow(true)

    private val _isDataEnabled = MutableStateFlow(true)
    override val isDataEnabled = _isDataEnabled

    private val _isDefaultDataEnabled = MutableStateFlow(true)
    override val isDefaultDataEnabled = _isDefaultDataEnabled

    private val _level = MutableStateFlow(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
    override val level = _level

    private val _numberOfLevels = MutableStateFlow(4)
    override val numberOfLevels = _numberOfLevels

    fun setIconGroup(group: SignalIcon.MobileIconGroup) {
        _iconGroup.value = group
    }

    fun setIsEmergencyOnly(emergency: Boolean) {
        _isEmergencyOnly.value = emergency
    }

    fun setIsDataEnabled(enabled: Boolean) {
        _isDataEnabled.value = enabled
    }

    fun setIsDefaultDataEnabled(disabled: Boolean) {
        _isDefaultDataEnabled.value = disabled
    }

    fun setIsFailedConnection(failed: Boolean) {
        _isFailedConnection.value = failed
    }

    fun setLevel(level: Int) {
        _level.value = level
    }

    fun setNumberOfLevels(num: Int) {
        _numberOfLevels.value = num
    }
}
