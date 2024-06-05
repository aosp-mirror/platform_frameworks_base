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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.waitUntilExists
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInfoTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun appInfoLabel_isDisplayed() {
        val packageInfo = PackageInfo().apply {
            applicationInfo = APP
        }
        val appInfoProvider = AppInfoProvider(packageInfo)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                appInfoProvider.AppInfo()
            }
        }

        composeTestRule.waitUntilExists(hasText(LABEL))
    }

    @Test
    fun appInfoVersion_whenDisplayVersionIsFalse() {
        val packageInfo = PackageInfo().apply {
            applicationInfo = APP
            versionName = VERSION_NAME
        }
        val appInfoProvider = AppInfoProvider(packageInfo)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                appInfoProvider.AppInfo(displayVersion = false)
            }
        }

        composeTestRule.onNodeWithText(VERSION_NAME).assertDoesNotExist()
    }

    @Test
    fun appInfoVersion_whenDisplayVersionIsTrue() {
        val packageInfo = PackageInfo().apply {
            applicationInfo = APP
            versionName = VERSION_NAME
        }
        val appInfoProvider = AppInfoProvider(packageInfo)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                appInfoProvider.AppInfo(displayVersion = true)
            }
        }

        composeTestRule.onNodeWithText(VERSION_NAME).assertIsDisplayed()
    }

    @Test
    fun footerAppVersion_versionIsDisplayed() {
        val packageInfo = PackageInfo().apply {
            applicationInfo = APP
            versionName = VERSION_NAME
            packageName = PACKAGE_NAME
        }
        val appInfoProvider = AppInfoProvider(packageInfo)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                appInfoProvider.FooterAppVersion()
            }
        }

        composeTestRule.onNodeWithText(text = "version $VERSION_NAME", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun footerAppVersion_developmentEnabled_packageNameIsDisplayed() {
        val packageInfo = PackageInfo().apply {
            applicationInfo = APP
            versionName = VERSION_NAME
            packageName = PACKAGE_NAME
        }
        val appInfoProvider = AppInfoProvider(packageInfo)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                appInfoProvider.FooterAppVersion(showPackageName = true)
            }
        }
        composeTestRule.onNodeWithText(text = PACKAGE_NAME, substring = true).assertIsDisplayed()
    }


    @Test
    fun footerAppVersion_developmentDisabled_packageNameDoesNotExist() {
        val packageInfo = PackageInfo().apply {
            applicationInfo = APP
            versionName = VERSION_NAME
            packageName = PACKAGE_NAME
        }
        val appInfoProvider = AppInfoProvider(packageInfo)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                appInfoProvider.FooterAppVersion(showPackageName = false)
            }
        }
        composeTestRule.onNodeWithText(text = PACKAGE_NAME, substring = true).assertDoesNotExist()
    }

    private companion object {
        const val LABEL = "Label"
        const val VERSION_NAME = "VersionName"
        const val PACKAGE_NAME = "package.name"
        val APP = object : ApplicationInfo() {
            override fun loadLabel(pm: PackageManager) = LABEL
        }
    }
}
