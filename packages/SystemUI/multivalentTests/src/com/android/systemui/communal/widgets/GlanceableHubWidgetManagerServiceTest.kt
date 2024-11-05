/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.os.Binder
import android.os.UserHandle
import android.testing.TestableLooper
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.fakeGlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IConfigureWidgetCallback
import com.android.systemui.communal.widgets.IGlanceableHubWidgetManagerService.IGlanceableHubWidgetsListener
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class GlanceableHubWidgetManagerServiceTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val appWidgetHostListenerCaptor = argumentCaptor<AppWidgetHost.AppWidgetHostListener>()

    private val widgetRepository = kosmos.fakeCommunalWidgetRepository
    private val appWidgetHost = mock<CommunalAppWidgetHost>()
    private val communalWidgetHost = mock<CommunalWidgetHost>()
    private val multiUserHelper = kosmos.fakeGlanceableHubMultiUserHelper

    private lateinit var underTest: GlanceableHubWidgetManagerService

    @Before
    fun setup() {
        underTest =
            GlanceableHubWidgetManagerService(
                widgetRepository,
                appWidgetHost,
                communalWidgetHost,
                multiUserHelper,
                logcatLogBuffer("GlanceableHubWidgetManagerServiceTest"),
            )
    }

    @Test
    fun appWidgetHost_listenWhenServiceIsBound() {
        underTest.onCreate()
        verify(appWidgetHost).startListening()
        verify(communalWidgetHost).startObservingHost()
        verify(appWidgetHost, never()).stopListening()
        verify(communalWidgetHost, never()).stopObservingHost()

        underTest.onDestroy()
        verify(appWidgetHost).stopListening()
        verify(communalWidgetHost).stopObservingHost()
    }

    @Test
    fun widgetsListener_getWidgetUpdates() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
        }

    @Test
    fun widgetsListener_multipleListeners_eachGetsWidgetUpdates() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update for the first listener is as expected
            val widgets1 by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets1).hasSize(3)
            assertThat(widgets1?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets1?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets1?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Verify the update for the second listener is as expected
            val widgets2 by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets2).hasSize(3)
            assertThat(widgets2?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets2?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets2?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
        }

    @Test
    fun setAppWidgetHostListener_getUpdates() =
        testScope.runTest {
            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Set listener
            val listener = mock<IGlanceableHubWidgetManagerService.IAppWidgetHostListener>()
            service.setAppWidgetHostListener(1, listener)

            // Verify a listener is set on the host
            verify(appWidgetHost).setListener(eq(1), appWidgetHostListenerCaptor.capture())
            val appWidgetHostListener = appWidgetHostListenerCaptor.firstValue

            // Each update should be passed to the listener
            val providerInfo = mock<AppWidgetProviderInfo>()
            appWidgetHostListener.onUpdateProviderInfo(providerInfo)
            verify(listener).onUpdateProviderInfo(providerInfo)

            val remoteViews = mock<RemoteViews>()
            appWidgetHostListener.updateAppWidget(remoteViews)
            verify(listener).updateAppWidget(remoteViews)

            appWidgetHostListener.updateAppWidgetDeferred("pkg", 1)
            verify(listener).updateAppWidgetDeferred("pkg", 1)

            appWidgetHostListener.onViewDataChanged(1)
            verify(listener).onViewDataChanged(1)
        }

    @Test
    fun addWidget_noConfigurationCallback_getWidgetUpdate() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Add a widget
            service.addWidget(ComponentName("pkg_4", "cls_4"), UserHandle.of(0), 3, null)
            runCurrent()

            // Verify an update pushed with widget 4 added
            assertThat(widgets).hasSize(4)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
            assertThat(widgets?.get(3)?.has(4, "pkg_4/cls_4", 3, 3)).isTrue()
        }

    @Test
    fun addWidget_withConfigurationCallback_configurationFails_doNotAddWidget() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Add a widget with a configuration callback that fails
            service.addWidget(
                ComponentName("pkg_4", "cls_4"),
                UserHandle.of(0),
                3,
                createConfigureWidgetCallback(success = false),
            )
            runCurrent()

            // Verify that widget 4 is not added
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
        }

    @Test
    fun addWidget_withConfigurationCallback_configurationSucceeds_addWidget() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Add a widget with a configuration callback that fails
            service.addWidget(
                ComponentName("pkg_4", "cls_4"),
                UserHandle.of(0),
                3,
                createConfigureWidgetCallback(success = true),
            )
            runCurrent()

            // Verify that widget 4 is added
            assertThat(widgets).hasSize(4)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
            assertThat(widgets?.get(3)?.has(4, "pkg_4/cls_4", 3, 3)).isTrue()
        }

    @Test
    fun deleteWidget_getWidgetUpdate() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Delete a widget
            service.deleteWidget(1)
            runCurrent()

            // Verify an update pushed with widget 1 removed
            assertThat(widgets).hasSize(2)
            assertThat(widgets?.get(0)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
        }

    @Test
    fun updateWidgetOrder_getWidgetUpdate() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Update widget order
            service.updateWidgetOrder(intArrayOf(1, 2, 3), intArrayOf(2, 1, 0))
            runCurrent()

            // Verify an update pushed with the new order
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(3, "pkg_3/cls_3", 0, 6)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(1, "pkg_1/cls_1", 2, 3)).isTrue()
        }

    @Test
    fun resizeWidget_getWidgetUpdate() =
        testScope.runTest {
            setupWidgets()

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            // Verify the update is as expected
            val widgets by collectLastValue(service.listenForWidgetUpdates())
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 3)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()

            // Resize widget 1 from spanY 3 to 6
            service.resizeWidget(1, 6, intArrayOf(1, 2, 3), intArrayOf(0, 1, 2))
            runCurrent()

            // Verify an update pushed with the new size for widget 1
            assertThat(widgets).hasSize(3)
            assertThat(widgets?.get(0)?.has(1, "pkg_1/cls_1", 0, 6)).isTrue()
            assertThat(widgets?.get(1)?.has(2, "pkg_2/cls_2", 1, 3)).isTrue()
            assertThat(widgets?.get(2)?.has(3, "pkg_3/cls_3", 2, 6)).isTrue()
        }

    @Test
    fun getIntentSenderForConfigureActivity() =
        testScope.runTest {
            val expected = IntentSender(Binder())
            whenever(appWidgetHost.getIntentSenderForConfigureActivity(anyInt(), anyInt()))
                .thenReturn(expected)

            // Bind service
            val binder = underTest.onBind(Intent())
            val service = IGlanceableHubWidgetManagerService.Stub.asInterface(binder)

            val actual = service.getIntentSenderForConfigureActivity(1)
            assertThat(actual).isEqualTo(expected)
        }

    private fun setupWidgets() {
        widgetRepository.addWidget(
            appWidgetId = 1,
            componentName = "pkg_1/cls_1",
            rank = 0,
            spanY = 3,
        )
        widgetRepository.addWidget(
            appWidgetId = 2,
            componentName = "pkg_2/cls_2",
            rank = 1,
            spanY = 3,
        )
        widgetRepository.addWidget(
            appWidgetId = 3,
            componentName = "pkg_3/cls_3",
            rank = 2,
            spanY = 6,
        )
    }

    private fun IGlanceableHubWidgetManagerService.listenForWidgetUpdates() =
        conflatedCallbackFlow {
            val listener =
                object : IGlanceableHubWidgetsListener.Stub() {
                    override fun onWidgetsUpdated(widgets: List<CommunalWidgetContentModel>) {
                        trySend(widgets)
                    }
                }
            addWidgetsListener(listener)
            awaitClose { removeWidgetsListener(listener) }
        }

    private fun CommunalWidgetContentModel.has(
        appWidgetId: Int,
        componentName: String,
        rank: Int,
        spanY: Int,
    ): Boolean {
        return this is CommunalWidgetContentModel.Available &&
            this.appWidgetId == appWidgetId &&
            this.providerInfo.provider.flattenToString() == componentName &&
            this.rank == rank &&
            this.spanY == spanY
    }

    private fun createConfigureWidgetCallback(success: Boolean): IConfigureWidgetCallback {
        return object : IConfigureWidgetCallback.Stub() {
            override fun onConfigureWidget(
                appWidgetId: Int,
                resultReceiver: IConfigureWidgetCallback.IResultReceiver?,
            ) {
                resultReceiver?.onResult(success)
            }
        }
    }
}
