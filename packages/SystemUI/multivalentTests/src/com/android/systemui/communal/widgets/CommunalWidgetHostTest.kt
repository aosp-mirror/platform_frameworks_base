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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
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
    private val selectedUserInteractor: SelectedUserInteractor by lazy {
        kosmos.selectedUserInteractor
    }

    private lateinit var underTest: CommunalWidgetHost

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            CommunalWidgetHost(
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
            whenever(
                    appWidgetManager.bindAppWidgetIdIfAllowed(
                        any<Int>(),
                        any<UserHandle>(),
                        any<ComponentName>(),
                        any<Bundle>(),
                    )
                )
                .thenReturn(true)

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
            whenever(
                    appWidgetManager.bindAppWidgetIdIfAllowed(
                        any<Int>(),
                        any<UserHandle>(),
                        any<ComponentName>(),
                        any<Bundle>()
                    )
                )
                .thenReturn(true)

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

    private fun selectUser() {
        kosmos.fakeUserRepository.selectedUser.value =
            SelectedUserModel(
                userInfo = UserInfo(0, "Current user", 0),
                selectionStatus = SelectionStatus.SELECTION_COMPLETE
            )
    }
}
