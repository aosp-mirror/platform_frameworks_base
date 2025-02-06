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

package com.android.systemui.communal.domain.interactor

import android.content.pm.UserInfo
import android.content.pm.UserInfo.FLAG_MAIN
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalPrefsInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest by lazy { kosmos.communalPrefsInteractor }

    @Test
    fun setCtaDismissed_currentUser() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)

            assertThat(isCtaDismissed).isFalse()
            underTest.setCtaDismissed(MAIN_USER)
            assertThat(isCtaDismissed).isTrue()
        }

    @Test
    fun setCtaDismissed_anotherUser() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)

            assertThat(isCtaDismissed).isFalse()
            underTest.setCtaDismissed(SECONDARY_USER)
            assertThat(isCtaDismissed).isFalse()
        }

    @Test
    fun isCtaDismissed_userSwitch() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            underTest.setCtaDismissed(MAIN_USER)
            val isCtaDismissed by collectLastValue(underTest.isCtaDismissed)

            assertThat(isCtaDismissed).isTrue()
            setSelectedUser(SECONDARY_USER)
            assertThat(isCtaDismissed).isFalse()
        }

    @Test
    fun setHubOnboardingDismissed_currentUser() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            val isHubOnboardingDismissed by collectLastValue(underTest.isHubOnboardingDismissed)

            assertThat(isHubOnboardingDismissed).isFalse()
            underTest.setHubOnboardingDismissed(MAIN_USER)
            assertThat(isHubOnboardingDismissed).isTrue()
        }

    @Test
    fun setHubOnboardingDismissed_anotherUser() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            val isHubOnboardingDismissed by collectLastValue(underTest.isHubOnboardingDismissed)

            assertThat(isHubOnboardingDismissed).isFalse()
            underTest.setHubOnboardingDismissed(SECONDARY_USER)
            assertThat(isHubOnboardingDismissed).isFalse()
        }

    @Test
    fun isHubOnboardingDismissed_userSwitch() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            underTest.setHubOnboardingDismissed(MAIN_USER)
            val isHubOnboardingDismissed by collectLastValue(underTest.isHubOnboardingDismissed)

            assertThat(isHubOnboardingDismissed).isTrue()
            setSelectedUser(SECONDARY_USER)
            assertThat(isHubOnboardingDismissed).isFalse()
        }

    @Test
    fun setDreamButtonTooltipDismissed_currentUser() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            val isDreamButtonTooltipDismissed by
                collectLastValue(underTest.isDreamButtonTooltipDismissed)

            assertThat(isDreamButtonTooltipDismissed).isFalse()
            underTest.setDreamButtonTooltipDismissed(MAIN_USER)
            assertThat(isDreamButtonTooltipDismissed).isTrue()
        }

    @Test
    fun setDreamButtonTooltipDismissed_anotherUser() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            val isDreamButtonTooltipDismissed by
                collectLastValue(underTest.isDreamButtonTooltipDismissed)

            assertThat(isDreamButtonTooltipDismissed).isFalse()
            underTest.setDreamButtonTooltipDismissed(SECONDARY_USER)
            assertThat(isDreamButtonTooltipDismissed).isFalse()
        }

    @Test
    fun isDreamButtonTooltipDismissed_userSwitch() =
        testScope.runTest {
            setSelectedUser(MAIN_USER)
            underTest.setDreamButtonTooltipDismissed(MAIN_USER)
            val isDreamButtonTooltipDismissed by
                collectLastValue(underTest.isDreamButtonTooltipDismissed)

            assertThat(isDreamButtonTooltipDismissed).isTrue()
            setSelectedUser(SECONDARY_USER)
            assertThat(isDreamButtonTooltipDismissed).isFalse()
        }

    private suspend fun setSelectedUser(user: UserInfo) {
        with(kosmos.fakeUserRepository) {
            setUserInfos(listOf(user))
            setSelectedUserInfo(user)
        }
        kosmos.fakeUserTracker.set(userInfos = listOf(user), selectedUserIndex = 0)
    }

    private companion object {
        val MAIN_USER = UserInfo(0, "main", FLAG_MAIN)
        val SECONDARY_USER = UserInfo(1, "secondary", 0)
    }
}
