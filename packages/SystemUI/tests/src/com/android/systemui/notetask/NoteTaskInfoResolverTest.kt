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

package com.android.systemui.notetask

import android.app.role.RoleManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations

/**
 * Tests for [NoteTaskInfoResolver].
 *
 * Build/Install/Run:
 * - atest SystemUITests:NoteTaskInfoResolverTest
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskInfoResolverTest : SysuiTestCase() {

    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var roleManager: RoleManager

    private lateinit var underTest: NoteTaskInfoResolver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest = NoteTaskInfoResolver(roleManager, packageManager)
    }

    @Test
    fun resolveInfo_shouldReturnInfo() {
        val packageName = "com.android.note.app"
        val uid = 123456
        whenever(roleManager.getRoleHoldersAsUser(RoleManager.ROLE_NOTES, context.user)).then {
            listOf(packageName)
        }
        whenever(
                packageManager.getApplicationInfoAsUser(
                    eq(packageName),
                    any<PackageManager.ApplicationInfoFlags>(),
                    eq(context.user)
                )
            )
            .thenReturn(ApplicationInfo().apply { this.uid = uid })

        val actual = underTest.resolveInfo(user = context.user)

        requireNotNull(actual) { "Note task info must not be null" }
        assertThat(actual.packageName).isEqualTo(packageName)
        assertThat(actual.uid).isEqualTo(uid)
        assertThat(actual.user).isEqualTo(context.user)
    }

    @Test
    fun resolveInfo_packageManagerThrowsException_shouldReturnInfoWithZeroUid() {
        val packageName = "com.android.note.app"
        whenever(roleManager.getRoleHoldersAsUser(RoleManager.ROLE_NOTES, context.user)).then {
            listOf(packageName)
        }
        whenever(
                packageManager.getApplicationInfoAsUser(
                    eq(packageName),
                    any<PackageManager.ApplicationInfoFlags>(),
                    eq(context.user)
                )
            )
            .thenThrow(PackageManager.NameNotFoundException(packageName))

        val actual = underTest.resolveInfo(user = context.user)

        requireNotNull(actual) { "Note task info must not be null" }
        assertThat(actual.packageName).isEqualTo(packageName)
        assertThat(actual.uid).isEqualTo(0)
        assertThat(actual.user).isEqualTo(context.user)
    }

    @Test
    fun resolveInfo_noRoleHolderIsSet_shouldReturnNull() {
        whenever(roleManager.getRoleHoldersAsUser(eq(RoleManager.ROLE_NOTES), any())).then {
            emptyList<String>()
        }

        val actual = underTest.resolveInfo(user = context.user)

        assertThat(actual).isNull()
    }
}
