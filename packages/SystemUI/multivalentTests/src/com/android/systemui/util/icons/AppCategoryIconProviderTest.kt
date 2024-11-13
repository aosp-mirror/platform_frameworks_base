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

package com.android.systemui.util.icons

import android.app.role.RoleManager.ROLE_ASSISTANT
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.CATEGORY_APP_BROWSER
import android.content.Intent.CATEGORY_APP_CONTACTS
import android.content.Intent.CATEGORY_APP_EMAIL
import android.content.mockPackageManager
import android.content.mockPackageManagerWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.assist.mockAssistManager
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppCategoryIconProviderTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val packageManagerWrapper = kosmos.mockPackageManagerWrapper
    private val packageManager = kosmos.mockPackageManager
    private val assistManager = kosmos.mockAssistManager
    private val provider = kosmos.appCategoryIconProvider

    @Before
    fun setUp() {
        whenever(packageManagerWrapper.resolveActivity(any<Intent>(), any<Int>())).thenAnswer {
            invocation ->
            val category = (invocation.arguments[0] as Intent).categories.first()
            val categoryAppIcon =
                categoryAppIcons.firstOrNull { it.category == category } ?: return@thenAnswer null
            val activityInfo = ActivityInfo().also { it.packageName = categoryAppIcon.packageName }
            return@thenAnswer ResolveInfo().also { it.activityInfo = activityInfo }
        }
        whenever(packageManager.getPackageInfo(any<String>(), any<Int>())).thenAnswer { invocation
            ->
            val packageName = invocation.arguments[0] as String
            val categoryAppIcon =
                categoryAppIcons.firstOrNull { it.packageName == packageName }
                    ?: return@thenAnswer null
            val applicationInfo =
                ApplicationInfo().also {
                    it.packageName = packageName
                    it.icon = categoryAppIcon.iconResId
                }
            return@thenAnswer PackageInfo().also {
                it.packageName = packageName
                it.applicationInfo = applicationInfo
            }
        }
    }

    @Test
    fun assistantAppIcon_defaultAssistantSet_returnsIcon() =
        testScope.runTest {
            whenever(assistManager.assistInfo)
                .thenReturn(ComponentName(ASSISTANT_PACKAGE, ASSISTANT_CLASS))

            val icon = provider.assistantAppIcon() as Icon

            assertThat(icon.resPackage).isEqualTo(ASSISTANT_PACKAGE)
            assertThat(icon.resId).isEqualTo(ASSISTANT_ICON_RES_ID)
        }

    @Test
    fun assistantAppIcon_defaultAssistantNotSet_returnsNull() =
        testScope.runTest {
            whenever(assistManager.assistInfo).thenReturn(null)

            assertThat(provider.assistantAppIcon()).isNull()
        }

    @Test
    fun categoryAppIcon_returnsIconOfKnownBrowserApp() {
        testScope.runTest {
            val icon = provider.categoryAppIcon(CATEGORY_APP_BROWSER) as Icon

            assertThat(icon.resPackage).isEqualTo(BROWSER_PACKAGE)
            assertThat(icon.resId).isEqualTo(BROWSER_ICON_RES_ID)
        }
    }

    @Test
    fun categoryAppIcon_returnsIconOfKnownContactsApp() {
        testScope.runTest {
            val icon = provider.categoryAppIcon(CATEGORY_APP_CONTACTS) as Icon

            assertThat(icon.resPackage).isEqualTo(CONTACTS_PACKAGE)
            assertThat(icon.resId).isEqualTo(CONTACTS_ICON_RES_ID)
        }
    }

    @Test
    fun categoryAppIcon_noDefaultAppForCategoryEmail_returnsNull() {
        testScope.runTest {
            val icon = provider.categoryAppIcon(CATEGORY_APP_EMAIL)

            assertThat(icon).isNull()
        }
    }

    private companion object {
        private const val ASSISTANT_PACKAGE = "the.assistant.app"
        private const val ASSISTANT_CLASS = "the.assistant.app.class"
        private const val ASSISTANT_ICON_RES_ID = 123

        private const val BROWSER_PACKAGE = "com.test.browser"
        private const val BROWSER_ICON_RES_ID = 1

        private const val CONTACTS_PACKAGE = "app.test.contacts"
        private const val CONTACTS_ICON_RES_ID = 234

        private val categoryAppIcons =
            listOf(
                App(ROLE_ASSISTANT, ASSISTANT_PACKAGE, ASSISTANT_ICON_RES_ID),
                App(CATEGORY_APP_BROWSER, BROWSER_PACKAGE, BROWSER_ICON_RES_ID),
                App(CATEGORY_APP_CONTACTS, CONTACTS_PACKAGE, CONTACTS_ICON_RES_ID),
            )
    }

    private class App(val category: String, val packageName: String, val iconResId: Int)
}
