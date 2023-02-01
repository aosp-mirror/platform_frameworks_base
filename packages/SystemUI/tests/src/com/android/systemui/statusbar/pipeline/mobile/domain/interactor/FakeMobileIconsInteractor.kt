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

import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
import android.telephony.TelephonyManager.NETWORK_TYPE_GSM
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UMTS
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMobileIconsInteractor(
    mobileMappings: MobileMappingsProxy,
    val tableLogBuffer: TableLogBuffer,
) : MobileIconsInteractor {
    val THREE_G_KEY = mobileMappings.toIconKey(THREE_G)
    val LTE_KEY = mobileMappings.toIconKey(LTE)
    val FOUR_G_KEY = mobileMappings.toIconKey(FOUR_G)
    val FIVE_G_OVERRIDE_KEY = mobileMappings.toIconKeyOverride(FIVE_G_OVERRIDE)

    /**
     * To avoid a reliance on [MobileMappings], we'll build a simpler map from network type to
     * mobile icon. See TelephonyManager.NETWORK_TYPES for a list of types and [TelephonyIcons] for
     * the exhaustive set of icons
     */
    val TEST_MAPPING: Map<String, MobileIconGroup> =
        mapOf(
            THREE_G_KEY to TelephonyIcons.THREE_G,
            LTE_KEY to TelephonyIcons.LTE,
            FOUR_G_KEY to TelephonyIcons.FOUR_G,
            FIVE_G_OVERRIDE_KEY to TelephonyIcons.NR_5G,
        )

    override val isDefaultConnectionFailed = MutableStateFlow(false)

    override val filteredSubscriptions = MutableStateFlow<List<SubscriptionModel>>(listOf())

    private val _activeDataConnectionHasDataEnabled = MutableStateFlow(false)
    override val activeDataConnectionHasDataEnabled = _activeDataConnectionHasDataEnabled

    override val alwaysShowDataRatIcon = MutableStateFlow(false)

    override val alwaysUseCdmaLevel = MutableStateFlow(false)
    override val defaultDataSubId = MutableStateFlow(DEFAULT_DATA_SUB_ID)

    override val defaultMobileNetworkConnectivity = MutableStateFlow(MobileConnectivityModel())

    private val _defaultMobileIconMapping = MutableStateFlow(TEST_MAPPING)
    override val defaultMobileIconMapping = _defaultMobileIconMapping

    private val _defaultMobileIconGroup = MutableStateFlow(DEFAULT_ICON)
    override val defaultMobileIconGroup = _defaultMobileIconGroup

    private val _isUserSetup = MutableStateFlow(true)
    override val isUserSetup = _isUserSetup

    /** Always returns a new fake interactor */
    override fun createMobileConnectionInteractorForSubId(subId: Int): MobileIconInteractor {
        return FakeMobileIconInteractor(tableLogBuffer)
    }

    companion object {
        val DEFAULT_ICON = TelephonyIcons.G

        const val DEFAULT_DATA_SUB_ID = 1

        // Use [MobileMappings] to define some simple definitions
        const val THREE_G = NETWORK_TYPE_GSM
        const val LTE = NETWORK_TYPE_LTE
        const val FOUR_G = NETWORK_TYPE_UMTS
        const val FIVE_G_OVERRIDE = OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
    }
}
