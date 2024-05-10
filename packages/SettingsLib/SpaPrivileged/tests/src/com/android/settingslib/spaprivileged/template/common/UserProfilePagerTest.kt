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

package com.android.settingslib.spaprivileged.template.common

import android.content.Context
import android.content.pm.UserInfo
import android.content.pm.UserProperties
import android.os.UserManager
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spaprivileged.framework.common.userManager
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy

@RunWith(AndroidJUnit4::class)
class UserProfilePagerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockUserManager = mock<UserManager> {
        on { getProfiles(any()) } doReturn listOf(USER_0)
        on { getUserProperties(USER_0.userHandle) } doReturn
            UserProperties.Builder()
                .setShowInSettings(UserProperties.SHOW_IN_LAUNCHER_WITH_PARENT)
                .build()
    }

    private val context: Context = spy(ApplicationProvider.getApplicationContext()) {
        on { userManager } doReturn mockUserManager
    }

    @Test
    fun userProfilePager() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                UserProfilePager { userGroup ->
                    Text(text = userGroup.userInfos.joinToString { it.id.toString() })
                }
            }
        }

        composeTestRule.onNodeWithText(USER_0.id.toString()).assertIsDisplayed()
    }

    private companion object {
        val USER_0 = UserInfo(0, "", 0)
    }
}
