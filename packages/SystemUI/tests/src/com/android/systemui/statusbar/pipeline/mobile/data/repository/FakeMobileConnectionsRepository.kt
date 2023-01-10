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

import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

// TODO(b/261632894): remove this in favor of the real impl or DemoMobileConnectionsRepository
class FakeMobileConnectionsRepository(
    mobileMappings: MobileMappingsProxy,
    val tableLogBuffer: TableLogBuffer,
) : MobileConnectionsRepository {
    val GSM_KEY = mobileMappings.toIconKey(GSM)
    val LTE_KEY = mobileMappings.toIconKey(LTE)
    val UMTS_KEY = mobileMappings.toIconKey(UMTS)
    val LTE_ADVANCED_KEY = mobileMappings.toIconKeyOverride(LTE_ADVANCED_PRO)

    /**
     * To avoid a reliance on [MobileMappings], we'll build a simpler map from network type to
     * mobile icon. See TelephonyManager.NETWORK_TYPES for a list of types and [TelephonyIcons] for
     * the exhaustive set of icons
     */
    val TEST_MAPPING: Map<String, SignalIcon.MobileIconGroup> =
        mapOf(
            GSM_KEY to TelephonyIcons.THREE_G,
            LTE_KEY to TelephonyIcons.LTE,
            UMTS_KEY to TelephonyIcons.FOUR_G,
            LTE_ADVANCED_KEY to TelephonyIcons.NR_5G,
        )

    private val _subscriptions = MutableStateFlow<List<SubscriptionModel>>(listOf())
    override val subscriptions = _subscriptions

    private val _activeMobileDataSubscriptionId = MutableStateFlow(INVALID_SUBSCRIPTION_ID)
    override val activeMobileDataSubscriptionId = _activeMobileDataSubscriptionId
    override val activeSubChangedInGroupEvent: MutableSharedFlow<Unit> = MutableSharedFlow()

    private val _mobileConnectivity = MutableStateFlow(MobileConnectivityModel())
    override val defaultMobileNetworkConnectivity = _mobileConnectivity

    private val subIdRepos = mutableMapOf<Int, MobileConnectionRepository>()
    override fun getRepoForSubId(subId: Int): MobileConnectionRepository {
        return subIdRepos[subId]
            ?: FakeMobileConnectionRepository(subId, tableLogBuffer).also { subIdRepos[subId] = it }
    }

    private val _globalMobileDataSettingChangedEvent = MutableStateFlow(Unit)
    override val globalMobileDataSettingChangedEvent = _globalMobileDataSettingChangedEvent

    override val defaultDataSubRatConfig = MutableStateFlow(MobileMappings.Config())

    private val _defaultMobileIconMapping = MutableStateFlow(TEST_MAPPING)
    override val defaultMobileIconMapping = _defaultMobileIconMapping

    private val _defaultMobileIconGroup = MutableStateFlow(DEFAULT_ICON)
    override val defaultMobileIconGroup = _defaultMobileIconGroup

    fun setSubscriptions(subs: List<SubscriptionModel>) {
        _subscriptions.value = subs
    }

    fun setMobileConnectivity(model: MobileConnectivityModel) {
        _mobileConnectivity.value = model
    }

    suspend fun triggerGlobalMobileDataSettingChangedEvent() {
        _globalMobileDataSettingChangedEvent.emit(Unit)
    }

    fun setActiveMobileDataSubscriptionId(subId: Int) {
        _activeMobileDataSubscriptionId.value = subId
    }

    fun setMobileConnectionRepositoryMap(connections: Map<Int, MobileConnectionRepository>) {
        connections.forEach { entry -> subIdRepos[entry.key] = entry.value }
    }

    companion object {
        val DEFAULT_ICON = TelephonyIcons.G

        // Use [MobileMappings] to define some simple definitions
        const val GSM = TelephonyManager.NETWORK_TYPE_GSM
        const val LTE = TelephonyManager.NETWORK_TYPE_LTE
        const val UMTS = TelephonyManager.NETWORK_TYPE_UMTS
        const val LTE_ADVANCED_PRO = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
    }
}
