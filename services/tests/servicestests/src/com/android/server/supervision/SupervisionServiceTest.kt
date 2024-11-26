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
 * limitations under the License
 */

package com.android.server.supervision

import android.app.admin.DevicePolicyManagerInternal
import android.content.ComponentName
import android.content.Context
import android.content.pm.UserInfo
import android.os.PersistableBundle
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.internal.R
import com.android.server.LocalServices
import com.android.server.SystemService.TargetUser
import com.android.server.pm.UserManagerInternal
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

/**
 * Unit tests for [SupervisionService].
 *
 * Run with `atest SupervisionServiceTest`.
 */
@RunWith(AndroidJUnit4::class)
class SupervisionServiceTest {
    companion object {
        const val USER_ID = 100
    }

    @get:Rule val mocks: MockitoRule = MockitoJUnit.rule()
    @get:Rule val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Mock private lateinit var mockDpmInternal: DevicePolicyManagerInternal
    @Mock private lateinit var mockUserManagerInternal: UserManagerInternal

    private lateinit var context: Context
    private lateinit var service: SupervisionService

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context

        LocalServices.removeServiceForTest(DevicePolicyManagerInternal::class.java)
        LocalServices.addService(DevicePolicyManagerInternal::class.java, mockDpmInternal)

        LocalServices.removeServiceForTest(UserManagerInternal::class.java)
        LocalServices.addService(UserManagerInternal::class.java, mockUserManagerInternal)

        service = SupervisionService(context)
    }

    @Test
    @RequiresFlagsEnabled(android.app.admin.flags.Flags.FLAG_ENABLE_SUPERVISION_SERVICE_SYNC)
    fun syncStateWithDevicePolicyManager_supervisionAppIsProfileOwner_enablesSupervision() {
        val supervisionPackageName =
            context.getResources().getString(R.string.config_systemSupervision)

        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName(supervisionPackageName, "MainActivity"))

        service.syncStateWithDevicePolicyManager(newTargetUser(USER_ID))

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()
    }

    @Test
    @RequiresFlagsEnabled(android.app.admin.flags.Flags.FLAG_ENABLE_SUPERVISION_SERVICE_SYNC)
    fun syncStateWithDevicePolicyManager_userPreCreated_doesNotEnableSupervision() {
        val supervisionPackageName =
            context.getResources().getString(R.string.config_systemSupervision)

        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName(supervisionPackageName, "MainActivity"))

        service.syncStateWithDevicePolicyManager(newTargetUser(USER_ID, preCreated = true))

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    @RequiresFlagsEnabled(android.app.admin.flags.Flags.FLAG_ENABLE_SUPERVISION_SERVICE_SYNC)
    fun syncStateWithDevicePolicyManager_supervisionAppIsNotProfileOwner_doesNotEnableSupervision() {
        whenever(mockDpmInternal.getProfileOwnerAsUser(USER_ID))
            .thenReturn(ComponentName("other.package", "MainActivity"))

        service.syncStateWithDevicePolicyManager(newTargetUser(USER_ID))

        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun setSupervisionEnabledForUser() {
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()

        service.setSupervisionEnabledForUser(USER_ID, true)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()

        service.setSupervisionEnabledForUser(USER_ID, false)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun supervisionEnabledForUser_internal() {
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()

        service.mInternal.setSupervisionEnabledForUser(USER_ID, true)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()

        service.mInternal.setSupervisionEnabledForUser(USER_ID, false)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun setSupervisionLockscreenEnabledForUser() {
        var userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isFalse()
        assertThat(userData.supervisionLockScreenOptions).isNull()

        service.mInternal.setSupervisionLockscreenEnabledForUser(USER_ID, true, PersistableBundle())
        userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isTrue()
        assertThat(userData.supervisionLockScreenOptions).isNotNull()

        service.mInternal.setSupervisionLockscreenEnabledForUser(USER_ID, false, null)
        userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isFalse()
        assertThat(userData.supervisionLockScreenOptions).isNull()
    }

    private fun newTargetUser(userId: Int, preCreated: Boolean = false): TargetUser {
        val userInfo = UserInfo(userId, /* name= */ "tempUser", /* flags= */ 0)
        userInfo.preCreated = preCreated
        return TargetUser(userInfo)
    }
}
