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

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.ACTION_USER_UNLOCKED
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.shared.CommunalWidgetHost
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var appWidgetManagerOptional: Optional<AppWidgetManager>

    @Mock private lateinit var appWidgetManager: AppWidgetManager

    @Mock private lateinit var appWidgetHost: CommunalAppWidgetHost

    @Mock private lateinit var userManager: UserManager

    @Mock private lateinit var userHandle: UserHandle

    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var stopwatchProviderInfo: AppWidgetProviderInfo

    @Mock private lateinit var providerInfoA: AppWidgetProviderInfo

    @Mock private lateinit var communalWidgetHost: CommunalWidgetHost

    @Mock private lateinit var communalWidgetDao: CommunalWidgetDao

    private val kosmos = testKosmos()

    private val testScope = kosmos.testScope

    private lateinit var communalRepository: FakeCommunalRepository

    private lateinit var logBuffer: LogBuffer

    private val fakeAllowlist =
        listOf(
            "com.android.fake/WidgetProviderA",
            "com.android.fake/WidgetProviderB",
            "com.android.fake/WidgetProviderC",
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        logBuffer = logcatLogBuffer(name = "CommunalWidgetRepoImplTest")
        communalRepository = kosmos.fakeCommunalRepository

        communalEnabled(true)
        setAppWidgetIds(emptyList())

        overrideResource(R.array.config_communalWidgetAllowlist, fakeAllowlist.toTypedArray())

        whenever(stopwatchProviderInfo.loadLabel(any())).thenReturn("Stopwatch")
        whenever(userTracker.userHandle).thenReturn(userHandle)
        whenever(communalWidgetDao.getWidgets()).thenReturn(flowOf(emptyMap()))
        whenever(appWidgetManagerOptional.isPresent).thenReturn(true)
        whenever(appWidgetManagerOptional.get()).thenReturn(appWidgetManager)
    }

    @Test
    fun neverQueryDbForWidgets_whenFeatureIsDisabled() =
        testScope.runTest {
            communalEnabled(false)
            val repository = initCommunalWidgetRepository()
            repository.communalWidgets.launchIn(backgroundScope)
            runCurrent()

            verify(communalWidgetDao, never()).getWidgets()
        }

    @Test
    fun neverQueryDbForWidgets_whenFeatureEnabled_andUserLocked() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            repository.communalWidgets.launchIn(backgroundScope)
            runCurrent()

            verify(communalWidgetDao, never()).getWidgets()
        }

    @Test
    fun communalWidgets_whenUserUnlocked_queryWidgetsFromDb() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            val communalWidgets by collectLastValue(repository.communalWidgets)
            runCurrent()
            val communalItemRankEntry = CommunalItemRank(uid = 1L, rank = 1)
            val communalWidgetItemEntry = CommunalWidgetItem(uid = 1L, 1, "pk_name/cls_name", 1L)
            whenever(communalWidgetDao.getWidgets())
                .thenReturn(flowOf(mapOf(communalItemRankEntry to communalWidgetItemEntry)))
            whenever(appWidgetManager.getAppWidgetInfo(anyInt())).thenReturn(providerInfoA)

            userUnlocked(true)
            installedProviders(listOf(stopwatchProviderInfo))
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(ACTION_USER_UNLOCKED)
            )
            runCurrent()

            verify(communalWidgetDao).getWidgets()
            assertThat(communalWidgets)
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
            whenever(communalWidgetHost.requiresConfiguration(id)).thenReturn(true)
            whenever(communalWidgetHost.allocateIdAndBindWidget(any<ComponentName>()))
                .thenReturn(id)
            repository.addWidget(provider, priority) { true }
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao).addWidget(id, provider, priority)
        }

    @Test
    fun addWidget_configurationFails_doNotAddWidgetToDb() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            runCurrent()

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.requiresConfiguration(id)).thenReturn(true)
            whenever(communalWidgetHost.allocateIdAndBindWidget(provider)).thenReturn(id)
            repository.addWidget(provider, priority) { false }
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao, never()).addWidget(id, provider, priority)
            verify(appWidgetHost).deleteAppWidgetId(id)
        }

    @Test
    fun addWidget_configurationThrowsError_doNotAddWidgetToDb() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            runCurrent()

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.requiresConfiguration(id)).thenReturn(true)
            whenever(communalWidgetHost.allocateIdAndBindWidget(provider)).thenReturn(id)
            repository.addWidget(provider, priority) { throw IllegalStateException("some error") }
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao, never()).addWidget(id, provider, priority)
            verify(appWidgetHost).deleteAppWidgetId(id)
        }

    @Test
    fun addWidget_configurationNotRequired_doesNotConfigure_addWidgetToDb() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            runCurrent()

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.requiresConfiguration(id)).thenReturn(false)
            whenever(communalWidgetHost.allocateIdAndBindWidget(any<ComponentName>()))
                .thenReturn(id)
            var configured = false
            repository.addWidget(provider, priority) {
                configured = true
                true
            }
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao).addWidget(id, provider, priority)
            assertThat(configured).isFalse()
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
    fun reorderWidgets_queryDb() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            runCurrent()

            val widgetIdToPriorityMap = mapOf(104 to 1, 103 to 2, 101 to 3)
            repository.updateWidgetOrder(widgetIdToPriorityMap)
            runCurrent()

            verify(communalWidgetDao).updateWidgetOrder(widgetIdToPriorityMap)
        }

    @Test
    fun broadcastReceiver_communalDisabled_doNotRegisterUserUnlockedBroadcastReceiver() =
        testScope.runTest {
            communalEnabled(false)
            val repository = initCommunalWidgetRepository()
            repository.communalWidgets.launchIn(backgroundScope)
            runCurrent()
            assertThat(fakeBroadcastDispatcher.numReceiversRegistered).isEqualTo(0)
        }

    @Test
    fun broadcastReceiver_featureEnabledAndUserLocked_registerBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            repository.communalWidgets.launchIn(backgroundScope)
            runCurrent()
            assertThat(fakeBroadcastDispatcher.numReceiversRegistered).isEqualTo(1)
        }

    @Test
    fun appWidgetHost_userUnlocked_startListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            repository.communalWidgets.launchIn(backgroundScope)
            runCurrent()
            verify(appWidgetHost, never()).startListening()

            userUnlocked(true)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(ACTION_USER_UNLOCKED)
            )
            runCurrent()

            verify(appWidgetHost).startListening()
        }

    @Test
    fun appWidgetHost_userLockedAgain_stopListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            repository.communalWidgets.launchIn(backgroundScope)
            runCurrent()

            userUnlocked(true)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(ACTION_USER_UNLOCKED)
            )
            runCurrent()

            verify(appWidgetHost).startListening()
            verify(appWidgetHost, never()).stopListening()

            userUnlocked(false)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(ACTION_USER_UNLOCKED)
            )
            runCurrent()

            verify(appWidgetHost).stopListening()
        }

    private fun initCommunalWidgetRepository(): CommunalWidgetRepositoryImpl {
        return CommunalWidgetRepositoryImpl(
            appWidgetManagerOptional,
            appWidgetHost,
            testScope.backgroundScope,
            kosmos.testDispatcher,
            fakeBroadcastDispatcher,
            communalRepository,
            communalWidgetHost,
            communalWidgetDao,
            userManager,
            userTracker,
            logBuffer,
        )
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
