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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.provider.Settings
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import com.android.internal.telephony.PhoneConstants
import com.android.settingslib.R
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.tableBufferLogName
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileConnectionsRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: MobileConnectionsRepositoryImpl

    private lateinit var connectionFactory: MobileConnectionRepositoryImpl.Factory
    private lateinit var carrierMergedFactory: CarrierMergedConnectionRepository.Factory
    private lateinit var fullConnectionFactory: FullMobileConnectionRepository.Factory
    private lateinit var wifiRepository: FakeWifiRepository
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var subscriptionManager: SubscriptionManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var logBufferFactory: TableLogBufferFactory

    private val mobileMappings = FakeMobileMappingsProxy()

    private val scope = CoroutineScope(IMMEDIATE)
    private val globalSettings = FakeSettings()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Set up so the individual connection repositories
        whenever(telephonyManager.createForSubscriptionId(anyInt())).thenAnswer { invocation ->
            telephonyManager.also {
                whenever(telephonyManager.subscriptionId).thenReturn(invocation.getArgument(0))
            }
        }

        whenever(logBufferFactory.getOrCreate(anyString(), anyInt())).thenAnswer { _ ->
            mock<TableLogBuffer>()
        }

        wifiRepository = FakeWifiRepository()

        connectionFactory =
            MobileConnectionRepositoryImpl.Factory(
                fakeBroadcastDispatcher,
                context = context,
                telephonyManager = telephonyManager,
                bgDispatcher = IMMEDIATE,
                globalSettings = globalSettings,
                logger = logger,
                mobileMappingsProxy = mobileMappings,
                scope = scope,
            )
        carrierMergedFactory =
            CarrierMergedConnectionRepository.Factory(
                scope,
                wifiRepository,
            )
        fullConnectionFactory =
            FullMobileConnectionRepository.Factory(
                scope = scope,
                logFactory = logBufferFactory,
                mobileRepoFactory = connectionFactory,
                carrierMergedRepoFactory = carrierMergedFactory,
            )

        underTest =
            MobileConnectionsRepositoryImpl(
                connectivityManager,
                subscriptionManager,
                telephonyManager,
                logger,
                mobileMappings,
                fakeBroadcastDispatcher,
                globalSettings,
                context,
                IMMEDIATE,
                scope,
                wifiRepository,
                fullConnectionFactory,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun testSubscriptions_initiallyEmpty() =
        runBlocking(IMMEDIATE) {
            assertThat(underTest.subscriptions.value).isEqualTo(listOf<SubscriptionModel>())
        }

    @Test
    fun testSubscriptions_listUpdates() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionModel>? = null

            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(MODEL_1, MODEL_2))

            job.cancel()
        }

    @Test
    fun testSubscriptions_removingSub_updatesList() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionModel>? = null

            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            // WHEN 2 networks show up
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // WHEN one network is removed
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // THEN the subscriptions list represents the newest change
            assertThat(latest).isEqualTo(listOf(MODEL_2))

            job.cancel()
        }

    @Test
    fun testSubscriptions_carrierMergedOnly_listHasCarrierMerged() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionModel>? = null

            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(MODEL_CM))

            job.cancel()
        }

    @Test
    fun testSubscriptions_carrierMergedAndOther_listHasBothWithCarrierMergedLast() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionModel>? = null

            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(MODEL_1, MODEL_2, MODEL_CM))

            job.cancel()
        }

    @Test
    fun testActiveDataSubscriptionId_initialValueIsInvalidId() =
        runBlocking(IMMEDIATE) {
            assertThat(underTest.activeMobileDataSubscriptionId.value)
                .isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        }

    @Test
    fun testActiveDataSubscriptionId_updates() =
        runBlocking(IMMEDIATE) {
            var active: Int? = null

            val job = underTest.activeMobileDataSubscriptionId.onEach { active = it }.launchIn(this)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(active).isEqualTo(SUB_2_ID)

            job.cancel()
        }

    @Test
    fun testConnectionRepository_validSubId_isCached() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_1_ID)

            assertThat(repo1).isSameInstanceAs(repo2)

            job.cancel()
        }

    @Test
    fun testConnectionRepository_carrierMergedSubId_isCached() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1 = underTest.getRepoForSubId(SUB_CM_ID)
            val repo2 = underTest.getRepoForSubId(SUB_CM_ID)

            assertThat(repo1).isSameInstanceAs(repo2)

            job.cancel()
        }

    @Test
    fun testConnectionRepository_carrierMergedAndMobileSubs_usesCorrectRepos() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val carrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            val mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(carrierMergedRepo.getIsCarrierMerged()).isTrue()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            job.cancel()
        }

    @Test
    fun testSubscriptions_subNoLongerCarrierMerged_repoUpdates() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val carrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            var mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(carrierMergedRepo.getIsCarrierMerged()).isTrue()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            // WHEN the wifi network updates to be not carrier merged
            wifiRepository.setWifiNetwork(WifiNetworkModel.Active(networkId = 4, level = 1))

            // THEN the repos update
            val noLongerCarrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(noLongerCarrierMergedRepo.getIsCarrierMerged()).isFalse()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            job.cancel()
        }

    @Test
    fun testSubscriptions_subBecomesCarrierMerged_repoUpdates() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val notYetCarrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            var mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(notYetCarrierMergedRepo.getIsCarrierMerged()).isFalse()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            // WHEN the wifi network updates to be carrier merged
            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)

            // THEN the repos update
            val carrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(carrierMergedRepo.getIsCarrierMerged()).isTrue()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            job.cancel()
        }

    @Test
    fun testConnectionCache_clearsInvalidSubscriptions() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger caching
            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_2_ID)

            assertThat(underTest.getSubIdRepoCache())
                .containsExactly(SUB_1_ID, repo1, SUB_2_ID, repo2)

            // SUB_2 disappears
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(underTest.getSubIdRepoCache()).containsExactly(SUB_1_ID, repo1)

            job.cancel()
        }

    @Test
    fun testConnectionCache_clearsInvalidSubscriptions_includingCarrierMerged() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            wifiRepository.setWifiNetwork(WIFI_NETWORK_CM)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger caching
            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_2_ID)
            val repoCarrierMerged = underTest.getRepoForSubId(SUB_CM_ID)

            assertThat(underTest.getSubIdRepoCache())
                .containsExactly(SUB_1_ID, repo1, SUB_2_ID, repo2, SUB_CM_ID, repoCarrierMerged)

            // SUB_2 and SUB_CM disappear
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(underTest.getSubIdRepoCache()).containsExactly(SUB_1_ID, repo1)

            job.cancel()
        }

    /** Regression test for b/261706421 */
    @Test
    fun testConnectionsCache_clearMultipleSubscriptionsAtOnce_doesNotThrow() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger caching
            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_2_ID)

            assertThat(underTest.getSubIdRepoCache())
                .containsExactly(SUB_1_ID, repo1, SUB_2_ID, repo2)

            // All subscriptions disappear
            whenever(subscriptionManager.completeActiveSubscriptionInfoList).thenReturn(listOf())
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(underTest.getSubIdRepoCache()).isEmpty()

            job.cancel()
        }

    @Test
    fun testConnectionRepository_invalidSubId_throws() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            assertThrows(IllegalArgumentException::class.java) {
                underTest.getRepoForSubId(SUB_1_ID)
            }

            job.cancel()
        }

    @Test
    fun `connection repository - log buffer contains sub id in its name`() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptions.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger creation
            underTest.getRepoForSubId(SUB_1_ID)
            verify(logBufferFactory)
                .getOrCreate(
                    eq(tableBufferLogName(SUB_1_ID)),
                    anyInt(),
                )
            underTest.getRepoForSubId(SUB_2_ID)
            verify(logBufferFactory)
                .getOrCreate(
                    eq(tableBufferLogName(SUB_2_ID)),
                    anyInt(),
                )

            job.cancel()
        }

    @Test
    fun testDefaultDataSubId_updatesOnBroadcast() =
        runBlocking(IMMEDIATE) {
            var latest: Int? = null
            val job = underTest.defaultDataSubId.onEach { latest = it }.launchIn(this)

            fakeBroadcastDispatcher.registeredReceivers.forEach { receiver ->
                receiver.onReceive(
                    context,
                    Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, SUB_2_ID)
                )
            }

            assertThat(latest).isEqualTo(SUB_2_ID)

            fakeBroadcastDispatcher.registeredReceivers.forEach { receiver ->
                receiver.onReceive(
                    context,
                    Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, SUB_1_ID)
                )
            }

            assertThat(latest).isEqualTo(SUB_1_ID)

            job.cancel()
        }

    @Test
    fun mobileConnectivity_default() {
        assertThat(underTest.defaultMobileNetworkConnectivity.value)
            .isEqualTo(MobileConnectivityModel(isConnected = false, isValidated = false))
    }

    @Test
    fun mobileConnectivity_isConnected_isValidated() =
        runBlocking(IMMEDIATE) {
            val caps = createCapabilities(connected = true, validated = true)

            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest)
                .isEqualTo(MobileConnectivityModel(isConnected = true, isValidated = true))

            job.cancel()
        }

    @Test
    fun globalMobileDataSettingsChangedEvent_producesOnSettingChange() =
        runBlocking(IMMEDIATE) {
            var produced = false
            val job =
                underTest.globalMobileDataSettingChangedEvent
                    .onEach { produced = true }
                    .launchIn(this)

            assertThat(produced).isFalse()

            globalSettings.putInt(Settings.Global.MOBILE_DATA, 0)

            assertThat(produced).isTrue()

            job.cancel()
        }

    @Test
    fun mobileConnectivity_isConnected_isNotValidated() =
        runBlocking(IMMEDIATE) {
            val caps = createCapabilities(connected = true, validated = false)

            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest)
                .isEqualTo(MobileConnectivityModel(isConnected = true, isValidated = false))

            job.cancel()
        }

    @Test
    fun mobileConnectivity_isNotConnected_isNotValidated() =
        runBlocking(IMMEDIATE) {
            val caps = createCapabilities(connected = false, validated = false)

            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest)
                .isEqualTo(MobileConnectivityModel(isConnected = false, isValidated = false))

            job.cancel()
        }

    /** In practice, I don't think this state can ever happen (!connected, validated) */
    @Test
    fun mobileConnectivity_isNotConnected_isValidated() =
        runBlocking(IMMEDIATE) {
            val caps = createCapabilities(connected = false, validated = true)

            var latest: MobileConnectivityModel? = null
            val job =
                underTest.defaultMobileNetworkConnectivity.onEach { latest = it }.launchIn(this)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isEqualTo(MobileConnectivityModel(false, true))

            job.cancel()
        }

    @Test
    fun config_initiallyFromContext() =
        runBlocking(IMMEDIATE) {
            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // The initial value will be fetched when the repo is created, so we need to override
            // the resources and then re-create the repo.
            underTest =
                MobileConnectionsRepositoryImpl(
                    connectivityManager,
                    subscriptionManager,
                    telephonyManager,
                    logger,
                    mobileMappings,
                    fakeBroadcastDispatcher,
                    globalSettings,
                    context,
                    IMMEDIATE,
                    scope,
                    wifiRepository,
                    fullConnectionFactory,
                )

            var latest: MobileMappings.Config? = null
            val job = underTest.defaultDataSubRatConfig.onEach { latest = it }.launchIn(this)

            assertTrue(latest!!.areEqual(configFromContext))
            assertTrue(latest!!.showAtLeast3G)

            job.cancel()
        }

    @Test
    fun config_subIdChangeEvent_updated() =
        runBlocking(IMMEDIATE) {
            var latest: MobileMappings.Config? = null
            val job = underTest.defaultDataSubRatConfig.onEach { latest = it }.launchIn(this)
            assertThat(latest!!.showAtLeast3G).isFalse()

            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // WHEN the change event is fired
            fakeBroadcastDispatcher.registeredReceivers.forEach { receiver ->
                receiver.onReceive(
                    context,
                    Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                        .putExtra(PhoneConstants.SUBSCRIPTION_KEY, SUB_1_ID)
                )
            }

            // THEN the config is updated
            assertTrue(latest!!.areEqual(configFromContext))
            assertTrue(latest!!.showAtLeast3G)

            job.cancel()
        }

    @Test
    fun config_carrierConfigChangeEvent_updated() =
        runBlocking(IMMEDIATE) {
            var latest: MobileMappings.Config? = null
            val job = underTest.defaultDataSubRatConfig.onEach { latest = it }.launchIn(this)
            assertThat(latest!!.showAtLeast3G).isFalse()

            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // WHEN the change event is fired
            fakeBroadcastDispatcher.registeredReceivers.forEach { receiver ->
                receiver.onReceive(
                    context,
                    Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)
                )
            }

            // THEN the config is updated
            assertThat(latest!!.areEqual(configFromContext)).isTrue()
            assertThat(latest!!.showAtLeast3G).isTrue()

            job.cancel()
        }

    private fun createCapabilities(connected: Boolean, validated: Boolean): NetworkCapabilities =
        mock<NetworkCapabilities>().also {
            whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(connected)
            whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(validated)
        }

    private fun getDefaultNetworkCallback(): ConnectivityManager.NetworkCallback {
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun getSubscriptionCallback(): SubscriptionManager.OnSubscriptionsChangedListener {
        val callbackCaptor = argumentCaptor<SubscriptionManager.OnSubscriptionsChangedListener>()
        verify(subscriptionManager)
            .addOnSubscriptionsChangedListener(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun getTelephonyCallbacks(): List<TelephonyCallback> {
        val callbackCaptor = argumentCaptor<TelephonyCallback>()
        verify(telephonyManager).registerTelephonyCallback(any(), callbackCaptor.capture())
        return callbackCaptor.allValues
    }

    private inline fun <reified T> getTelephonyCallbackForType(): T {
        val cbs = getTelephonyCallbacks().filterIsInstance<T>()
        assertThat(cbs.size).isEqualTo(1)
        return cbs[0]
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private const val SUB_1_ID = 1
        private val SUB_1 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_1_ID) }
        private val MODEL_1 = SubscriptionModel(subscriptionId = SUB_1_ID)

        private const val SUB_2_ID = 2
        private val SUB_2 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_2_ID) }
        private val MODEL_2 = SubscriptionModel(subscriptionId = SUB_2_ID)

        private const val NET_ID = 123
        private val NETWORK = mock<Network>().apply { whenever(getNetId()).thenReturn(NET_ID) }

        private const val SUB_CM_ID = 5
        private val SUB_CM =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_CM_ID) }
        private val MODEL_CM = SubscriptionModel(subscriptionId = SUB_CM_ID)
        private val WIFI_NETWORK_CM =
            WifiNetworkModel.CarrierMerged(
                networkId = 3,
                subscriptionId = SUB_CM_ID,
                level = 1,
            )
    }
}
