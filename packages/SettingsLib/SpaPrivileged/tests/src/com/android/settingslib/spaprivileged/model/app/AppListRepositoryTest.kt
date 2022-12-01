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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.ResolveInfoFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.Mockito.`when` as whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AppListRepositoryTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var packageManager: PackageManager

    private lateinit var repository: AppListRepository

    private val normalApp = ApplicationInfo().apply {
        packageName = "normal"
        enabled = true
    }

    private val instantApp = ApplicationInfo().apply {
        packageName = "instant"
        enabled = true
        privateFlags = ApplicationInfo.PRIVATE_FLAG_INSTANT
    }

    @Before
    fun setUp() {
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.getInstalledModules(anyInt())).thenReturn(emptyList())
        whenever(
            packageManager.getInstalledApplicationsAsUser(any<ApplicationInfoFlags>(), eq(USER_ID))
        ).thenReturn(listOf(normalApp, instantApp))
        whenever(
            packageManager.queryIntentActivitiesAsUser(any(), any<ResolveInfoFlags>(), eq(USER_ID))
        ).thenReturn(emptyList())

        repository = AppListRepositoryImpl(context)
    }

    @Test
    fun notShowInstantApps() = runTest {
        val appListConfig = AppListConfig(userId = USER_ID, showInstantApps = false)

        val appListFlow = repository.loadApps(appListConfig)

        assertThat(appListFlow).containsExactly(normalApp)
    }

    @Test
    fun showInstantApps() = runTest {
        val appListConfig = AppListConfig(userId = USER_ID, showInstantApps = true)

        val appListFlow = repository.loadApps(appListConfig)

        assertThat(appListFlow).containsExactly(normalApp, instantApp)
    }

    private companion object {
        const val USER_ID = 0
    }
}
