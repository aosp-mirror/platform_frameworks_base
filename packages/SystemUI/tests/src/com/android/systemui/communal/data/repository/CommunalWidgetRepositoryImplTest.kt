/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.shared.CommunalWidgetHost
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var appWidgetManager: AppWidgetManager

    @Mock private lateinit var appWidgetHost: AppWidgetHost

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    @Mock private lateinit var userManager: UserManager

    @Mock private lateinit var userHandle: UserHandle

    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var stopwatchProviderInfo: AppWidgetProviderInfo

    @Mock private lateinit var providerInfoA: AppWidgetProviderInfo

    @Mock private lateinit var communalWidgetHost: CommunalWidgetHost

    @Mock private lateinit var communalWidgetDao: CommunalWidgetDao

    private lateinit var communalRepository: FakeCommunalRepository

    private lateinit var logBuffer: LogBuffer

    private val testDispatcher = StandardTestDispatcher()

    private val testScope = TestScope(testDispatcher)

    private val fakeAllowlist =
        listOf(
            "com.android.fake/WidgetProviderA",
            "com.android.fake/WidgetProviderB",
            "com.android.fake/WidgetProviderC",
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        logBuffer = FakeLogBuffer.Factory.create()
        communalRepository = FakeCommunalRepository()

        communalEnabled(true)
        setAppWidgetIds(emptyList())

        overrideResource(R.array.config_communalWidgetAllowlist, fakeAllowlist.toTypedArray())

        whenever(stopwatchProviderInfo.loadLabel(any())).thenReturn("Stopwatch")
        whenever(userTracker.userHandle).thenReturn(userHandle)
        whenever(communalWidgetDao.getWidgets()).thenReturn(flowOf(emptyMap()))
    }

    @Test
    fun neverQueryDbForWidgets_whenFeatureIsDisabled() =
        testScope.runTest {
            communalEnabled(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()
            runCurrent()

            verify(communalWidgetDao, Mockito.never()).getWidgets()
        }

    @Test
    fun neverQueryDbForWidgets_whenFeatureEnabled_andUserLocked() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()
            runCurrent()

            verify(communalWidgetDao, Mockito.never()).getWidgets()
        }

    @Test
    fun communalWidgets_whenUserUnlocked_queryWidgetsFromDb() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            val communalWidgets = collectLastValue(repository.communalWidgets)
            communalWidgets()
            runCurrent()
            val communalItemRankEntry = CommunalItemRank(uid = 1L, rank = 1)
            val communalWidgetItemEntry = CommunalWidgetItem(uid = 1L, 1, "pk_name/cls_name", 1L)
            whenever(communalWidgetDao.getWidgets())
                .thenReturn(flowOf(mapOf(communalItemRankEntry to communalWidgetItemEntry)))
            whenever(appWidgetManager.getAppWidgetInfo(anyInt())).thenReturn(providerInfoA)

            userUnlocked(true)
            installedProviders(listOf(stopwatchProviderInfo))
            broadcastReceiverUpdate()
            runCurrent()

            verify(communalWidgetDao).getWidgets()
            assertThat(communalWidgets())
                .containsExactly(
                    CommunalWidgetContentModel(
                        appWidgetId = communalWidgetItemEntry.widgetId,
                        providerInfo = providerInfoA,
                        priority = communalItemRankEntry.rank,
                    )
                )
        }

    @Test
    fun addWidget_allocateId_bindWidget_andAddToDb() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            runCurrent()

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.allocateIdAndBindWidget(any<ComponentName>()))
                .thenReturn(id)
            repository.addWidget(provider, priority)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao).addWidget(id, provider, priority)
        }

    @Test
    fun deleteWidget_removeWidgetId_andDeleteFromDb() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            runCurrent()

            val id = 1
            repository.deleteWidget(id)
            runCurrent()

            verify(communalWidgetDao).deleteWidgetById(id)
            verify(appWidgetHost).deleteAppWidgetId(id)
        }

    @Test
    fun broadcastReceiver_communalDisabled_doNotRegisterUserUnlockedBroadcastReceiver() =
        testScope.runTest {
            communalEnabled(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()
            verifyBroadcastReceiverNeverRegistered()
        }

    @Test
    fun broadcastReceiver_featureEnabledAndUserUnlocked_doNotRegisterBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()
            verifyBroadcastReceiverNeverRegistered()
        }

    @Test
    fun broadcastReceiver_featureEnabledAndUserLocked_registerBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()
            verifyBroadcastReceiverRegistered()
        }

    @Test
    fun broadcastReceiver_whenFlowFinishes_unregisterBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()

            val job = launch { repository.communalWidgets.collect() }
            runCurrent()
            val receiver = broadcastReceiverUpdate()

            job.cancel()
            runCurrent()

            verify(broadcastDispatcher).unregisterReceiver(receiver)
        }

    @Test
    fun appWidgetHost_userUnlocked_startListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()
            verify(appWidgetHost, Mockito.never()).startListening()

            userUnlocked(true)
            broadcastReceiverUpdate()
            collectLastValue(repository.communalWidgets)()

            verify(appWidgetHost).startListening()
        }

    @Test
    fun appWidgetHost_userLockedAgain_stopListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.communalWidgets)()

            userUnlocked(true)
            broadcastReceiverUpdate()
            collectLastValue(repository.communalWidgets)()

            verify(appWidgetHost).startListening()
            verify(appWidgetHost, Mockito.never()).stopListening()

            userUnlocked(false)
            broadcastReceiverUpdate()
            collectLastValue(repository.communalWidgets)()

            verify(appWidgetHost).stopListening()
        }

    private fun initCommunalWidgetRepository(): CommunalWidgetRepositoryImpl {
        return CommunalWidgetRepositoryImpl(
            appWidgetManager,
            appWidgetHost,
            testScope.backgroundScope,
            testDispatcher,
            broadcastDispatcher,
            communalRepository,
            communalWidgetHost,
            communalWidgetDao,
            userManager,
            userTracker,
            logBuffer,
        )
    }

    private fun verifyBroadcastReceiverRegistered() {
        verify(broadcastDispatcher)
            .registerReceiver(
                any(),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable(),
            )
    }

    private fun verifyBroadcastReceiverNeverRegistered() {
        verify(broadcastDispatcher, Mockito.never())
            .registerReceiver(
                any(),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable(),
            )
    }

    private fun broadcastReceiverUpdate(): BroadcastReceiver {
        val broadcastReceiverCaptor = kotlinArgumentCaptor<BroadcastReceiver>()
        verify(broadcastDispatcher)
            .registerReceiver(
                broadcastReceiverCaptor.capture(),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable(),
            )
        broadcastReceiverCaptor.value.onReceive(null, null)
        return broadcastReceiverCaptor.value
    }

    private fun communalEnabled(enabled: Boolean) {
        communalRepository.setIsCommunalEnabled(enabled)
    }

    private fun userUnlocked(userUnlocked: Boolean) {
        whenever(userManager.isUserUnlockingOrUnlocked(userHandle)).thenReturn(userUnlocked)
    }

    private fun installedProviders(providers: List<AppWidgetProviderInfo>) {
        whenever(appWidgetManager.installedProviders).thenReturn(providers)
    }

    private fun setAppWidgetIds(ids: List<Int>) {
        whenever(appWidgetHost.appWidgetIds).thenReturn(ids.toIntArray())
    }
}
