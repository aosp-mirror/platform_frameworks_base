/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.content.pm.UserInfo.FLAG_MAIN
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.fakeCommunalPrefsRepository
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnableFlags(FLAG_GLANCEABLE_HUB_V2)
@RunWith(AndroidJUnit4::class)
class HubOnboardingViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val underTest: HubOnboardingViewModel by lazy { kosmos.hubOnboardingViewModel }

    @Before
    fun setUp() {
        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        underTest.activateIn(kosmos.testScope)
    }

    @Test
    fun onDismissed_setsDismissedTrue() =
        kosmos.runTest {
            setSelectedUser(MAIN_USER)

            val isHubOnboardingDismissed by
                collectLastValue(fakeCommunalPrefsRepository.isHubOnboardingDismissed(MAIN_USER))

            underTest.onDismissed()

            assertThat(isHubOnboardingDismissed).isTrue()
        }

    private suspend fun setSelectedUser(user: UserInfo) {
        with(kosmos.fakeUserRepository) {
            setUserInfos(listOf(user))
            setSelectedUserInfo(user)
        }
        kosmos.fakeUserTracker.set(userInfos = listOf(user), selectedUserIndex = 0)
    }

    companion object {
        val MAIN_USER = UserInfo(0, "main", FLAG_MAIN)
    }
}
