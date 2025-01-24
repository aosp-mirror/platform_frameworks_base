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
 * limitations under the License
 */

package com.android.settingslib.supervision

import android.app.supervision.SupervisionManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

/**
 * Unit tests for [SupervisionIntentProvider].
 *
 * Run with `atest SupervisionIntentProviderTest`.
 */
@RunWith(AndroidJUnit4::class)
class SupervisionIntentProviderTest {
    @get:Rule val mocks: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var mockPackageManager: PackageManager

    @Mock private lateinit var mockSupervisionManager: SupervisionManager

    private lateinit var context: Context

    @Before
    fun setUp() {
        context =
            object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
                override fun getPackageManager() = mockPackageManager

                override fun getSystemService(name: String) =
                    when (name) {
                        Context.SUPERVISION_SERVICE -> mockSupervisionManager
                        else -> super.getSystemService(name)
                    }
            }
    }

    @Test
    fun getSettingsIntent_nullSupervisionPackage() {
        `when`(mockSupervisionManager.activeSupervisionAppPackage).thenReturn(null)

        val intent = SupervisionIntentProvider.getSettingsIntent(context)

        assertThat(intent).isNull()
    }

    @Test
    fun getSettingsIntent_unresolvedIntent() {
        `when`(mockSupervisionManager.activeSupervisionAppPackage)
            .thenReturn(SUPERVISION_APP_PACKAGE)
        `when`(mockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(), anyInt()))
            .thenReturn(emptyList())

        val intent = SupervisionIntentProvider.getSettingsIntent(context)

        assertThat(intent).isNull()
    }

    @Test
    fun getSettingsIntent_resolvedIntent() {
        `when`(mockSupervisionManager.activeSupervisionAppPackage)
            .thenReturn(SUPERVISION_APP_PACKAGE)
        `when`(mockPackageManager.queryIntentActivitiesAsUser(any(), anyInt(), anyInt()))
            .thenReturn(listOf(ResolveInfo()))

        val intent = SupervisionIntentProvider.getSettingsIntent(context)

        assertThat(intent).isNotNull()
        assertThat(intent?.action).isEqualTo("android.settings.SHOW_PARENTAL_CONTROLS")
        assertThat(intent?.`package`).isEqualTo(SUPERVISION_APP_PACKAGE)
    }

    private companion object {
        const val SUPERVISION_APP_PACKAGE = "app.supervision"
    }
}
