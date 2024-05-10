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
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.MobileIconCarrierIdOverrides
import com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.CarrierMergedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.FIVE_G_OVERRIDE
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.FOUR_G
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor.Companion.THREE_G
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val subscriptionModel =
        MutableStateFlow(
            SubscriptionModel(
                subscriptionId = SUB_1_ID,
                carrierName = DEFAULT_NAME,
                profileClass = PROFILE_CLASS_UNSET,
            )
        )

    private val connectionRepository = FakeMobileConnectionRepository(SUB_1_ID, mock())

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        underTest = createInteractor()

        mobileIconsInteractor.activeDataConnectionHasDataEnabled.value = true
        connectionRepository.isInService.value = true
    }

    @Test
    fun gsm_usesGsmLevel() =
        testScope.runTest {
            connectionRepository.isGsm.value = true
            connectionRepository.primaryLevel.value = GSM_LEVEL
            connectionRepository.cdmaLevel.value = CDMA_LEVEL

            var latest: Int? = null
            val job = underTest.signalLevelIcon.onEach { latest = it.level }.launchIn(this)

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
            val job = underTest.signalLevelIcon.onEach { latest = it.level }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun notGsm_level_default_unknown() =
        testScope.runTest {
            connectionRepository.isGsm.value = false

            var latest: Int? = null
            val job = underTest.signalLevelIcon.onEach { latest = it.level }.launchIn(this)

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
            val job = underTest.signalLevelIcon.onEach { latest = it.level }.launchIn(this)

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
            val job = underTest.signalLevelIcon.onEach { latest = it.level }.launchIn(this)

            assertThat(latest).isEqualTo(GSM_LEVEL)

            job.cancel()
        }

    @Test
    fun numberOfLevels_comesFromRepo() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.signalLevelIcon.onEach { latest = it.numberOfLevels }.launchIn(this)

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
    fun networkName_usesOperatorAlphaShortWhenNonNullAndRepoIsDefault() =
        testScope.runTest {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            val testOperatorName = "operatorAlphaShort"

            // Default network name, operator name is non-null, uses the operator name
            connectionRepository.networkName.value = DEFAULT_NAME_MODEL
            connectionRepository.operatorAlphaShort.value = testOperatorName

            assertThat(latest).isEqualTo(NetworkNameModel.IntentDerived(testOperatorName))

            // Default network name, operator name is null, uses the default
            connectionRepository.operatorAlphaShort.value = null

            assertThat(latest).isEqualTo(DEFAULT_NAME_MODEL)

            // Derived network name, operator name non-null, uses the derived name
            connectionRepository.networkName.value = DERIVED_NAME_MODEL
            connectionRepository.operatorAlphaShort.value = testOperatorName

            assertThat(latest).isEqualTo(DERIVED_NAME_MODEL)

            job.cancel()
        }

    @Test
    fun networkNameForSubId_usesOperatorAlphaShortWhenNonNullAndRepoIsDefault() =
        testScope.runTest {
            var latest: String? = null
            val job = underTest.carrierName.onEach { latest = it }.launchIn(this)

            val testOperatorName = "operatorAlphaShort"

            // Default network name, operator name is non-null, uses the operator name
            connectionRepository.carrierName.value = DEFAULT_NAME_MODEL
            connectionRepository.operatorAlphaShort.value = testOperatorName

            assertThat(latest).isEqualTo(testOperatorName)

            // Default network name, operator name is null, uses the default
            connectionRepository.operatorAlphaShort.value = null

            assertThat(latest).isEqualTo(DEFAULT_NAME)

            // Derived network name, operator name non-null, uses the derived name
            connectionRepository.carrierName.value =
                NetworkNameModel.SubscriptionDerived(DERIVED_NAME)
            connectionRepository.operatorAlphaShort.value = testOperatorName

            assertThat(latest).isEqualTo(DERIVED_NAME)

            job.cancel()
        }

    @Test
    fun isSingleCarrier_matchesParent() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isSingleCarrier.onEach { latest = it }.launchIn(this)

            mobileIconsInteractor.isSingleCarrier.value = true
            assertThat(latest).isTrue()

            mobileIconsInteractor.isSingleCarrier.value = false
            assertThat(latest).isFalse()

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

    @Test
    fun isAllowedDuringAirplaneMode_matchesRepo() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isAllowedDuringAirplaneMode)

            connectionRepository.isAllowedDuringAirplaneMode.value = true
            assertThat(latest).isTrue()

            connectionRepository.isAllowedDuringAirplaneMode.value = false
            assertThat(latest).isFalse()
        }

    @Test
    fun iconId_correctLevel_notCutout() =
        testScope.runTest {
            connectionRepository.isInService.value = true
            connectionRepository.primaryLevel.value = 1
            connectionRepository.setDataEnabled(false)

            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest?.level).isEqualTo(1)
            assertThat(latest?.showExclamationMark).isFalse()

            job.cancel()
        }

    @Test
    fun icon_usesLevelFromInteractor() =
        testScope.runTest {
            connectionRepository.isInService.value = true

            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            connectionRepository.primaryLevel.value = 3
            assertThat(latest!!.level).isEqualTo(3)

            connectionRepository.primaryLevel.value = 1
            assertThat(latest!!.level).isEqualTo(1)

            job.cancel()
        }

    @Test
    fun icon_usesNumberOfLevelsFromInteractor() =
        testScope.runTest {
            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            connectionRepository.numberOfLevels.value = 5
            assertThat(latest!!.numberOfLevels).isEqualTo(5)

            connectionRepository.numberOfLevels.value = 2
            assertThat(latest!!.numberOfLevels).isEqualTo(2)

            job.cancel()
        }

    @Test
    fun icon_defaultDataDisabled_showExclamationTrue() =
        testScope.runTest {
            mobileIconsInteractor.activeDataConnectionHasDataEnabled.value = false

            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest!!.showExclamationMark).isTrue()

            job.cancel()
        }

    @Test
    fun icon_defaultConnectionFailed_showExclamationTrue() =
        testScope.runTest {
            mobileIconsInteractor.isDefaultConnectionFailed.value = true

            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest!!.showExclamationMark).isTrue()

            job.cancel()
        }

    @Test
    fun icon_enabledAndNotFailed_showExclamationFalse() =
        testScope.runTest {
            connectionRepository.isInService.value = true
            mobileIconsInteractor.activeDataConnectionHasDataEnabled.value = true
            mobileIconsInteractor.isDefaultConnectionFailed.value = false

            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            assertThat(latest!!.showExclamationMark).isFalse()

            job.cancel()
        }

    @Test
    fun icon_usesEmptyState_whenNotInService() =
        testScope.runTest {
            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            connectionRepository.isInService.value = false

            assertThat(latest?.level).isEqualTo(0)
            assertThat(latest?.showExclamationMark).isTrue()

            // Changing the level doesn't overwrite the disabled state
            connectionRepository.primaryLevel.value = 2
            assertThat(latest?.level).isEqualTo(0)
            assertThat(latest?.showExclamationMark).isTrue()

            // Once back in service, the regular icon appears
            connectionRepository.isInService.value = true
            assertThat(latest?.level).isEqualTo(2)
            assertThat(latest?.showExclamationMark).isFalse()

            job.cancel()
        }

    @Test
    fun icon_usesCarrierNetworkState_whenInCarrierNetworkChangeMode() =
        testScope.runTest {
            var latest: SignalIconModel? = null
            val job = underTest.signalLevelIcon.onEach { latest = it }.launchIn(this)

            connectionRepository.isInService.value = true
            connectionRepository.carrierNetworkChangeActive.value = true
            connectionRepository.primaryLevel.value = 1
            connectionRepository.cdmaLevel.value = 1

            assertThat(latest!!.level).isEqualTo(1)
            assertThat(latest!!.carrierNetworkChange).isTrue()

            // SignalIconModel respects the current level
            connectionRepository.primaryLevel.value = 2

            assertThat(latest!!.level).isEqualTo(2)
            assertThat(latest!!.carrierNetworkChange).isTrue()

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
            mobileIconsInteractor.isSingleCarrier,
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

        private const val DEFAULT_NAME = "test default name"
        private val DEFAULT_NAME_MODEL = NetworkNameModel.Default(DEFAULT_NAME)
        private const val DERIVED_NAME = "test derived name"
        private val DERIVED_NAME_MODEL = NetworkNameModel.IntentDerived(DERIVED_NAME)
    }
}
