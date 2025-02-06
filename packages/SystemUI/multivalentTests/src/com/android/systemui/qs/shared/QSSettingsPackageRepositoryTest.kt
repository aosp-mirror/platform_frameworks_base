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

package com.android.systemui.qs.shared

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSSettingsPackageRepositoryTest : SysuiTestCase() {

    @get:Rule val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var context: Context
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var resolveInfo: ResolveInfo
    @Mock private lateinit var activityInfo: ActivityInfo

    private val kosmos = testKosmos()
    private val scope = kosmos.testScope
    private val userRepository = kosmos.fakeUserRepository

    private lateinit var underTest: QSSettingsPackageRepository

    @Before
    fun setUp() {
        whenever(context.createContextAsUser(any(), anyInt())).thenReturn(context)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(packageManager.queryIntentActivities(any(Intent::class.java), anyInt()))
            .thenReturn(listOf(resolveInfo))
        resolveInfo.activityInfo = activityInfo

        underTest = QSSettingsPackageRepository(context, scope, userRepository)
    }

    @Test
    fun getSettingsPackageName_noInit_returnsDefaultPackageName() {
        assertThat(underTest.getSettingsPackageName()).isEqualTo(DEFAULT_SETTINGS_PACKAGE_NAME)
    }

    @Test
    fun getSettingsPackageName_repositoryWithCustomPackage_returnsCustomPackageName() {
        scope.runTest {
            activityInfo.packageName = CUSTOM_SETTINGS_PACKAGE_NAME

            underTest.init()
            runCurrent()

            assertThat(underTest.getSettingsPackageName()).isEqualTo(CUSTOM_SETTINGS_PACKAGE_NAME)
        }
    }

    @Test
    fun getSettingsPackageName_noMatchingActivity_returnsDefaultPackageName() {
        scope.runTest {
            whenever(packageManager.queryIntentActivities(any(Intent::class.java), anyInt()))
                .thenReturn(emptyList())

            underTest.init()
            runCurrent()

            assertThat(underTest.getSettingsPackageName()).isEqualTo(DEFAULT_SETTINGS_PACKAGE_NAME)
        }
    }

    @Test
    fun getSettingsPackageName_nullActivityInfo_returnsDefaultPackageName() {
        scope.runTest {
            resolveInfo.activityInfo = null

            underTest.init()
            runCurrent()

            assertThat(underTest.getSettingsPackageName()).isEqualTo(DEFAULT_SETTINGS_PACKAGE_NAME)
        }
    }

    companion object {
        private const val DEFAULT_SETTINGS_PACKAGE_NAME = "com.android.settings"
        private const val CUSTOM_SETTINGS_PACKAGE_NAME = "com.android.test.settings"
    }
}
