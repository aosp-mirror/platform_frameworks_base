/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm

import android.content.res.Configuration
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.util.ArrayMap
import com.android.server.LockGuard
import com.android.server.extendedtestutils.wheneverStatic
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
class UserManagerServiceDemoModeTest {
    private lateinit var ums: UserManagerService

    @Rule
    @JvmField
    val rule = MockSystemRule()

    @Before
    @Throws(Exception::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        rule.system().stageNominalSystemState()

        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        wheneverStatic { LockGuard.installNewLock(LockGuard.INDEX_USER) }.thenReturn(Object())
        whenever(rule.mocks().systemConfig.getAndClearPackageToUserTypeWhitelist()).thenReturn(ArrayMap<String, Set<String>>())
        whenever(rule.mocks().systemConfig.getAndClearPackageToUserTypeBlacklist()).thenReturn(ArrayMap<String, Set<String>>())
        whenever(rule.mocks().resources.getStringArray(com.android.internal.R.array.config_defaultFirstUserRestrictions)).thenReturn(arrayOf<String>())
        whenever(rule.mocks().resources.configuration).thenReturn(Configuration())

        ums = UserManagerService(rule.mocks().context)
    }

    @Test
    fun isDemoUser_returnsTrue_whenSystemPropertyIsSet() {
        wheneverStatic { SystemProperties.getBoolean("ro.boot.arc_demo_mode", false) }.thenReturn(true)

        assertThat(ums.isDemoUser(0)).isTrue()
    }

    @Test
    fun isDemoUser_returnsFalse_whenSystemPropertyIsSet() {
        wheneverStatic { SystemProperties.getBoolean("ro.boot.arc_demo_mode", false) }.thenReturn(false)

        assertThat(ums.isDemoUser(0)).isFalse()
    }

    @Test
    fun isDemoUser_returnsFalse_whenSystemPropertyIsNotSet() {
        assertThat(ums.isDemoUser(0)).isFalse()
    }
}