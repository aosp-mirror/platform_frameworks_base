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

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ModuleInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class PackageManagerExtTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var packageManager: PackageManager

    private fun mockResolveActivityAsUser(resolveInfo: ResolveInfo?) {
        whenever(
            packageManager.resolveActivityAsUser(any(), any<ResolveInfoFlags>(), eq(APP.userId))
        ).thenReturn(resolveInfo)
    }

    @Test
    fun isSystemModule_whenSystemModule_returnTrue() {
        whenever(packageManager.getModuleInfo(PACKAGE_NAME, 0)).thenReturn(ModuleInfo())

        val isSystemModule = packageManager.isSystemModule(PACKAGE_NAME)

        assertThat(isSystemModule).isTrue()
    }

    @Test
    fun isSystemModule_whenNotSystemModule_returnFalse() {
        whenever(packageManager.getModuleInfo(PACKAGE_NAME, 0)).thenThrow(NameNotFoundException())

        val isSystemModule = packageManager.isSystemModule(PACKAGE_NAME)

        assertThat(isSystemModule).isFalse()
    }

    @Test
    fun resolveActionForApp_noResolveInfo() {
        mockResolveActivityAsUser(null)

        val activityInfo = packageManager.resolveActionForApp(APP, ACTION)

        assertThat(activityInfo).isNull()
    }

    @Test
    fun resolveActionForApp_noActivityInfo() {
        mockResolveActivityAsUser(ResolveInfo())

        val activityInfo = packageManager.resolveActionForApp(APP, ACTION)

        assertThat(activityInfo).isNull()
    }

    @Test
    fun resolveActionForApp_hasActivityInfo() {
        mockResolveActivityAsUser(ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = PACKAGE_NAME
                name = ACTIVITY_NAME
            }
        })

        val activityInfo = packageManager.resolveActionForApp(APP, ACTION)!!

        assertThat(activityInfo.componentName).isEqualTo(ComponentName(PACKAGE_NAME, ACTIVITY_NAME))
    }

    @Test
    fun resolveActionForApp_withFlags() {
        packageManager.resolveActionForApp(
            app = APP,
            action = ACTION,
            flags = PackageManager.GET_META_DATA,
        )

        argumentCaptor<ResolveInfoFlags> {
            verify(packageManager).resolveActivityAsUser(any(), capture(), eq(APP.userId))
            assertThat(firstValue.value).isEqualTo(PackageManager.GET_META_DATA.toLong())
        }
    }

    private companion object {
        const val PACKAGE_NAME = "package.name"
        const val ACTIVITY_NAME = "ActivityName"
        const val ACTION = "action"
        const val UID = 123
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
            uid = UID
        }
    }
}
