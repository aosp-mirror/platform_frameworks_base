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

package com.android.settingslib.spaprivileged.template.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spaprivileged.test.R
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogglePermissionAppListPageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun appListInjectEntry_titleDisplayed() {
        val entry = TogglePermissionAppListPageProvider.buildInjectEntry(PERMISSION_TYPE) {
            TestTogglePermissionAppListModel()
        }.build()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                entry.UiLayout()
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.test_permission_title))
            .assertIsDisplayed()
    }

    @Test
    fun appListRoute() {
        val route = TogglePermissionAppListPageProvider.getRoute(PERMISSION_TYPE)

        assertThat(route).isEqualTo("TogglePermissionAppList/test.PERMISSION")
    }

    private companion object {
        const val PERMISSION_TYPE = "test.PERMISSION"
    }
}
