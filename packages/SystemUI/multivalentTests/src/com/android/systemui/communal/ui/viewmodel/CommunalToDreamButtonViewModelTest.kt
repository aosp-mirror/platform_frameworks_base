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

package com.android.systemui.communal.ui.viewmodel

import android.content.pm.UserInfo
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.service.dream.dreamManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalPrefsRepository
import com.android.systemui.communal.domain.interactor.HubOnboardingInteractorTest.Companion.MAIN_USER
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.plugins.activityStarter
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.statusbar.policy.batteryController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@EnableFlags(FLAG_GLANCEABLE_HUB_V2)
@RunWith(AndroidJUnit4::class)
class CommunalToDreamButtonViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest: CommunalToDreamButtonViewModel by lazy {
        kosmos.communalToDreamButtonViewModel
    }

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        underTest.activateIn(testScope)
    }

    @Test
    fun shouldShowDreamButtonOnHub_trueWhenPluggedIn() =
        with(kosmos) {
            runTest {
                whenever(batteryController.isPluggedIn()).thenReturn(true)
                runCurrent()

                assertThat(underTest.shouldShowDreamButtonOnHub).isTrue()
            }
        }

    @Test
    fun shouldShowDreamButtonOnHub_falseWhenNotPluggedIn() =
        with(kosmos) {
            runTest {
                whenever(batteryController.isPluggedIn()).thenReturn(false)
                runCurrent()

                assertThat(underTest.shouldShowDreamButtonOnHub).isFalse()
            }
        }

    @Test
    fun onShowDreamButtonTap_dreamsEnabled_startsDream() =
        with(kosmos) {
            runTest {
                val currentUser = fakeUserRepository.asMainUser()
                kosmos.fakeSettings.putIntForUser(
                    Settings.Secure.SCREENSAVER_ENABLED,
                    1,
                    currentUser.id,
                )
                runCurrent()

                underTest.onShowDreamButtonTap()
                runCurrent()

                verify(dreamManager).startDream()
            }
        }

    @Test
    fun onShowDreamButtonTap_dreamsDisabled_startsActivity() =
        with(kosmos) {
            runTest {
                val currentUser = fakeUserRepository.asMainUser()
                kosmos.fakeSettings.putIntForUser(
                    Settings.Secure.SCREENSAVER_ENABLED,
                    0,
                    currentUser.id,
                )
                runCurrent()

                underTest.onShowDreamButtonTap()
                runCurrent()

                verify(activityStarter).postStartActivityDismissingKeyguard(any(), anyInt())
            }
        }

    @Test
    fun shouldShowDreamButtonTooltip_trueWhenNotDismissedAndHubOnboardingDismissed() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            fakeCommunalPrefsRepository.setHubOnboardingDismissed(MAIN_USER)
            runCurrent()

            assertThat(underTest.shouldShowTooltip).isTrue()
        }

    @Test
    fun shouldShowDreamButtonTooltip_falseWhenNotDismissedAndHubOnboardingNotDismissed() =
        kosmos.runTest {
            runCurrent()
            assertThat(underTest.shouldShowTooltip).isFalse()
        }

    @Test
    fun shouldShowDreamButtonTooltip_falseWhenDismissed() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)
            fakeCommunalPrefsRepository.setDreamButtonTooltipDismissed(MAIN_USER)
            runCurrent()

            assertThat(underTest.shouldShowTooltip).isFalse()
        }

    @Test
    fun onShowDreamButtonTap_eventLogged() =
        with(kosmos) {
            runTest {
                underTest.onShowDreamButtonTap()
                runCurrent()

                assertThat(uiEventLoggerFake[0].eventId)
                    .isEqualTo(CommunalUiEvent.COMMUNAL_HUB_SHOW_DREAM_BUTTON_TAP.id)
            }
        }

    private suspend fun setSelectedUser(user: UserInfo) {
        with(kosmos.fakeUserRepository) {
            setUserInfos(listOf(user))
            setSelectedUserInfo(user)
        }
        kosmos.fakeUserTracker.set(userInfos = listOf(user), selectedUserIndex = 0)
    }
}
