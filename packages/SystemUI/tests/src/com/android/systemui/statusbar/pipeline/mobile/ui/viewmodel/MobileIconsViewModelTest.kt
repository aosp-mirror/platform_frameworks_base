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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.VerboseMobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class MobileIconsViewModelTest : SysuiTestCase() {
    private lateinit var underTest: MobileIconsViewModel
    private val interactor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())
    private val flags = FakeFeatureFlagsClassic().also { it.set(Flags.NEW_NETWORK_SLICE_UI, false) }

    private lateinit var airplaneModeInteractor: AirplaneModeInteractor
    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var constants: ConnectivityConstants
    @Mock private lateinit var logger: MobileViewLogger
    @Mock private lateinit var verboseLogger: VerboseMobileViewLogger

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        airplaneModeInteractor =
            AirplaneModeInteractor(
                FakeAirplaneModeRepository(),
                FakeConnectivityRepository(),
                FakeMobileConnectionsRepository(),
            )

        underTest =
            MobileIconsViewModel(
                logger,
                verboseLogger,
                interactor,
                airplaneModeInteractor,
                constants,
                flags,
                testScope.backgroundScope,
            )

        interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
    }

    @Test
    fun subscriptionIdsFlow_matchesInteractor() =
        testScope.runTest {
            var latest: List<Int>? = null
            val job = underTest.subscriptionIdsFlow.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value =
                listOf(
                    SubscriptionModel(
                        subscriptionId = 1,
                        isOpportunistic = false,
                        carrierName = "Carrier 1",
                        profileClass = PROFILE_CLASS_UNSET,
                    ),
                )
            assertThat(latest).isEqualTo(listOf(1))

            interactor.filteredSubscriptions.value =
                listOf(
                    SubscriptionModel(
                        subscriptionId = 2,
                        isOpportunistic = false,
                        carrierName = "Carrier 2",
                        profileClass = PROFILE_CLASS_UNSET,
                    ),
                    SubscriptionModel(
                        subscriptionId = 5,
                        isOpportunistic = true,
                        carrierName = "Carrier 5",
                        profileClass = PROFILE_CLASS_UNSET,
                    ),
                    SubscriptionModel(
                        subscriptionId = 7,
                        isOpportunistic = true,
                        carrierName = "Carrier 7",
                        profileClass = PROFILE_CLASS_UNSET,
                    ),
                )
            assertThat(latest).isEqualTo(listOf(2, 5, 7))

            interactor.filteredSubscriptions.value = emptyList()
            assertThat(latest).isEmpty()

            job.cancel()
        }

    @Test
    fun caching_mobileIconViewModelIsReusedForSameSubId() =
        testScope.runTest {
            val model1 = underTest.viewModelForSub(1, StatusBarLocation.HOME)
            val model2 = underTest.viewModelForSub(1, StatusBarLocation.QS)

            assertThat(model1.commonImpl).isSameInstanceAs(model2.commonImpl)
        }

    @Test
    fun caching_invalidViewModelsAreRemovedFromCacheWhenSubDisappears() =
        testScope.runTest {
            // Retrieve models to trigger caching
            val model1 = underTest.viewModelForSub(1, StatusBarLocation.HOME)
            val model2 = underTest.viewModelForSub(2, StatusBarLocation.QS)

            // Both impls are cached
            assertThat(underTest.reuseCache.keys).containsExactly(1, 2)

            // SUB_1 is removed from the list...
            interactor.filteredSubscriptions.value = listOf(SUB_2)

            // ... and dropped from the cache
            assertThat(underTest.reuseCache.keys).containsExactly(2)
        }

    @Test
    fun caching_invalidatedViewModelsAreCanceled() =
        testScope.runTest {
            // Retrieve models to trigger caching
            val model1 = underTest.viewModelForSub(1, StatusBarLocation.HOME)
            val model2 = underTest.viewModelForSub(2, StatusBarLocation.QS)

            var scope1 = underTest.reuseCache[1]?.second
            var scope2 = underTest.reuseCache[2]?.second

            // Scopes are not canceled
            assertTrue(scope1!!.isActive)
            assertTrue(scope2!!.isActive)

            // SUB_1 is removed from the list...
            interactor.filteredSubscriptions.value = listOf(SUB_2)

            // scope1 is canceled
            assertFalse(scope1!!.isActive)
            assertTrue(scope2!!.isActive)
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_noSubs_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = emptyList()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_oneSub_notShowingRat_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1)
            // The unknown icon group doesn't show a RAT
            interactor.getInteractorForSubId(1)!!.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_oneSub_showingRat_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1)
            // The 3G icon group will show a RAT
            interactor.getInteractorForSubId(1)!!.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_updatesAsSubUpdates() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1)
            val sub1Interactor = interactor.getInteractorForSubId(1)!!

            sub1Interactor.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)
            assertThat(latest).isTrue()

            sub1Interactor.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)
            assertThat(latest).isFalse()

            sub1Interactor.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.LTE)
            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_multipleSubs_lastSubNotShowingRat_false() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
            interactor.getInteractorForSubId(1)?.networkTypeIconGroup?.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)
            interactor.getInteractorForSubId(2)!!.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_multipleSubs_lastSubShowingRat_true() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
            interactor.getInteractorForSubId(1)?.networkTypeIconGroup?.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)
            interactor.getInteractorForSubId(2)!!.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)

            assertThat(latest).isTrue()
            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_subListUpdates_valAlsoUpdates() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
            interactor.getInteractorForSubId(1)?.networkTypeIconGroup?.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)
            interactor.getInteractorForSubId(2)!!.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)

            assertThat(latest).isTrue()

            // WHEN the sub list gets new subscriptions where the last subscription is not showing
            // the network type icon
            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2, SUB_3)
            interactor.getInteractorForSubId(3)!!.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)

            // THEN the flow updates
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun firstMobileSubShowingNetworkTypeIcon_subListReorders_valAlsoUpdates() =
        testScope.runTest {
            var latest: Boolean? = null
            val job =
                underTest.firstMobileSubShowingNetworkTypeIcon.onEach { latest = it }.launchIn(this)

            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
            // Immediately switch the order so that we've created both interactors
            interactor.filteredSubscriptions.value = listOf(SUB_2, SUB_1)
            val sub1Interactor = interactor.getInteractorForSubId(1)!!
            val sub2Interactor = interactor.getInteractorForSubId(2)!!

            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
            sub1Interactor.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.UNKNOWN)
            sub2Interactor.networkTypeIconGroup.value =
                NetworkTypeIconModel.DefaultIcon(TelephonyIcons.THREE_G)
            assertThat(latest).isTrue()

            // WHEN sub1 becomes last and sub1 has no network type icon
            interactor.filteredSubscriptions.value = listOf(SUB_2, SUB_1)

            // THEN the flow updates
            assertThat(latest).isFalse()

            // WHEN sub2 becomes last and sub2 has a network type icon
            interactor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)

            // THEN the flow updates
            assertThat(latest).isTrue()

            job.cancel()
        }

    companion object {
        private val SUB_1 =
            SubscriptionModel(
                subscriptionId = 1,
                isOpportunistic = false,
                carrierName = "Carrier 1",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val SUB_2 =
            SubscriptionModel(
                subscriptionId = 2,
                isOpportunistic = false,
                carrierName = "Carrier 2",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val SUB_3 =
            SubscriptionModel(
                subscriptionId = 3,
                isOpportunistic = false,
                carrierName = "Carrier 3",
                profileClass = PROFILE_CLASS_UNSET,
            )
    }
}
