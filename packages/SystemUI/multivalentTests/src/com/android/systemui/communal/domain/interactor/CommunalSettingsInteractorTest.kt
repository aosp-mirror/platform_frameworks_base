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

import android.app.admin.DevicePolicyManager
import android.app.admin.devicePolicyManager
import android.content.Intent
import android.content.pm.UserInfo
import android.os.UserManager
import android.os.userManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalSettingsInteractorTest : SysuiTestCase() {

    private lateinit var userManager: UserManager
    private lateinit var userRepository: FakeUserRepository
    private lateinit var userTracker: FakeUserTracker

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: CommunalSettingsInteractor

    @Before
    fun setUp() {
        userManager = kosmos.userManager
        userRepository = kosmos.fakeUserRepository
        userTracker = kosmos.fakeUserTracker

        val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
        userRepository.setUserInfos(userInfos)
        userTracker.set(
            userInfos = userInfos,
            selectedUserIndex = 0,
        )

        underTest = kosmos.communalSettingsInteractor
    }

    @Test
    fun filterUsers_dontFilteredUsersWhenAllAreAllowed() =
        testScope.runTest {
            // If no users have any keyguard features disabled...
            val disallowedUser by
                collectLastValue(underTest.workProfileUserDisallowedByDevicePolicy)
            // ...then the disallowed user should be null
            assertNull(disallowedUser)
        }

    @Test
    fun filterUsers_filterWorkProfileUserWhenDisallowed() =
        testScope.runTest {
            // If the work profile user has keyguard widgets disabled...
            setKeyguardFeaturesDisabled(
                USER_INFO_WORK,
                DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL
            )
            // ...then the disallowed user match the work profile
            val disallowedUser by
                collectLastValue(underTest.workProfileUserDisallowedByDevicePolicy)
            assertNotNull(disallowedUser)
            assertEquals(USER_INFO_WORK.id, disallowedUser!!.id)
        }

    private fun setKeyguardFeaturesDisabled(user: UserInfo, disabledFlags: Int) {
        whenever(
                kosmos.devicePolicyManager.getKeyguardDisabledFeatures(
                    anyOrNull(),
                    ArgumentMatchers.eq(user.id)
                )
            )
            .thenReturn(disabledFlags)
        kosmos.broadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
        )
    }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        val USER_INFO_WORK =
            UserInfo(
                10,
                "work",
                /* iconPath= */ "",
                /* flags= */ 0,
                UserManager.USER_TYPE_PROFILE_MANAGED,
            )
    }
}
