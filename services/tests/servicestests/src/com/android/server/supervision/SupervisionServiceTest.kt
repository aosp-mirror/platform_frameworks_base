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

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.server.LocalServices
import com.android.server.pm.UserManagerInternal
import com.google.common.truth.Truth.assertThat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

/**
 * Unit tests for {@link SupervisionService}.
 * <p/>
 * Run with <code>atest SupervisionServiceTest</code>.
 */
@RunWith(AndroidJUnit4::class)
class SupervisionServiceTest {
    companion object {
        const val USER_ID = 100
    }

    private lateinit var service: SupervisionService

    @Rule
    @JvmField
    val mocks: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockUserManagerInternal: UserManagerInternal

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().context

        LocalServices.removeServiceForTest(UserManagerInternal::class.java)
        LocalServices.addService(UserManagerInternal::class.java, mockUserManagerInternal)

        service = SupervisionService(context)
    }

    @Test
    fun testSetSupervisionEnabledForUser() {
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()

        service.setSupervisionEnabledForUser(USER_ID, true)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isTrue()

        service.setSupervisionEnabledForUser(USER_ID, false)
        assertThat(service.isSupervisionEnabledForUser(USER_ID)).isFalse()
    }

    @Test
    fun testSetSupervisionLockscreenEnabledForUser() {
        var userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isFalse()
        assertThat(userData.supervisionLockScreenOptions).isNull()

        service.mInternal.setSupervisionLockscreenEnabledForUser(USER_ID, true, Bundle())
        userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isTrue()
        assertThat(userData.supervisionLockScreenOptions).isNotNull()

        service.mInternal.setSupervisionLockscreenEnabledForUser(USER_ID, false, null)
        userData = service.getUserDataLocked(USER_ID)
        assertThat(userData.supervisionLockScreenEnabled).isFalse()
        assertThat(userData.supervisionLockScreenOptions).isNull()
    }
}
