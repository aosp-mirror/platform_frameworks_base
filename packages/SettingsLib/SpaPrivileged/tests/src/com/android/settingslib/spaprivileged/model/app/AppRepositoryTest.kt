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

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.UserManager
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.testutils.delay
import com.android.settingslib.spaprivileged.R
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
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidJUnit4::class)
class AppRepositoryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Spy
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var userManager: UserManager

    private lateinit var appRepository: AppRepositoryImpl

    @Before
    fun setUp() {
        whenever(context.userManager).thenReturn(userManager)
        appRepository = AppRepositoryImpl(context)
    }

    @Test
    fun produceIconContentDescription_workProfile() {
        whenever(userManager.isManagedProfile(APP.userId)).thenReturn(true)

        val contentDescription = produceIconContentDescription()

        assertThat(contentDescription.value).isEqualTo(context.getString(R.string.category_work))
    }

    @Test
    fun produceIconContentDescription_personalProfile() {
        whenever(userManager.isManagedProfile(APP.userId)).thenReturn(false)

        val contentDescription = produceIconContentDescription()

        assertThat(contentDescription.value).isNull()
    }

    private fun produceIconContentDescription(): State<String?> {
        var contentDescription: State<String?> = stateOf(null)
        composeTestRule.setContent {
            contentDescription = appRepository.produceIconContentDescription(APP)
        }
        composeTestRule.delay()
        return contentDescription
    }

    private companion object {
        const val UID = 123
        val APP = ApplicationInfo().apply {
            uid = UID
        }
    }
}