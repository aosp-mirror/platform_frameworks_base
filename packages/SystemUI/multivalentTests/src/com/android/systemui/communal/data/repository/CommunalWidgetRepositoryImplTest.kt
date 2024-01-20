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
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.shared.CommunalWidgetHost
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.communal.widgets.widgetConfiguratorFail
import com.android.systemui.communal.widgets.widgetConfiguratorSuccess
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
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

    @Mock private lateinit var stopwatchProviderInfo: AppWidgetProviderInfo

    @Mock private lateinit var providerInfoA: AppWidgetProviderInfo

    @Mock private lateinit var communalWidgetHost: CommunalWidgetHost

    @Mock private lateinit var communalWidgetDao: CommunalWidgetDao

    private lateinit var logBuffer: LogBuffer

    private val kosmos = testKosmos()
    private val testDispatcher = kosmos.testDispatcher
    private val testScope = kosmos.testScope

    private val fakeAllowlist =
        listOf(
            "com.android.fake/WidgetProviderA",
            "com.android.fake/WidgetProviderB",
            "com.android.fake/WidgetProviderC",
        )

    private lateinit var underTest: CommunalWidgetRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        logBuffer = logcatLogBuffer(name = "CommunalWidgetRepoImplTest")

        setAppWidgetIds(emptyList())

        overrideResource(R.array.config_communalWidgetAllowlist, fakeAllowlist.toTypedArray())

        whenever(stopwatchProviderInfo.loadLabel(any())).thenReturn("Stopwatch")
        whenever(communalWidgetDao.getWidgets()).thenReturn(flowOf(emptyMap()))
        whenever(appWidgetManagerOptional.isPresent).thenReturn(true)
        whenever(appWidgetManagerOptional.get()).thenReturn(appWidgetManager)

        underTest =
            CommunalWidgetRepositoryImpl(
                appWidgetManagerOptional,
                appWidgetHost,
                testScope.backgroundScope,
                kosmos.testDispatcher,
                communalWidgetHost,
                communalWidgetDao,
                logBuffer,
            )
    }

    @Test
    fun neverQueryDbForWidgets_whenHostIsInactive() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(false)
            underTest.communalWidgets.launchIn(testScope.backgroundScope)
            runCurrent()

            verify(communalWidgetDao, never()).getWidgets()
        }

    @Test
    fun communalWidgets_whenHostIsActive_queryWidgetsFromDb() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            val communalItemRankEntry = CommunalItemRank(uid = 1L, rank = 1)
            val communalWidgetItemEntry = CommunalWidgetItem(uid = 1L, 1, "pk_name/cls_name", 1L)
            whenever(communalWidgetDao.getWidgets())
                .thenReturn(flowOf(mapOf(communalItemRankEntry to communalWidgetItemEntry)))
            whenever(appWidgetManager.getAppWidgetInfo(anyInt())).thenReturn(providerInfoA)

            installedProviders(listOf(stopwatchProviderInfo))

            val communalWidgets by collectLastValue(underTest.communalWidgets)
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
            underTest.updateAppWidgetHostActive(true)

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_REQUIRES_CONFIGURATION)
            whenever(communalWidgetHost.allocateIdAndBindWidget(any<ComponentName>()))
                .thenReturn(id)
            underTest.addWidget(provider, priority, kosmos.widgetConfiguratorSuccess)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao).addWidget(id, provider, priority)
        }

    @Test
    fun addWidget_configurationFails_doNotAddWidgetToDb() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_REQUIRES_CONFIGURATION)
            whenever(communalWidgetHost.allocateIdAndBindWidget(provider)).thenReturn(id)
            underTest.addWidget(provider, priority, kosmos.widgetConfiguratorFail)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao, never()).addWidget(id, provider, priority)
            verify(appWidgetHost).deleteAppWidgetId(id)
        }

    @Test
    fun addWidget_configurationThrowsError_doNotAddWidgetToDb() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_REQUIRES_CONFIGURATION)
            whenever(communalWidgetHost.allocateIdAndBindWidget(provider)).thenReturn(id)
            underTest.addWidget(
                provider,
                priority,
                object : WidgetConfigurator {
                    override suspend fun configureWidget(appWidgetId: Int): Boolean {
                        throw IllegalStateException("some error")
                    }
                }
            )
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao, never()).addWidget(id, provider, priority)
            verify(appWidgetHost).deleteAppWidgetId(id)
        }

    @Test
    fun addWidget_configurationNotRequired_doesNotConfigure_addWidgetToDb() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val priority = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_CONFIGURATION_OPTIONAL)
            whenever(communalWidgetHost.allocateIdAndBindWidget(any<ComponentName>()))
                .thenReturn(id)
            underTest.addWidget(provider, priority, kosmos.widgetConfiguratorFail)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider)
            verify(communalWidgetDao).addWidget(id, provider, priority)
        }

    @Test
    fun deleteWidget_removeWidgetId_andDeleteFromDb() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            val id = 1
            underTest.deleteWidget(id)
            runCurrent()

            verify(communalWidgetDao).deleteWidgetById(id)
            verify(appWidgetHost).deleteAppWidgetId(id)
        }

    @Test
    fun reorderWidgets_queryDb() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            val widgetIdToPriorityMap = mapOf(104 to 1, 103 to 2, 101 to 3)
            underTest.updateWidgetOrder(widgetIdToPriorityMap)
            runCurrent()

            verify(communalWidgetDao).updateWidgetOrder(widgetIdToPriorityMap)
        }

    @Test
    fun appWidgetHost_startListening() =
        testScope.runTest {
            verify(appWidgetHost, never()).startListening()

            underTest.updateAppWidgetHostActive(true)

            verify(appWidgetHost).startListening()
        }

    @Test
    fun appWidgetHost_stopListening() =
        testScope.runTest {
            underTest.updateAppWidgetHostActive(true)

            verify(appWidgetHost).startListening()

            underTest.updateAppWidgetHostActive(false)

            verify(appWidgetHost).stopListening()
        }

    private fun installedProviders(providers: List<AppWidgetProviderInfo>) {
        whenever(appWidgetManager.installedProviders).thenReturn(providers)
    }

    private fun setAppWidgetIds(ids: List<Int>) {
        whenever(appWidgetHost.appWidgetIds).thenReturn(ids.toIntArray())
    }

    private companion object {
        val PROVIDER_INFO_REQUIRES_CONFIGURATION =
            AppWidgetProviderInfo().apply { configure = ComponentName("test.pkg", "test.cmp") }
        val PROVIDER_INFO_CONFIGURATION_OPTIONAL =
            AppWidgetProviderInfo().apply {
                configure = ComponentName("test.pkg", "test.cmp")
                widgetFeatures =
                    WIDGET_FEATURE_CONFIGURATION_OPTIONAL or WIDGET_FEATURE_RECONFIGURABLE
            }
    }
}
