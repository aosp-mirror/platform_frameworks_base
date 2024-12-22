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
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

// TODO(b/261632894): remove this in favor of the real impl or DemoMobileConnectionsRepository
class FakeMobileConnectionsRepository(
    mobileMappings: MobileMappingsProxy = FakeMobileMappingsProxy(),
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

    private val _activeMobileDataSubscriptionId = MutableStateFlow<Int?>(null)
    override val activeMobileDataSubscriptionId = _activeMobileDataSubscriptionId

    private val _activeMobileRepository = MutableStateFlow<MobileConnectionRepository?>(null)
    override val activeMobileDataRepository = _activeMobileRepository

    override val activeSubChangedInGroupEvent: MutableSharedFlow<Unit> = MutableSharedFlow()

    private val _defaultDataSubId = MutableStateFlow(INVALID_SUBSCRIPTION_ID)
    override val defaultDataSubId = _defaultDataSubId

    override val mobileIsDefault = MutableStateFlow(false)

    override val hasCarrierMergedConnection = MutableStateFlow(false)

    override val defaultConnectionIsValidated = MutableStateFlow(false)

    private val subIdRepos = mutableMapOf<Int, MobileConnectionRepository>()

    override fun getRepoForSubId(subId: Int): MobileConnectionRepository {
        return subIdRepos[subId]
            ?: FakeMobileConnectionRepository(
                    subId,
                    tableLogBuffer,
                )
                .also { subIdRepos[subId] = it }
    }

    override val defaultDataSubRatConfig = MutableStateFlow(MobileMappings.Config())

    private val _defaultMobileIconMapping = MutableStateFlow(TEST_MAPPING)
    override val defaultMobileIconMapping = _defaultMobileIconMapping

    private val _defaultMobileIconGroup = MutableStateFlow(DEFAULT_ICON)
    override val defaultMobileIconGroup = _defaultMobileIconGroup

    override val isDeviceEmergencyCallCapable = MutableStateFlow(false)

    override val isAnySimSecure = MutableStateFlow(false)

    override fun getIsAnySimSecure(): Boolean = isAnySimSecure.value

    private var isInEcmMode: Boolean = false

    fun setSubscriptions(subs: List<SubscriptionModel>) {
        _subscriptions.value = subs
    }

    fun setActiveMobileDataSubscriptionId(subId: Int) {
        // Simulate the filtering that the repo does
        if (subId == INVALID_SUBSCRIPTION_ID) {
            _activeMobileDataSubscriptionId.value = null
            _activeMobileRepository.value = null
        } else {
            _activeMobileDataSubscriptionId.value = subId
            _activeMobileRepository.value = getRepoForSubId(subId)
        }
    }

    fun setMobileConnectionRepositoryMap(connections: Map<Int, MobileConnectionRepository>) {
        connections.forEach { entry -> subIdRepos[entry.key] = entry.value }
    }

    fun setIsInEcmState(isInEcmState: Boolean) {
        this.isInEcmMode = isInEcmState
    }

    override suspend fun isInEcmMode(): Boolean = isInEcmMode

    companion object {
        val DEFAULT_ICON = TelephonyIcons.G

        // Use [MobileMappings] to define some simple definitions
        const val GSM = TelephonyManager.NETWORK_TYPE_GSM
        const val LTE = TelephonyManager.NETWORK_TYPE_LTE
        const val UMTS = TelephonyManager.NETWORK_TYPE_UMTS
        const val LTE_ADVANCED_PRO = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
    }
}
