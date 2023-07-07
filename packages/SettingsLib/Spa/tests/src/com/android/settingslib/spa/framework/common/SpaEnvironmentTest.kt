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

package com.android.settingslib.spa.framework.common

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaEnvironmentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment = SpaEnvironmentForTest(context)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSpaEnvironmentFactory() {
        SpaEnvironmentFactory.reset()
        Truth.assertThat(SpaEnvironmentFactory.isReady()).isFalse()
        Assert.assertThrows(UnsupportedOperationException::class.java) {
            SpaEnvironmentFactory.instance
        }

        SpaEnvironmentFactory.reset(spaEnvironment)
        Truth.assertThat(SpaEnvironmentFactory.isReady()).isTrue()
        Truth.assertThat(SpaEnvironmentFactory.instance).isEqualTo(spaEnvironment)
    }

    @Test
    fun testSpaEnvironmentFactoryForPreview() {
        SpaEnvironmentFactory.reset()
        composeTestRule.setContent {
            Truth.assertThat(SpaEnvironmentFactory.isReady()).isFalse()
            SpaEnvironmentFactory.resetForPreview()
            Truth.assertThat(SpaEnvironmentFactory.isReady()).isTrue()
        }
    }
}