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

import android.os.ParcelUuid
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.SubscriptionManager.PROFILE_CLASS_PROVISIONING
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fake
import com.android.systemui.flags.featureFlagsClassic
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fake
import com.android.systemui.statusbar.pipeline.mobile.data.repository.mobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.mobileConnectionsRepositoryLogbufferName
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.connectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.fake
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.CarrierConfigTracker
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class MobileIconsInteractorTest : SysuiTestCase() {
    private val kosmos by lazy {
        testKosmos().apply {
            mobileConnectionsRepositoryLogbufferName = "MobileIconsInteractorTest"
            mobileConnectionsRepository.fake.run {
                setMobileConnectionRepositoryMap(
                    mapOf(
                        SUB_1_ID to CONNECTION_1,
                        SUB_2_ID to CONNECTION_2,
                        SUB_3_ID to CONNECTION_3,
                        SUB_4_ID to CONNECTION_4,
                    )
                )
                setActiveMobileDataSubscriptionId(SUB_1_ID)
            }
            featureFlagsClassic.fake.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, true)
        }
    }

    // shortcut rename
    private val Kosmos.connectionsRepository by Fixture { mobileConnectionsRepository.fake }

    private val Kosmos.carrierConfigTracker by Fixture { mock<CarrierConfigTracker>() }

    private val Kosmos.underTest by Fixture {
        MobileIconsInteractorImpl(
            mobileConnectionsRepository,
            carrierConfigTracker,
            tableLogger = mock(),
            connectivityRepository,
            FakeUserSetupRepository(),
            testScope.backgroundScope,
            context,
            featureFlagsClassic,
        )
    }

    @Test
    fun filteredSubscriptions_default() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf<SubscriptionModel>())
        }

    // Based on the logic from the old pipeline, we'll never filter subs when there are more than 2
    @Test
    fun filteredSubscriptions_moreThanTwo_doesNotFilter() =
        kosmos.runTest {
            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_3_OPP, SUB_4_OPP))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_4_ID)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_3_OPP, SUB_4_OPP))
        }

    @Test
    fun filteredSubscriptions_nonOpportunistic_updatesWithMultipleSubs() =
        kosmos.runTest {
            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))
        }

    @Test
    fun filteredSubscriptions_opportunistic_differentGroups_doesNotFilter() =
        kosmos.runTest {
            connectionsRepository.setSubscriptions(listOf(SUB_3_OPP, SUB_4_OPP))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(SUB_3_OPP, SUB_4_OPP))
        }

    @Test
    fun filteredSubscriptions_opportunistic_nonGrouped_doesNotFilter() =
        kosmos.runTest {
            val (sub1, sub2) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_2_ID),
                    opportunistic = Pair(true, true),
                    grouped = false,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub2))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_1_ID)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub1, sub2))
        }

    @Test
    fun filteredSubscriptions_opportunistic_grouped_configFalse_showsActive_3() =
        kosmos.runTest {
            val (sub3, sub4) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_3_ID, SUB_4_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub3, sub4))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            // Filtered subscriptions should show the active one when the config is false
            assertThat(latest).isEqualTo(listOf(sub3))
        }

    @Test
    fun filteredSubscriptions_opportunistic_grouped_configFalse_showsActive_4() =
        kosmos.runTest {
            val (sub3, sub4) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_3_ID, SUB_4_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub3, sub4))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_4_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            // Filtered subscriptions should show the active one when the config is false
            assertThat(latest).isEqualTo(listOf(sub4))
        }

    @Test
    fun filteredSubscriptions_oneOpportunistic_grouped_configTrue_showsPrimary_active_1() =
        kosmos.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(false, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_1_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(true)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            // Filtered subscriptions should show the primary (non-opportunistic) if the config is
            // true
            assertThat(latest).isEqualTo(listOf(sub1))
        }

    @Test
    fun filteredSubscriptions_oneOpportunistic_grouped_configTrue_showsPrimary_nonActive_1() =
        kosmos.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(false, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(true)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            // Filtered subscriptions should show the primary (non-opportunistic) if the config is
            // true
            assertThat(latest).isEqualTo(listOf(sub1))
        }

    @Test
    fun filteredSubscriptions_vcnSubId_agreesWithActiveSubId_usesActiveAkaVcnSub() =
        kosmos.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            kosmos.connectivityRepository.fake.vcnSubId.value = SUB_3_ID
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub3))
        }

    @Test
    fun filteredSubscriptions_vcnSubId_disagreesWithActiveSubId_usesVcnSub() =
        kosmos.runTest {
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )
            connectionsRepository.setSubscriptions(listOf(sub1, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)
            kosmos.connectivityRepository.fake.vcnSubId.value = SUB_1_ID
            whenever(carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault)
                .thenReturn(false)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub1))
        }

    @Test
    fun filteredSubscriptions_doesNotFilterProvisioningWhenFlagIsFalse() =
        kosmos.runTest {
            // GIVEN the flag is false
            featureFlagsClassic.fake.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, false)

            // GIVEN 1 sub that is in PROFILE_CLASS_PROVISIONING
            val sub1 =
                SubscriptionModel(
                    subscriptionId = SUB_1_ID,
                    isOpportunistic = false,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            connectionsRepository.setSubscriptions(listOf(sub1))

            // WHEN filtering is applied
            val latest by collectLastValue(underTest.filteredSubscriptions)

            // THEN the provisioning sub is still present (unfiltered)
            assertThat(latest).isEqualTo(listOf(sub1))
        }

    @Test
    fun filteredSubscriptions_filtersOutProvisioningSubs() =
        kosmos.runTest {
            val sub1 =
                SubscriptionModel(
                    subscriptionId = SUB_1_ID,
                    isOpportunistic = false,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_UNSET,
                )
            val sub2 =
                SubscriptionModel(
                    subscriptionId = SUB_2_ID,
                    isOpportunistic = false,
                    carrierName = "Carrier 2",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            connectionsRepository.setSubscriptions(listOf(sub1, sub2))

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub1))
        }

    /** Note: I'm not sure if this will ever be the case, but we can test it at least */
    @Test
    fun filteredSubscriptions_filtersOutProvisioningSubsBeforeOpportunistic() =
        kosmos.runTest {
            // This is a contrived test case, where the active subId is the one that would
            // also be filtered by opportunistic filtering.

            // GIVEN grouped, opportunistic subscriptions
            val groupUuid = ParcelUuid(UUID.randomUUID())
            val sub1 =
                SubscriptionModel(
                    subscriptionId = 1,
                    isOpportunistic = true,
                    groupUuid = groupUuid,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            val sub2 =
                SubscriptionModel(
                    subscriptionId = 2,
                    isOpportunistic = true,
                    groupUuid = groupUuid,
                    carrierName = "Carrier 2",
                    profileClass = PROFILE_CLASS_UNSET,
                )

            // GIVEN active subId is 1
            connectionsRepository.setSubscriptions(listOf(sub1, sub2))
            connectionsRepository.setActiveMobileDataSubscriptionId(1)

            // THEN filtering of provisioning subs takes place first, and we result in sub2

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub2))
        }

    @Test
    fun filteredSubscriptions_groupedPairAndNonProvisioned_groupedFilteringStillHappens() =
        kosmos.runTest {
            // Grouped filtering only happens when the list of subs is length 2. In this case
            // we'll show that filtering of provisioning subs happens before, and thus grouped
            // filtering happens even though the unfiltered list is length 3
            val (sub1, sub3) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_1_ID, SUB_3_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )

            val sub2 =
                SubscriptionModel(
                    subscriptionId = 2,
                    isOpportunistic = true,
                    groupUuid = null,
                    carrierName = "Carrier 2",
                    profileClass = PROFILE_CLASS_PROVISIONING,
                )

            connectionsRepository.setSubscriptions(listOf(sub1, sub2, sub3))
            connectionsRepository.setActiveMobileDataSubscriptionId(1)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(sub1))
        }

    @Test
    fun filteredSubscriptions_subNotExclusivelyNonTerrestrial_hasSub() =
        kosmos.runTest {
            val notExclusivelyNonTerrestrialSub =
                SubscriptionModel(
                    isExclusivelyNonTerrestrial = false,
                    subscriptionId = 5,
                    carrierName = "Carrier 5",
                    profileClass = PROFILE_CLASS_UNSET,
                )

            connectionsRepository.setSubscriptions(listOf(notExclusivelyNonTerrestrialSub))

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(notExclusivelyNonTerrestrialSub))
        }

    @Test
    fun filteredSubscriptions_subExclusivelyNonTerrestrial_doesNotHaveSub() =
        kosmos.runTest {
            val exclusivelyNonTerrestrialSub =
                SubscriptionModel(
                    isExclusivelyNonTerrestrial = true,
                    subscriptionId = 5,
                    carrierName = "Carrier 5",
                    profileClass = PROFILE_CLASS_UNSET,
                )

            connectionsRepository.setSubscriptions(listOf(exclusivelyNonTerrestrialSub))

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEmpty()
        }

    @Test
    fun filteredSubscription_mixOfExclusivelyNonTerrestrialAndOther_hasOtherSubsOnly() =
        kosmos.runTest {
            val exclusivelyNonTerrestrialSub =
                SubscriptionModel(
                    isExclusivelyNonTerrestrial = true,
                    subscriptionId = 5,
                    carrierName = "Carrier 5",
                    profileClass = PROFILE_CLASS_UNSET,
                )
            val otherSub1 =
                SubscriptionModel(
                    isExclusivelyNonTerrestrial = false,
                    subscriptionId = 1,
                    carrierName = "Carrier 1",
                    profileClass = PROFILE_CLASS_UNSET,
                )
            val otherSub2 =
                SubscriptionModel(
                    isExclusivelyNonTerrestrial = false,
                    subscriptionId = 2,
                    carrierName = "Carrier 2",
                    profileClass = PROFILE_CLASS_UNSET,
                )

            connectionsRepository.setSubscriptions(
                listOf(otherSub1, exclusivelyNonTerrestrialSub, otherSub2)
            )

            val latest by collectLastValue(underTest.filteredSubscriptions)

            assertThat(latest).isEqualTo(listOf(otherSub1, otherSub2))
        }

    @Test
    fun filteredSubscriptions_exclusivelyNonTerrestrialSub_andOpportunistic_bothFiltersHappen() =
        kosmos.runTest {
            // Exclusively non-terrestrial sub
            val exclusivelyNonTerrestrialSub =
                SubscriptionModel(
                    isExclusivelyNonTerrestrial = true,
                    subscriptionId = 5,
                    carrierName = "Carrier 5",
                    profileClass = PROFILE_CLASS_UNSET,
                )

            // Opportunistic subs
            val (sub3, sub4) =
                createSubscriptionPair(
                    subscriptionIds = Pair(SUB_3_ID, SUB_4_ID),
                    opportunistic = Pair(true, true),
                    grouped = true,
                )

            // WHEN both an exclusively non-terrestrial sub and opportunistic sub pair is included
            connectionsRepository.setSubscriptions(listOf(sub3, sub4, exclusivelyNonTerrestrialSub))
            connectionsRepository.setActiveMobileDataSubscriptionId(SUB_3_ID)

            val latest by collectLastValue(underTest.filteredSubscriptions)

            // THEN both the only-non-terrestrial sub and the non-active sub are filtered out,
            // leaving only sub3.
            assertThat(latest).isEqualTo(listOf(sub3))
        }

    @Test
    fun activeDataConnection_turnedOn() =
        kosmos.runTest {
            CONNECTION_1.setDataEnabled(true)

            val latest by collectLastValue(underTest.activeDataConnectionHasDataEnabled)

            assertThat(latest).isTrue()
        }

    @Test
    fun activeDataConnection_turnedOff() =
        kosmos.runTest {
            CONNECTION_1.setDataEnabled(true)
            val latest by collectLastValue(underTest.activeDataConnectionHasDataEnabled)

            CONNECTION_1.setDataEnabled(false)

            assertThat(latest).isFalse()
        }

    @Test
    fun activeDataConnection_invalidSubId() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.activeDataConnectionHasDataEnabled)

            connectionsRepository.setActiveMobileDataSubscriptionId(INVALID_SUBSCRIPTION_ID)

            // An invalid active subId should tell us that data is off
            assertThat(latest).isFalse()
        }

    @Test
    fun failedConnection_default_validated_notFailed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true

            assertThat(latest).isFalse()
        }

    @Test
    fun failedConnection_notDefault_notValidated_notFailed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.defaultConnectionIsValidated.value = false

            assertThat(latest).isFalse()
        }

    @Test
    fun failedConnection_default_notValidated_failed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = false

            assertThat(latest).isTrue()
        }

    @Test
    fun failedConnection_carrierMergedDefault_notValidated_failed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.hasCarrierMergedConnection.value = true
            connectionsRepository.defaultConnectionIsValidated.value = false

            assertThat(latest).isTrue()
        }

    /** Regression test for b/275076959. */
    @Test
    fun failedConnection_dataSwitchInSameGroup_notFailed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true
            runCurrent()

            // WHEN there's a data change in the same subscription group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.defaultConnectionIsValidated.value = false
            runCurrent()

            // THEN the default connection is *not* marked as failed because of forced validation
            assertThat(latest).isFalse()
        }

    @Test
    fun failedConnection_dataSwitchNotInSameGroup_isFailed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true
            runCurrent()

            // WHEN the connection is invalidated without a activeSubChangedInGroupEvent
            connectionsRepository.defaultConnectionIsValidated.value = false

            // THEN the connection is immediately marked as failed
            assertThat(latest).isTrue()
        }

    @Test
    fun alwaysShowDataRatIcon_configHasTrue() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.alwaysShowDataRatIcon)

            val config = MobileMappings.Config()
            config.alwaysShowDataRatIcon = true
            connectionsRepository.defaultDataSubRatConfig.value = config

            assertThat(latest).isTrue()
        }

    @Test
    fun alwaysShowDataRatIcon_configHasFalse() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.alwaysShowDataRatIcon)

            val config = MobileMappings.Config()
            config.alwaysShowDataRatIcon = false
            connectionsRepository.defaultDataSubRatConfig.value = config

            assertThat(latest).isFalse()
        }

    @Test
    fun alwaysUseCdmaLevel_configHasTrue() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.alwaysUseCdmaLevel)

            val config = MobileMappings.Config()
            config.alwaysShowCdmaRssi = true
            connectionsRepository.defaultDataSubRatConfig.value = config

            assertThat(latest).isTrue()
        }

    @Test
    fun alwaysUseCdmaLevel_configHasFalse() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.alwaysUseCdmaLevel)

            val config = MobileMappings.Config()
            config.alwaysShowCdmaRssi = false
            connectionsRepository.defaultDataSubRatConfig.value = config

            assertThat(latest).isFalse()
        }

    @Test
    fun isSingleCarrier_zeroSubscriptions_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isSingleCarrier)

            connectionsRepository.setSubscriptions(emptyList())

            assertThat(latest).isFalse()
        }

    @Test
    fun isSingleCarrier_oneSubscription_true() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isSingleCarrier)

            connectionsRepository.setSubscriptions(listOf(SUB_1))

            assertThat(latest).isTrue()
        }

    @Test
    fun isSingleCarrier_twoSubscriptions_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isSingleCarrier)

            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))

            assertThat(latest).isFalse()
        }

    @Test
    fun isSingleCarrier_updates() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isSingleCarrier)

            connectionsRepository.setSubscriptions(listOf(SUB_1))
            assertThat(latest).isTrue()

            connectionsRepository.setSubscriptions(listOf(SUB_1, SUB_2))
            assertThat(latest).isFalse()
        }

    @Test
    fun mobileIsDefault_mobileFalseAndCarrierMergedFalse_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.mobileIsDefault)

            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.hasCarrierMergedConnection.value = false

            assertThat(latest).isFalse()
        }

    @Test
    fun mobileIsDefault_mobileTrueAndCarrierMergedFalse_true() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.mobileIsDefault)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.hasCarrierMergedConnection.value = false

            assertThat(latest).isTrue()
        }

    /** Regression test for b/272586234. */
    @Test
    fun mobileIsDefault_mobileFalseAndCarrierMergedTrue_true() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.mobileIsDefault)

            connectionsRepository.mobileIsDefault.value = false
            connectionsRepository.hasCarrierMergedConnection.value = true

            assertThat(latest).isTrue()
        }

    @Test
    fun mobileIsDefault_updatesWhenRepoUpdates() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.mobileIsDefault)

            connectionsRepository.mobileIsDefault.value = true
            assertThat(latest).isTrue()

            connectionsRepository.mobileIsDefault.value = false
            assertThat(latest).isFalse()

            connectionsRepository.hasCarrierMergedConnection.value = true
            assertThat(latest).isTrue()
        }

    // The data switch tests are mostly testing the [forcingCellularValidation] flow, but that flow
    // is private and can only be tested by looking at [isDefaultConnectionFailed].

    @Test
    fun dataSwitch_inSameGroup_validatedMatchesPreviousValue_expiresAfter2s() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true
            runCurrent()

            // Trigger a data change in the same subscription group that's not yet validated
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.defaultConnectionIsValidated.value = false
            runCurrent()

            // After 1s, the force validation bit is still present, so the connection is not marked
            // as failed
            testScope.advanceTimeBy(1000)
            assertThat(latest).isFalse()

            // After 2s, the force validation expires so the connection updates to failed
            testScope.advanceTimeBy(1001)
            assertThat(latest).isTrue()
        }

    @Test
    fun dataSwitch_inSameGroup_notValidated_immediatelyMarkedAsFailed() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = false
            runCurrent()

            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            assertThat(latest).isTrue()
        }

    @Test
    fun dataSwitch_loseValidation_thenSwitchHappens_clearsForcedBit() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)

            // GIVEN the network starts validated
            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true
            runCurrent()

            // WHEN a data change happens in the same group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            // WHEN the validation bit is lost
            connectionsRepository.defaultConnectionIsValidated.value = false
            runCurrent()

            // WHEN another data change happens in the same group
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            // THEN the forced validation bit is still used...
            assertThat(latest).isFalse()

            testScope.advanceTimeBy(1000)
            assertThat(latest).isFalse()

            // ... but expires after 2s
            testScope.advanceTimeBy(1001)
            assertThat(latest).isTrue()
        }

    @Test
    fun dataSwitch_whileAlreadyForcingValidation_resetsClock() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDefaultConnectionFailed)
            connectionsRepository.mobileIsDefault.value = true
            connectionsRepository.defaultConnectionIsValidated.value = true
            runCurrent()

            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)

            testScope.advanceTimeBy(1000)

            // WHEN another change in same group event happens
            connectionsRepository.activeSubChangedInGroupEvent.emit(Unit)
            connectionsRepository.defaultConnectionIsValidated.value = false
            runCurrent()

            // THEN the forced validation remains for exactly 2 more seconds from now

            // 1.500s from second event
            testScope.advanceTimeBy(1500)
            assertThat(latest).isFalse()

            // 2.001s from the second event
            testScope.advanceTimeBy(501)
            assertThat(latest).isTrue()
        }

    @Test
    fun isForceHidden_repoHasMobileHidden_true() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isForceHidden)

            kosmos.connectivityRepository.fake.setForceHiddenIcons(setOf(ConnectivitySlot.MOBILE))

            assertThat(latest).isTrue()
        }

    @Test
    fun isForceHidden_repoDoesNotHaveMobileHidden_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isForceHidden)

            kosmos.connectivityRepository.fake.setForceHiddenIcons(setOf(ConnectivitySlot.WIFI))

            assertThat(latest).isFalse()
        }

    @Test
    fun iconInteractor_cachedPerSubId() =
        kosmos.runTest {
            val interactor1 = underTest.getMobileConnectionInteractorForSubId(SUB_1_ID)
            val interactor2 = underTest.getMobileConnectionInteractorForSubId(SUB_1_ID)

            assertThat(interactor1).isNotNull()
            assertThat(interactor1).isSameInstanceAs(interactor2)
        }

    @Test
    fun deviceBasedEmergencyMode_emergencyCallsOnly_followsDeviceServiceStateFromRepo() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isDeviceInEmergencyCallsOnlyMode)

            connectionsRepository.isDeviceEmergencyCallCapable.value = true

            assertThat(latest).isTrue()

            connectionsRepository.isDeviceEmergencyCallCapable.value = false

            assertThat(latest).isFalse()
        }

    /**
     * Convenience method for creating a pair of subscriptions to test the filteredSubscriptions
     * flow.
     */
    private fun createSubscriptionPair(
        subscriptionIds: Pair<Int, Int>,
        opportunistic: Pair<Boolean, Boolean> = Pair(false, false),
        grouped: Boolean = false,
    ): Pair<SubscriptionModel, SubscriptionModel> {
        val groupUuid = if (grouped) ParcelUuid(UUID.randomUUID()) else null
        val sub1 =
            SubscriptionModel(
                subscriptionId = subscriptionIds.first,
                isOpportunistic = opportunistic.first,
                groupUuid = groupUuid,
                carrierName = "Carrier ${subscriptionIds.first}",
                profileClass = PROFILE_CLASS_UNSET,
            )

        val sub2 =
            SubscriptionModel(
                subscriptionId = subscriptionIds.second,
                isOpportunistic = opportunistic.second,
                groupUuid = groupUuid,
                carrierName = "Carrier ${opportunistic.second}",
                profileClass = PROFILE_CLASS_UNSET,
            )

        return Pair(sub1, sub2)
    }

    companion object {

        private const val SUB_1_ID = 1
        private val SUB_1 =
            SubscriptionModel(
                subscriptionId = SUB_1_ID,
                carrierName = "Carrier $SUB_1_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_1 = FakeMobileConnectionRepository(SUB_1_ID, mock())

        private const val SUB_2_ID = 2
        private val SUB_2 =
            SubscriptionModel(
                subscriptionId = SUB_2_ID,
                carrierName = "Carrier $SUB_2_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_2 = FakeMobileConnectionRepository(SUB_2_ID, mock())

        private const val SUB_3_ID = 3
        private val SUB_3_OPP =
            SubscriptionModel(
                subscriptionId = SUB_3_ID,
                isOpportunistic = true,
                groupUuid = ParcelUuid(UUID.randomUUID()),
                carrierName = "Carrier $SUB_3_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_3 = FakeMobileConnectionRepository(SUB_3_ID, mock())

        private const val SUB_4_ID = 4
        private val SUB_4_OPP =
            SubscriptionModel(
                subscriptionId = SUB_4_ID,
                isOpportunistic = true,
                groupUuid = ParcelUuid(UUID.randomUUID()),
                carrierName = "Carrier $SUB_4_ID",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val CONNECTION_4 = FakeMobileConnectionRepository(SUB_4_ID, mock())
    }
}
