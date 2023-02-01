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
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import androidx.test.filters.SmallTest
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.CarrierMergedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.FIVE_G_OVERRIDE
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.FOUR_G
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.THREE_G
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test

@SmallTest
class MobileIconInteractorTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconInteractor
    private val mobileMappingsProxy = FakeMobileMappingsProxy()
    private val mobileIconsInteractor = FakeMobileIconsInteractor(mobileMappingsProxy, mock())
    private val connectionRepository = FakeMobileConnectionRepository(SUB_1_ID, mock())

    private val scope = CoroutineScope(IMMEDIATE)

    @Before
    fun setUp() {
        underTest =
            MobileIconInteractorImpl(
                scope,
                mobileIconsInteractor.activeDataConnectionHasDataEnabled,
                mobileIconsInteractor.alwaysShowDataRatIcon,
                mobileIconsInteractor.alwaysUseCdmaLevel,
                mobileIconsInteractor.defaultMobileNetworkConnectivity,
                mobileIconsInteractor.defaultMobileIconMapping,
                mobileIconsInteractor.defaultMobileIconGroup,
                mobileIconsInteractor.defaultDataSubId,
                mobileIconsInteractor.isDefaultConnectionFailed,
                connectionRepository,
            )
    }

    @Test
    fun gsm_level_default_unknown() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(isGsm = true),
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

            job.cancel()
        }

    @Test
    fun gsm_usesGsmLevel() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = true,
                    primaryLevel = GSM_LEVEL,
                    cdmaLevel = CDMA_LEVEL
                ),
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun gsm_alwaysShowCdmaTrue_stillUsesGsmLevel() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = true,
                    primaryLevel = GSM_LEVEL,
                    cdmaLevel = CDMA_LEVEL,
                ),
            )
            mobileIconsInteractor.alwaysUseCdmaLevel.value = true

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun notGsm_level_default_unknown() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(isGsm = false),
            )

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
            job.cancel()
        }

    @Test
    fun notGsm_alwaysShowCdmaTrue_usesCdmaLevel() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = false,
                    primaryLevel = GSM_LEVEL,
                    cdmaLevel = CDMA_LEVEL
                ),
            )
            mobileIconsInteractor.alwaysUseCdmaLevel.value = true

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CDMA_LEVEL)

            job.cancel()
        }

    @Test
    fun notGsm_alwaysShowCdmaFalse_usesPrimaryLevel() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = false,
                    primaryLevel = GSM_LEVEL,
                    cdmaLevel = CDMA_LEVEL,
                ),
            )
            mobileIconsInteractor.alwaysUseCdmaLevel.value = false

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun numberOfLevels_comesFromRepo() =
        runBlocking(IMMEDIATE) {
            var latest: Int? = null
            val job = underTest.numberOfLevels.onEach { latest = it }.launchIn(this)

            connectionRepository.numberOfLevels.value = 5
            assertThat(latest).isEqualTo(5)

            connectionRepository.numberOfLevels.value = 4
            assertThat(latest).isEqualTo(4)

            job.cancel()
        }

    @Test
    fun iconGroup_three_g() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType = DefaultNetworkType(mobileMappingsProxy.toIconKey(THREE_G))
                ),
            )

            var latest: MobileIconGroup? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(TelephonyIcons.THREE_G)

            job.cancel()
        }

    @Test
    fun iconGroup_updates_on_change() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType = DefaultNetworkType(mobileMappingsProxy.toIconKey(THREE_G))
                ),
            )

            var latest: MobileIconGroup? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType =
                        DefaultNetworkType(
                            mobileMappingsProxy.toIconKey(FOUR_G),
                        ),
                ),
            )
            yield()

            assertThat(latest).isEqualTo(TelephonyIcons.FOUR_G)

            job.cancel()
        }

    @Test
    fun iconGroup_5g_override_type() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType =
                        OverrideNetworkType(mobileMappingsProxy.toIconKeyOverride(FIVE_G_OVERRIDE))
                ),
            )

            var latest: MobileIconGroup? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(TelephonyIcons.NR_5G)

            job.cancel()
        }

    @Test
    fun iconGroup_default_if_no_lookup() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType =
                        DefaultNetworkType(mobileMappingsProxy.toIconKey(NETWORK_TYPE_UNKNOWN)),
                ),
            )

            var latest: MobileIconGroup? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(FakeMobileIconsInteractor.DEFAULT_ICON)

            job.cancel()
        }

    @Test
    fun iconGroup_carrierMerged_usesOverride() =
        runBlocking(IMMEDIATE) {
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType = CarrierMergedNetworkType,
                ),
            )

            var latest: MobileIconGroup? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CarrierMergedNetworkType.iconGroupOverride)

            job.cancel()
        }

    @Test
    fun `icon group - checks default data`() =
        runBlocking(IMMEDIATE) {
            mobileIconsInteractor.defaultDataSubId.value = SUB_1_ID
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    resolvedNetworkType = DefaultNetworkType(mobileMappingsProxy.toIconKey(THREE_G))
                ),
            )

            var latest: MobileIconGroup? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(TelephonyIcons.THREE_G)

            // Default data sub id changes to something else
            mobileIconsInteractor.defaultDataSubId.value = 123
            yield()

            assertThat(latest).isEqualTo(TelephonyIcons.NOT_DEFAULT_DATA)

            job.cancel()
        }

    @Test
    fun alwaysShowDataRatIcon_matchesParent() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.alwaysShowDataRatIcon.onEach { latest = it }.launchIn(this)

            mobileIconsInteractor.alwaysShowDataRatIcon.value = true
            assertThat(latest).isTrue()

            mobileIconsInteractor.alwaysShowDataRatIcon.value = false
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun alwaysUseCdmaLevel_matchesParent() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.alwaysUseCdmaLevel.onEach { latest = it }.launchIn(this)

            mobileIconsInteractor.alwaysUseCdmaLevel.value = true
            assertThat(latest).isTrue()

            mobileIconsInteractor.alwaysUseCdmaLevel.value = false
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun test_isDefaultDataEnabled_matchesParent() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isDefaultDataEnabled.onEach { latest = it }.launchIn(this)

            mobileIconsInteractor.activeDataConnectionHasDataEnabled.value = true
            assertThat(latest).isTrue()

            mobileIconsInteractor.activeDataConnectionHasDataEnabled.value = false
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun test_isDefaultConnectionFailed_matchedParent() =
        runBlocking(IMMEDIATE) {
            val job = underTest.isDefaultConnectionFailed.launchIn(this)

            mobileIconsInteractor.isDefaultConnectionFailed.value = false
            assertThat(underTest.isDefaultConnectionFailed.value).isFalse()

            mobileIconsInteractor.isDefaultConnectionFailed.value = true
            assertThat(underTest.isDefaultConnectionFailed.value).isTrue()

            job.cancel()
        }

    @Test
    fun dataState_connected() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isDataConnected.onEach { latest = it }.launchIn(this)

            connectionRepository.setConnectionInfo(
                MobileConnectionModel(dataConnectionState = DataConnectionState.Connected)
            )
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun dataState_notConnected() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isDataConnected.onEach { latest = it }.launchIn(this)

            connectionRepository.setConnectionInfo(
                MobileConnectionModel(dataConnectionState = DataConnectionState.Disconnected)
            )

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun `isInService - uses repository value`() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isInService.onEach { latest = it }.launchIn(this)

            connectionRepository.setConnectionInfo(MobileConnectionModel(isInService = true))

            assertThat(latest).isTrue()

            connectionRepository.setConnectionInfo(MobileConnectionModel(isInService = false))

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun `roaming - is gsm - uses connection model`() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = true,
                    isRoaming = false,
                )
            )
            yield()

            assertThat(latest).isFalse()

            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = true,
                    isRoaming = true,
                )
            )
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun `roaming - is cdma - uses cdma roaming bit`() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            connectionRepository.cdmaRoaming.value = false
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = false,
                    isRoaming = true,
                )
            )
            yield()

            assertThat(latest).isFalse()

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = false,
                    isRoaming = false,
                )
            )
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun `roaming - false while carrierNetworkChangeActive`() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = false,
                    isRoaming = true,
                    carrierNetworkChangeActive = true,
                )
            )
            yield()

            assertThat(latest).isFalse()

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(
                    isGsm = true,
                    isRoaming = true,
                    carrierNetworkChangeActive = true,
                )
            )
            yield()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun `network name - uses operatorAlphaShot when non null and repo is default`() =
        runBlocking(IMMEDIATE) {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            val testOperatorName = "operatorAlphaShort"

            // Default network name, operator name is non-null, uses the operator name
            connectionRepository.networkName.value = DEFAULT_NAME
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(operatorAlphaShort = testOperatorName)
            )
            yield()

            assertThat(latest).isEqualTo(NetworkNameModel.Derived(testOperatorName))

            // Default network name, operator name is null, uses the default
            connectionRepository.setConnectionInfo(MobileConnectionModel(operatorAlphaShort = null))
            yield()

            assertThat(latest).isEqualTo(DEFAULT_NAME)

            // Derived network name, operator name non-null, uses the derived name
            connectionRepository.networkName.value = DERIVED_NAME
            connectionRepository.setConnectionInfo(
                MobileConnectionModel(operatorAlphaShort = testOperatorName)
            )
            yield()

            assertThat(latest).isEqualTo(DERIVED_NAME)

            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate

        private const val GSM_LEVEL = 1
        private const val CDMA_LEVEL = 2

        private const val SUB_1_ID = 1

        private val DEFAULT_NAME = NetworkNameModel.Default("test default name")
        private val DERIVED_NAME = NetworkNameModel.Derived("test derived name")
    }
}
