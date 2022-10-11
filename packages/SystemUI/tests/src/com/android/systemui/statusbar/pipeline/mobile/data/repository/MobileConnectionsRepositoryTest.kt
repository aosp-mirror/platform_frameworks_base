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

import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileConnectionsRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: MobileConnectionsRepositoryImpl

    @Mock private lateinit var subscriptionManager: SubscriptionManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    private val scope = CoroutineScope(IMMEDIATE)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(
                broadcastDispatcher.broadcastFlow(
                    any(),
                    nullable(),
                    ArgumentMatchers.anyInt(),
                    nullable(),
                )
            )
            .thenReturn(flowOf(Unit))

        underTest =
            MobileConnectionsRepositoryImpl(
                subscriptionManager,
                telephonyManager,
                logger,
                broadcastDispatcher,
                context,
                IMMEDIATE,
                scope,
                mock(),
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun testSubscriptions_initiallyEmpty() =
        runBlocking(IMMEDIATE) {
            assertThat(underTest.subscriptionsFlow.value).isEqualTo(listOf<SubscriptionInfo>())
        }

    @Test
    fun testSubscriptions_listUpdates() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionInfo>? = null

            val job = underTest.subscriptionsFlow.onEach { latest = it }.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))

            job.cancel()
        }

    @Test
    fun testSubscriptions_removingSub_updatesList() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionInfo>? = null

            val job = underTest.subscriptionsFlow.onEach { latest = it }.launchIn(this)

            // WHEN 2 networks show up
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // WHEN one network is removed
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // THEN the subscriptions list represents the newest change
            assertThat(latest).isEqualTo(listOf(SUB_2))

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
            val job = underTest.subscriptionsFlow.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_1_ID)

            assertThat(repo1).isSameInstanceAs(repo2)

            job.cancel()
        }

    @Test
    fun testConnectionCache_clearsInvalidSubscriptions() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptionsFlow.launchIn(this)

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
    fun testConnectionRepository_invalidSubId_throws() =
        runBlocking(IMMEDIATE) {
            val job = underTest.subscriptionsFlow.launchIn(this)

            assertThrows(IllegalArgumentException::class.java) {
                underTest.getRepoForSubId(SUB_1_ID)
            }

            job.cancel()
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

        private const val SUB_2_ID = 2
        private val SUB_2 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_2_ID) }
    }
}
