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
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.UserInfo
import android.os.Bundle
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalWidgetHostTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var appWidgetManager: AppWidgetManager
    @Mock private lateinit var appWidgetHost: CommunalAppWidgetHost
    @Mock private lateinit var providerInfo1: AppWidgetProviderInfo
    @Mock private lateinit var providerInfo2: AppWidgetProviderInfo
    @Mock private lateinit var providerInfo3: AppWidgetProviderInfo

    private val selectedUserInteractor: SelectedUserInteractor by lazy {
        kosmos.selectedUserInteractor
    }

    private lateinit var underTest: CommunalWidgetHost

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(
                appWidgetManager.bindAppWidgetIdIfAllowed(
                    any<Int>(),
                    any<UserHandle>(),
                    any<ComponentName>(),
                    any<Bundle>()
                )
            )
            .thenReturn(true)

        underTest =
            CommunalWidgetHost(
                kosmos.applicationCoroutineScope,
                Optional.of(appWidgetManager),
                appWidgetHost,
                selectedUserInteractor,
                logcatLogBuffer("CommunalWidgetHostTest"),
            )
    }

    @Test
    fun allocateIdAndBindWidget_withCurrentUser() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val widgetId = 1
            val userId by collectLastValue(selectedUserInteractor.selectedUser)
            selectUser()
            runCurrent()

            val user = UserHandle(checkNotNull(userId))
            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(widgetId)

            // bind the widget with the current user when no user is explicitly set
            val result = underTest.allocateIdAndBindWidget(provider)

            verify(appWidgetHost).allocateAppWidgetId()
            val bundle =
                withArgCaptor<Bundle> {
                    verify(appWidgetManager)
                        .bindAppWidgetIdIfAllowed(eq(widgetId), eq(user), eq(provider), capture())
                }
            assertThat(result).isEqualTo(widgetId)
            assertThat(bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY))
                .isEqualTo(AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
        }

    @Test
    fun allocateIdAndBindWidget_onSuccess() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val widgetId = 1
            val user = UserHandle(0)

            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(widgetId)

            // provider and user handle are both set
            val result = underTest.allocateIdAndBindWidget(provider, user)

            verify(appWidgetHost).allocateAppWidgetId()
            val bundle =
                withArgCaptor<Bundle> {
                    verify(appWidgetManager)
                        .bindAppWidgetIdIfAllowed(eq(widgetId), eq(user), eq(provider), capture())
                }
            assertThat(result).isEqualTo(widgetId)
            assertThat(bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY))
                .isEqualTo(AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
        }

    @Test
    fun allocateIdAndBindWidget_onFailure() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val widgetId = 1
            val user = UserHandle(0)

            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(widgetId)
            // failed to bind widget
            whenever(
                    appWidgetManager.bindAppWidgetIdIfAllowed(
                        any<Int>(),
                        any<UserHandle>(),
                        any<ComponentName>(),
                        any<Bundle>()
                    )
                )
                .thenReturn(false)
            val result = underTest.allocateIdAndBindWidget(provider, user)

            verify(appWidgetHost).allocateAppWidgetId()
            verify(appWidgetManager)
                .bindAppWidgetIdIfAllowed(eq(widgetId), eq(user), eq(provider), any())
            verify(appWidgetHost).deleteAppWidgetId(widgetId)
            assertThat(result).isNull()
        }

    @Test
    fun listener_exactlyOneListenerRegisteredForEachWidgetWhenHostStartListening() =
        testScope.runTest {
            // 3 widgets registered with the host
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2, 3))

            underTest.startObservingHost()
            runCurrent()

            // Make sure no listener is set before host starts listening
            verify(appWidgetHost, never()).setListener(any(), any())

            // Host starts listening
            val observer =
                withArgCaptor<CommunalAppWidgetHost.Observer> {
                    verify(appWidgetHost).addObserver(capture())
                }
            observer.onHostStartListening()
            runCurrent()

            // Verify a listener is set for each widget
            verify(appWidgetHost, times(3)).setListener(any(), any())
            verify(appWidgetHost).setListener(eq(1), any())
            verify(appWidgetHost).setListener(eq(2), any())
            verify(appWidgetHost).setListener(eq(3), any())
        }

    @Test
    fun listener_listenersRemovedWhenHostStopListening() =
        testScope.runTest {
            // 3 widgets registered with the host
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2, 3))

            underTest.startObservingHost()
            runCurrent()

            // Host starts listening
            val observer =
                withArgCaptor<CommunalAppWidgetHost.Observer> {
                    verify(appWidgetHost).addObserver(capture())
                }
            observer.onHostStartListening()
            runCurrent()

            // Verify none of the listener is removed before host stop listening
            verify(appWidgetHost, never()).removeListener(any())

            observer.onHostStopListening()

            // Verify each listener is removed
            verify(appWidgetHost, times(3)).removeListener(any())
            verify(appWidgetHost).removeListener(eq(1))
            verify(appWidgetHost).removeListener(eq(2))
            verify(appWidgetHost).removeListener(eq(3))
        }

    @Test
    fun listener_addNewListenerWhenNewIdAllocated() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf())
            val observer = start()

            // Verify no listener is set before a new app widget id is allocated
            verify(appWidgetHost, never()).setListener(any(), any())

            // Allocate an app widget id
            observer.onAllocateAppWidgetId(1)

            // Verify new listener set for that app widget id
            verify(appWidgetHost).setListener(eq(1), any())
        }

    @Test
    fun listener_removeListenerWhenWidgetDeleted() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1))
            val observer = start()

            // Verify listener not removed before widget deleted
            verify(appWidgetHost, never()).removeListener(eq(1))

            // Widget deleted
            observer.onDeleteAppWidgetId(1)

            // Verify listener removed for that widget
            verify(appWidgetHost).removeListener(eq(1))
        }

    @Test
    fun providerInfo_populatesWhenStartListening() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2))
            whenever(appWidgetManager.getAppWidgetInfo(1)).thenReturn(providerInfo1)
            whenever(appWidgetManager.getAppWidgetInfo(2)).thenReturn(providerInfo2)

            val providerInfoValues by collectValues(underTest.appWidgetProviders)

            // Assert that the map is empty before host starts listening
            assertThat(providerInfoValues).hasSize(1)
            assertThat(providerInfoValues[0]).isEmpty()

            start()
            runCurrent()

            // Assert that the provider info map is populated after host started listening, and that
            // all providers are emitted at once
            assertThat(providerInfoValues).hasSize(2)
            assertThat(providerInfoValues[1])
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo1),
                        Pair(2, providerInfo2),
                    )
                )
        }

    @Test
    fun providerInfo_clearsWhenStopListening() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2))
            whenever(appWidgetManager.getAppWidgetInfo(1)).thenReturn(providerInfo1)
            whenever(appWidgetManager.getAppWidgetInfo(2)).thenReturn(providerInfo2)

            val observer = start()
            runCurrent()

            // Assert that the provider info map is populated
            val providerInfo by collectLastValue(underTest.appWidgetProviders)
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo1),
                        Pair(2, providerInfo2),
                    )
                )

            // Host stop listening
            observer.onHostStopListening()

            // Assert that the provider info map is cleared
            assertThat(providerInfo).isEmpty()
        }

    @Test
    fun providerInfo_onUpdate() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2))
            whenever(appWidgetManager.getAppWidgetInfo(1)).thenReturn(providerInfo1)
            whenever(appWidgetManager.getAppWidgetInfo(2)).thenReturn(providerInfo2)

            val providerInfo by collectLastValue(underTest.appWidgetProviders)

            start()
            runCurrent()

            // Assert that the provider info map is populated
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo1),
                        Pair(2, providerInfo2),
                    )
                )

            // Provider info for widget 1 updated
            val listener =
                withArgCaptor<AppWidgetHost.AppWidgetHostListener> {
                    verify(appWidgetHost).setListener(eq(1), capture())
                }
            listener.onUpdateProviderInfo(providerInfo3)
            runCurrent()

            // Assert that the update is reflected in the flow
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo3),
                        Pair(2, providerInfo2),
                    )
                )
        }

    @Test
    fun providerInfo_updateWhenANewWidgetIsBound() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2))
            whenever(appWidgetManager.getAppWidgetInfo(1)).thenReturn(providerInfo1)
            whenever(appWidgetManager.getAppWidgetInfo(2)).thenReturn(providerInfo2)

            val providerInfo by collectLastValue(underTest.appWidgetProviders)

            start()
            runCurrent()

            // Assert that the provider info map is populated
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo1),
                        Pair(2, providerInfo2),
                    )
                )

            // Bind a new widget
            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(3)
            whenever(appWidgetManager.getAppWidgetInfo(3)).thenReturn(providerInfo3)
            val newWidgetComponentName = ComponentName.unflattenFromString("pkg_new/cls_new")!!
            underTest.allocateIdAndBindWidget(newWidgetComponentName)
            runCurrent()

            // Assert that the new provider is reflected in the flow
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo1),
                        Pair(2, providerInfo2),
                        Pair(3, providerInfo3),
                    )
                )
        }

    @Test
    fun providerInfo_updateWhenWidgetRemoved() =
        testScope.runTest {
            whenever(appWidgetHost.appWidgetIds).thenReturn(intArrayOf(1, 2))
            whenever(appWidgetManager.getAppWidgetInfo(1)).thenReturn(providerInfo1)
            whenever(appWidgetManager.getAppWidgetInfo(2)).thenReturn(providerInfo2)

            val providerInfo by collectLastValue(underTest.appWidgetProviders)

            val observer = start()
            runCurrent()

            // Assert that the provider info map is populated
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, providerInfo1),
                        Pair(2, providerInfo2),
                    )
                )

            // Remove widget 1
            observer.onDeleteAppWidgetId(1)
            runCurrent()

            // Assert that provider info for widget 1 is removed
            assertThat(providerInfo)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(2, providerInfo2),
                    )
                )
        }

    private fun selectUser() {
        kosmos.fakeUserRepository.selectedUser.value =
            SelectedUserModel(
                userInfo = UserInfo(0, "Current user", 0),
                selectionStatus = SelectionStatus.SELECTION_COMPLETE
            )
    }

    private fun TestScope.start(): CommunalAppWidgetHost.Observer {
        underTest.startObservingHost()
        runCurrent()

        val observer =
            withArgCaptor<CommunalAppWidgetHost.Observer> {
                verify(appWidgetHost).addObserver(capture())
            }
        observer.onHostStartListening()
        return observer
    }
}
