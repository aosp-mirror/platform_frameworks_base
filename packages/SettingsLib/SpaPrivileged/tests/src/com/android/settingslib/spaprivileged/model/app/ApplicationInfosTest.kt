/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spaprivileged.framework.common.devicePolicyManager
import com.android.settingslib.spaprivileged.framework.common.userManager
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class ApplicationInfosTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Spy
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var userManager: UserManager

    @Mock
    private lateinit var devicePolicyManager: DevicePolicyManager

    @Before
    fun setUp() {
        whenever(context.userManager).thenReturn(userManager)
        whenever(context.devicePolicyManager).thenReturn(devicePolicyManager)
    }

    @Test
    fun userId() {
        val app = ApplicationInfo().apply {
            uid = 123
        }

        val userId = app.userId

        assertThat(userId).isEqualTo(0)
    }

    @Test
    fun userHandle() {
        val app = ApplicationInfo().apply {
            uid = 123
        }

        val userHandle = app.userHandle

        assertThat(userHandle.identifier).isEqualTo(0)
    }

    @Test
    fun hasFlag() {
        val app = ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_INSTALLED
        }

        val hasFlag = app.hasFlag(ApplicationInfo.FLAG_INSTALLED)

        assertThat(hasFlag).isTrue()
    }

    @Test
    fun installed() {
        val app = ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_INSTALLED
        }

        val installed = app.installed

        assertThat(installed).isTrue()
    }

    @Test
    fun isDisabledUntilUsed() {
        val app = ApplicationInfo().apply {
            enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        }

        val isDisabledUntilUsed = app.isDisabledUntilUsed

        assertThat(isDisabledUntilUsed).isTrue()
    }

    @Test
    fun isDisallowControl() {
        val app = ApplicationInfo().apply {
            uid = 123
        }
        whenever(
            userManager.hasBaseUserRestriction(UserManager.DISALLOW_APPS_CONTROL, app.userHandle)
        ).thenReturn(true)

        val isDisallowControl = app.isDisallowControl(context)

        assertThat(isDisallowControl).isTrue()
    }

    @Test
    fun isActiveAdmin() {
        val app = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
            uid = 123
        }
        whenever(devicePolicyManager.packageHasActiveAdmins(PACKAGE_NAME, app.userId))
            .thenReturn(true)

        val isActiveAdmin = app.isActiveAdmin(context)

        assertThat(isActiveAdmin).isTrue()
    }

    @Test
    fun toRoute() {
        val app = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
            uid = 123
        }

        val route = app.toRoute()

        assertThat(route).isEqualTo("package.name/0")
    }

    private companion object {
        const val PACKAGE_NAME = "package.name"
    }
}
