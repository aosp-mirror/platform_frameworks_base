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

import android.content.pm.UserInfo
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.shared.model.FakeGlanceableHubMultiUserHelper
import com.android.systemui.communal.shared.model.fakeGlanceableHubMultiUserHelper
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalAppWidgetHostStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @Mock private lateinit var appWidgetHost: CommunalAppWidgetHost
    @Mock private lateinit var communalWidgetHost: CommunalWidgetHost

    private lateinit var widgetManager: GlanceableHubWidgetManager
    private lateinit var helper: FakeGlanceableHubMultiUserHelper

    private lateinit var appWidgetIdToRemove: MutableSharedFlow<Int>

    private lateinit var underTest: CommunalAppWidgetHostStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        kosmos.fakeUserRepository.setUserInfos(listOf(MAIN_USER_INFO, USER_INFO_WORK))
        kosmos.fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)
        mSetFlagsRule.enableFlags(FLAG_COMMUNAL_HUB)

        widgetManager = kosmos.mockGlanceableHubWidgetManager
        helper = kosmos.fakeGlanceableHubMultiUserHelper
        appWidgetIdToRemove = MutableSharedFlow()
        whenever(appWidgetHost.appWidgetIdToRemove).thenReturn(appWidgetIdToRemove)

        underTest =
            CommunalAppWidgetHostStartable(
                { appWidgetHost },
                { communalWidgetHost },
                { kosmos.communalInteractor },
                { kosmos.communalSettingsInteractor },
                { kosmos.keyguardInteractor },
                { kosmos.fakeUserTracker },
                kosmos.applicationCoroutineScope,
                kosmos.testDispatcher,
                { widgetManager },
                helper,
            )
    }

    @Test
    fun editModeShowingStartsAppWidgetHost() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(false)
                communalInteractor.setEditModeOpen(true)
                verify(appWidgetHost, never()).startListening()

                underTest.start()
                runCurrent()

                verify(appWidgetHost).startListening()
                verify(appWidgetHost, never()).stopListening()

                communalInteractor.setEditModeOpen(false)
                runCurrent()

                verify(appWidgetHost).stopListening()
            }
        }

    @Test
    fun communalShowingStartsAppWidgetHost() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(true)
                communalInteractor.setEditModeOpen(false)
                verify(appWidgetHost, never()).startListening()

                underTest.start()
                runCurrent()

                verify(appWidgetHost).startListening()
                verify(appWidgetHost, never()).stopListening()

                setCommunalAvailable(false)
                runCurrent()

                verify(appWidgetHost).stopListening()
            }
        }

    @Test
    fun communalAndEditModeNotShowingNeverStartListening() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(false)
                communalInteractor.setEditModeOpen(false)

                underTest.start()
                runCurrent()

                verify(appWidgetHost, never()).startListening()
                verify(appWidgetHost, never()).stopListening()
            }
        }

    @Test
    fun observeHostWhenCommunalIsAvailable() =
        with(kosmos) {
            testScope.runTest {
                setCommunalAvailable(true)
                communalInteractor.setEditModeOpen(false)
                verify(communalWidgetHost, never()).startObservingHost()
                verify(communalWidgetHost, never()).stopObservingHost()

                underTest.start()
                runCurrent()

                verify(communalWidgetHost).startObservingHost()
                verify(communalWidgetHost, never()).stopObservingHost()

                setCommunalAvailable(false)
                runCurrent()

                verify(communalWidgetHost).stopObservingHost()
            }
        }

    @Test
    fun removeAppWidgetReportedByHost() =
        with(kosmos) {
            testScope.runTest {
                // Set up communal widgets
                fakeCommunalWidgetRepository.addWidget(appWidgetId = 1)
                fakeCommunalWidgetRepository.addWidget(appWidgetId = 2)
                fakeCommunalWidgetRepository.addWidget(appWidgetId = 3)

                underTest.start()

                // Assert communal widgets has 3
                val communalWidgets by
                    collectLastValue(fakeCommunalWidgetRepository.communalWidgets)
                assertThat(communalWidgets).hasSize(3)

                val widget1 = communalWidgets!![0]
                val widget2 = communalWidgets!![1]
                val widget3 = communalWidgets!![2]
                assertThat(widget1.appWidgetId).isEqualTo(1)
                assertThat(widget2.appWidgetId).isEqualTo(2)
                assertThat(widget3.appWidgetId).isEqualTo(3)

                // Report app widget 1 to remove and assert widget removed
                appWidgetIdToRemove.emit(1)
                runCurrent()
                assertThat(communalWidgets).containsExactly(widget2, widget3)

                // Report app widget 3 to remove and assert widget removed
                appWidgetIdToRemove.emit(3)
                runCurrent()
                assertThat(communalWidgets).containsExactly(widget2)
            }
        }

    @Test
    fun removeWidgetsForDeletedProfile_whenCommunalIsAvailable() =
        with(kosmos) {
            testScope.runTest {
                // Communal is available and work profile is configured.
                setCommunalAvailable(true)
                kosmos.fakeUserTracker.set(
                    userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK),
                    selectedUserIndex = 0,
                )
                // One work widget, one pending work widget, and one personal widget.
                fakeCommunalWidgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
                fakeCommunalWidgetRepository.addPendingWidget(
                    appWidgetId = 2,
                    userId = USER_INFO_WORK.id,
                )
                fakeCommunalWidgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

                underTest.start()
                runCurrent()

                val communalWidgets by
                    collectLastValue(fakeCommunalWidgetRepository.communalWidgets)
                assertThat(communalWidgets).hasSize(3)

                val widget1 = communalWidgets!![0]
                val widget2 = communalWidgets!![1]
                val widget3 = communalWidgets!![2]
                assertThat(widget1.appWidgetId).isEqualTo(1)
                assertThat(widget2.appWidgetId).isEqualTo(2)
                assertThat(widget3.appWidgetId).isEqualTo(3)

                // Unlock the device and remove work profile.
                fakeKeyguardRepository.setKeyguardShowing(false)
                kosmos.fakeUserTracker.set(
                    userInfos = listOf(MAIN_USER_INFO),
                    selectedUserIndex = 0,
                )
                runCurrent()

                // Communal becomes available.
                fakeKeyguardRepository.setKeyguardShowing(true)
                runCurrent()

                // Both work widgets are removed.
                assertThat(communalWidgets).containsExactly(widget3)
            }
        }

    @Test
    fun onStartHeadlessSystemUser_registerWidgetManager_whenCommunalIsAvailable() =
        with(kosmos) {
            testScope.runTest {
                helper.setIsInHeadlessSystemUser(true)
                underTest.start()
                runCurrent()
                verify(widgetManager, never()).register()
                verify(widgetManager, never()).unregister()

                // Binding to the service does not require keyguard showing
                setCommunalAvailable(true, setKeyguardShowing = false)
                runCurrent()
                verify(widgetManager).register()

                setCommunalAvailable(false)
                runCurrent()
                verify(widgetManager).unregister()
            }
        }

    private suspend fun setCommunalAvailable(
        available: Boolean,
        setKeyguardShowing: Boolean = true,
    ) =
        with(kosmos) {
            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(MAIN_USER_INFO)
            if (setKeyguardShowing) {
                fakeKeyguardRepository.setKeyguardShowing(true)
            }
            val settingsValue = if (available) 1 else 0
            fakeSettings.putIntForUser(
                Settings.Secure.GLANCEABLE_HUB_ENABLED,
                settingsValue,
                MAIN_USER_INFO.id,
            )
        }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        val USER_INFO_WORK = UserInfo(10, "work", UserInfo.FLAG_PROFILE)
    }
}
