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
import com.android.settingslib.mobile.MobileIconCarrierIdOverrides
import com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.CarrierMergedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.FIVE_G_OVERRIDE
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.FOUR_G
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.THREE_G
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileIconInteractorTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconInteractor
    private val mobileMappingsProxy = FakeMobileMappingsProxy()
    private val mobileIconsInteractor = FakeMobileIconsInteractor(mobileMappingsProxy, mock())
    private val connectionRepository = FakeMobileConnectionRepository(SUB_1_ID, mock())

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        underTest = createInteractor()
    }

    @Test
    fun gsm_level_default_unknown() =
        testScope.runTest {
            connectionRepository.isGsm.value = true

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

            job.cancel()
        }

    @Test
    fun gsm_usesGsmLevel() =
        testScope.runTest {
            connectionRepository.isGsm.value = true
            connectionRepository.primaryLevel.value = GSM_LEVEL
            connectionRepository.cdmaLevel.value = CDMA_LEVEL

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun gsm_alwaysShowCdmaTrue_stillUsesGsmLevel() =
        testScope.runTest {
            connectionRepository.isGsm.value = true
            connectionRepository.primaryLevel.value = GSM_LEVEL
            connectionRepository.cdmaLevel.value = CDMA_LEVEL
            mobileIconsInteractor.alwaysUseCdmaLevel.value = true

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun notGsm_level_default_unknown() =
        testScope.runTest {
            connectionRepository.isGsm.value = false

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
            job.cancel()
        }

    @Test
    fun notGsm_alwaysShowCdmaTrue_usesCdmaLevel() =
        testScope.runTest {
            connectionRepository.isGsm.value = false
            connectionRepository.primaryLevel.value = GSM_LEVEL
            connectionRepository.cdmaLevel.value = CDMA_LEVEL
            mobileIconsInteractor.alwaysUseCdmaLevel.value = true

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(CDMA_LEVEL)

            job.cancel()
        }

    @Test
    fun notGsm_alwaysShowCdmaFalse_usesPrimaryLevel() =
        testScope.runTest {
            connectionRepository.isGsm.value = false
            connectionRepository.primaryLevel.value = GSM_LEVEL
            connectionRepository.cdmaLevel.value = CDMA_LEVEL
            mobileIconsInteractor.alwaysUseCdmaLevel.value = false

            var latest: Int? = null
            val job = underTest.level.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun numberOfLevels_comesFromRepo() =
        testScope.runTest {
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
        testScope.runTest {
            connectionRepository.resolvedNetworkType.value =
                DefaultNetworkType(mobileMappingsProxy.toIconKey(THREE_G))

            var latest: NetworkTypeIconModel? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G))

            job.cancel()
        }

    @Test
    fun iconGroup_updates_on_change() =
        testScope.runTest {
            connectionRepository.resolvedNetworkType.value =
                DefaultNetworkType(mobileMappingsProxy.toIconKey(THREE_G))

            var latest: NetworkTypeIconModel? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            connectionRepository.resolvedNetworkType.value =
                DefaultNetworkType(mobileMappingsProxy.toIconKey(FOUR_G))

            assertThat(latest).isEqualTo(NetworkTypeIconModel.DefaultIcon(TelephonyIcons.FOUR_G))

            job.cancel()
        }

    @Test
    fun iconGroup_5g_override_type() =
        testScope.runTest {
            connectionRepository.resolvedNetworkType.value =
                OverrideNetworkType(mobileMappingsProxy.toIconKeyOverride(FIVE_G_OVERRIDE))

            var latest: NetworkTypeIconModel? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(NetworkTypeIconModel.DefaultIcon(TelephonyIcons.NR_5G))

            job.cancel()
        }

    @Test
    fun iconGroup_default_if_no_lookup() =
        testScope.runTest {
            connectionRepository.resolvedNetworkType.value =
                DefaultNetworkType(mobileMappingsProxy.toIconKey(NETWORK_TYPE_UNKNOWN))

            var latest: NetworkTypeIconModel? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest)
                .isEqualTo(NetworkTypeIconModel.DefaultIcon(FakeMobileIconsInteractor.DEFAULT_ICON))

            job.cancel()
        }

    @Test
    fun iconGroup_carrierMerged_usesOverride() =
        testScope.runTest {
            connectionRepository.resolvedNetworkType.value = CarrierMergedNetworkType

            var latest: NetworkTypeIconModel? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest)
                .isEqualTo(
                    NetworkTypeIconModel.DefaultIcon(CarrierMergedNetworkType.iconGroupOverride)
                )

            job.cancel()
        }

    @Test
    fun overrideIcon_usesCarrierIdOverride() =
        testScope.runTest {
            val overrides =
                mock<MobileIconCarrierIdOverrides>().also {
                    whenever(it.carrierIdEntryExists(anyInt())).thenReturn(true)
                    whenever(it.getOverrideFor(anyInt(), anyString(), any())).thenReturn(1234)
                }

            underTest = createInteractor(overrides)

            connectionRepository.resolvedNetworkType.value =
                DefaultNetworkType(mobileMappingsProxy.toIconKey(THREE_G))

            var latest: NetworkTypeIconModel? = null
            val job = underTest.networkTypeIconGroup.onEach { latest = it }.launchIn(this)

            assertThat(latest)
                .isEqualTo(NetworkTypeIconModel.OverriddenIcon(TelephonyIcons.THREE_G, 1234))

            job.cancel()
        }

    @Test
    fun alwaysShowDataRatIcon_matchesParent() =
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            val job = underTest.isDefaultConnectionFailed.launchIn(this)

            mobileIconsInteractor.isDefaultConnectionFailed.value = false
            assertThat(underTest.isDefaultConnectionFailed.value).isFalse()

            mobileIconsInteractor.isDefaultConnectionFailed.value = true
            assertThat(underTest.isDefaultConnectionFailed.value).isTrue()

            job.cancel()
        }

    @Test
    fun dataState_connected() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDataConnected.onEach { latest = it }.launchIn(this)

            connectionRepository.dataConnectionState.value = DataConnectionState.Connected

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun dataState_notConnected() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isDataConnected.onEach { latest = it }.launchIn(this)

            connectionRepository.dataConnectionState.value = DataConnectionState.Disconnected

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isInService_usesRepositoryValue() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isInService.onEach { latest = it }.launchIn(this)

            connectionRepository.isInService.value = true

            assertThat(latest).isTrue()

            connectionRepository.isInService.value = false

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun roaming_isGsm_usesConnectionModel() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.isGsm.value = true
            connectionRepository.isRoaming.value = false

            assertThat(latest).isFalse()

            connectionRepository.isRoaming.value = true

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun roaming_isCdma_usesCdmaRoamingBit() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            connectionRepository.cdmaRoaming.value = false
            connectionRepository.isGsm.value = false
            connectionRepository.isRoaming.value = true

            assertThat(latest).isFalse()

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.isGsm.value = false
            connectionRepository.isRoaming.value = false

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun roaming_falseWhileCarrierNetworkChangeActive() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.isGsm.value = false
            connectionRepository.isRoaming.value = true
            connectionRepository.carrierNetworkChangeActive.value = true

            assertThat(latest).isFalse()

            connectionRepository.cdmaRoaming.value = true
            connectionRepository.isGsm.value = true

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun networkName_usesOperatorAlphaShotWhenNonNullAndRepoIsDefault() =
        testScope.runTest {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            val testOperatorName = "operatorAlphaShort"

            // Default network name, operator name is non-null, uses the operator name
            connectionRepository.networkName.value = DEFAULT_NAME
            connectionRepository.operatorAlphaShort.value = testOperatorName

            assertThat(latest).isEqualTo(NetworkNameModel.IntentDerived(testOperatorName))

            // Default network name, operator name is null, uses the default
            connectionRepository.operatorAlphaShort.value = null

            assertThat(latest).isEqualTo(DEFAULT_NAME)

            // Derived network name, operator name non-null, uses the derived name
            connectionRepository.networkName.value = DERIVED_NAME
            connectionRepository.operatorAlphaShort.value = testOperatorName

            assertThat(latest).isEqualTo(DERIVED_NAME)

            job.cancel()
        }

    @Test
    fun isForceHidden_matchesParent() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            mobileIconsInteractor.isForceHidden.value = true
            assertThat(latest).isTrue()

            mobileIconsInteractor.isForceHidden.value = false
            assertThat(latest).isFalse()

            job.cancel()
        }

    private fun createInteractor(
        overrides: MobileIconCarrierIdOverrides = MobileIconCarrierIdOverridesImpl()
    ) =
        MobileIconInteractorImpl(
            testScope.backgroundScope,
            mobileIconsInteractor.activeDataConnectionHasDataEnabled,
            mobileIconsInteractor.alwaysShowDataRatIcon,
            mobileIconsInteractor.alwaysUseCdmaLevel,
            mobileIconsInteractor.mobileIsDefault,
            mobileIconsInteractor.defaultMobileIconMapping,
            mobileIconsInteractor.defaultMobileIconGroup,
            mobileIconsInteractor.isDefaultConnectionFailed,
            mobileIconsInteractor.isForceHidden,
            connectionRepository,
            context,
            overrides,
        )

    companion object {
        private const val GSM_LEVEL = 1
        private const val CDMA_LEVEL = 2

        private const val SUB_1_ID = 1

        private val DEFAULT_NAME = NetworkNameModel.Default("test default name")
        private val DERIVED_NAME = NetworkNameModel.IntentDerived("test derived name")
    }
}
