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

import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMobileIconInteractor(
    override val tableLogBuffer: TableLogBuffer,
) : MobileIconInteractor {
    override val alwaysShowDataRatIcon = MutableStateFlow(false)

    override val activity =
        MutableStateFlow(
            DataActivityModel(
                hasActivityIn = false,
                hasActivityOut = false,
            )
        )

    override val carrierNetworkChangeActive = MutableStateFlow(false)

    override val mobileIsDefault = MutableStateFlow(true)

    override val isSingleCarrier = MutableStateFlow(true)

    override val networkTypeIconGroup =
        MutableStateFlow<NetworkTypeIconModel>(
            NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)
        )

    override val showSliceAttribution = MutableStateFlow(false)

    override val networkName = MutableStateFlow(NetworkNameModel.IntentDerived("demo mode"))

    override val carrierName = MutableStateFlow("demo mode")

    override val isRoaming = MutableStateFlow(false)

    override val isDataConnected = MutableStateFlow(true)

    override val isInService = MutableStateFlow(true)

    override val isNonTerrestrial = MutableStateFlow(false)

    private val _isDataEnabled = MutableStateFlow(true)
    override val isDataEnabled = _isDataEnabled

    override val isForceHidden = MutableStateFlow(false)

    override val isAllowedDuringAirplaneMode = MutableStateFlow(false)

    override val signalLevelIcon: MutableStateFlow<SignalIconModel> =
        MutableStateFlow(
            SignalIconModel.Cellular(
                level = 0,
                numberOfLevels = 4,
                showExclamationMark = false,
                carrierNetworkChange = false,
            )
        )

    fun setIsDataEnabled(enabled: Boolean) {
        _isDataEnabled.value = enabled
    }
}
