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

package com.android.systemui.keyboard.shortcut.data.source

import android.content.Intent.CATEGORY_APP_BROWSER
import android.content.Intent.CATEGORY_APP_CALCULATOR
import android.content.Intent.CATEGORY_APP_CALENDAR
import android.content.Intent.CATEGORY_APP_CONTACTS
import android.content.Intent.CATEGORY_APP_EMAIL
import android.content.Intent.CATEGORY_APP_MAPS
import android.content.Intent.CATEGORY_APP_MESSAGING
import android.content.Intent.CATEGORY_APP_MUSIC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.shortcut.shortcutHelperAppCategoriesShortcutsSource
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.icons.fakeAppCategoryIconProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppCategoriesShortcutsSourceTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val defaultAppIconsProvider = kosmos.fakeAppCategoryIconProvider
    private val source = kosmos.shortcutHelperAppCategoriesShortcutsSource

    @Before
    fun setUp() {
        categoryApps.forEach { categoryAppIcon ->
            defaultAppIconsProvider.installCategoryApp(
                categoryAppIcon.category,
                categoryAppIcon.packageName,
                categoryAppIcon.iconResId
            )
        }
    }

    @Test
    fun shortcutGroups_returnsSingleGroup() =
        testScope.runTest { assertThat(source.shortcutGroups(TEST_DEVICE_ID)).hasSize(1) }

    @Test
    fun shortcutGroups_hasAssistantIcon() =
        testScope.runTest {
            defaultAppIconsProvider.installAssistantApp(ASSISTANT_PACKAGE, ASSISTANT_ICON_RES_ID)

            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Assistant" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(ASSISTANT_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(ASSISTANT_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasBrowserIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Browser" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(BROWSER_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(BROWSER_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasContactsIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Contacts" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(CONTACTS_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(CONTACTS_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasEmailIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Email" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(EMAIL_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(EMAIL_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasCalendarIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Calendar" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(CALENDAR_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(CALENDAR_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasMapsIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Maps" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(MAPS_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(MAPS_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasMessagingIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "SMS" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(MESSAGING_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(MESSAGING_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasMusicIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Music" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(MUSIC_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(MUSIC_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_hasCalculatorIcon() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutInfo = shortcuts.first { it.label == "Calculator" }

            assertThat(shortcutInfo.icon!!.resPackage).isEqualTo(CALCULATOR_PACKAGE)
            assertThat(shortcutInfo.icon!!.resId).isEqualTo(CALCULATOR_ICON_RES_ID)
        }

    @Test
    fun shortcutGroups_shortcutsSortedByLabelIgnoringCase() =
        testScope.runTest {
            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items

            val shortcutLabels = shortcuts.map { it.label!!.toString() }
            assertThat(shortcutLabels).isEqualTo(shortcutLabels.sortedBy { it.lowercase() })
        }

    @Test
    fun shortcutGroups_noAssistantApp_excludesAssistantFromShortcuts() =
        testScope.runTest {
            val shortcutLabels =
                source.shortcutGroups(TEST_DEVICE_ID).first().items.map { it.label!!.toString() }

            assertThat(shortcutLabels).doesNotContain("Assistant")
        }

    private companion object {
        private const val ASSISTANT_PACKAGE = "the.assistant.app"
        private const val ASSISTANT_ICON_RES_ID = 123

        private const val BROWSER_PACKAGE = "com.test.browser"
        private const val BROWSER_ICON_RES_ID = 1

        private const val CONTACTS_PACKAGE = "app.test.contacts"
        private const val CONTACTS_ICON_RES_ID = 234

        private const val EMAIL_PACKAGE = "email.app.test"
        private const val EMAIL_ICON_RES_ID = 351

        private const val CALENDAR_PACKAGE = "app.test.calendar"
        private const val CALENDAR_ICON_RES_ID = 411

        private const val MAPS_PACKAGE = "maps.app.package"
        private const val MAPS_ICON_RES_ID = 999

        private const val MUSIC_PACKAGE = "com.android.music"
        private const val MUSIC_ICON_RES_ID = 101

        private const val MESSAGING_PACKAGE = "my.sms.app"
        private const val MESSAGING_ICON_RES_ID = 9191

        private const val CALCULATOR_PACKAGE = "that.calculator.app"
        private const val CALCULATOR_ICON_RES_ID = 314

        private val categoryApps =
            listOf(
                CategoryApp(CATEGORY_APP_BROWSER, BROWSER_PACKAGE, BROWSER_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_CONTACTS, CONTACTS_PACKAGE, CONTACTS_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_EMAIL, EMAIL_PACKAGE, EMAIL_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_CALENDAR, CALENDAR_PACKAGE, CALENDAR_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_MAPS, MAPS_PACKAGE, MAPS_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_MUSIC, MUSIC_PACKAGE, MUSIC_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_MESSAGING, MESSAGING_PACKAGE, MESSAGING_ICON_RES_ID),
                CategoryApp(CATEGORY_APP_CALCULATOR, CALCULATOR_PACKAGE, CALCULATOR_ICON_RES_ID),
            )

        private const val TEST_DEVICE_ID = 123
    }

    private class CategoryApp(val category: String, val packageName: String, val iconResId: Int)
}
