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

package com.android.settingslib.spaprivileged.template.scaffold

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.widget.scaffold.MoreOptionsScope
import com.android.settingslib.spaprivileged.model.enterprise.BaseUserRestricted
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.model.enterprise.Restrictions
import com.android.settingslib.spaprivileged.tests.testutils.FakeBlockedByAdmin
import com.android.settingslib.spaprivileged.tests.testutils.FakeRestrictionsProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestrictedMenuItemTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val fakeBlockedByAdmin = FakeBlockedByAdmin()

    private val fakeRestrictionsProvider = FakeRestrictionsProvider()

    private var menuItemOnClickIsCalled = false

    @Test
    fun whenRestrictionsKeysIsEmpty_enabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = emptyList())

        setContent(restrictions)

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun whenRestrictionsKeysIsEmpty_clickable() {
        val restrictions = Restrictions(userId = USER_ID, keys = emptyList())

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        assertThat(menuItemOnClickIsCalled).isTrue()
    }

    @Test
    fun whenNoRestricted_enabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = NoRestricted

        setContent(restrictions)

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun whenNoRestricted_clickable() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = NoRestricted

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        assertThat(menuItemOnClickIsCalled).isTrue()
    }

    @Test
    fun whenBaseUserRestricted_disabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = BaseUserRestricted

        setContent(restrictions)

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun whenBaseUserRestricted_notClickable() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = BaseUserRestricted

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        assertThat(menuItemOnClickIsCalled).isFalse()
    }

    @Test
    fun whenBlockedByAdmin_disabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = fakeBlockedByAdmin

        setContent(restrictions)

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun whenBlockedByAdmin_onClick_showAdminSupportDetails() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = fakeBlockedByAdmin

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        assertThat(fakeBlockedByAdmin.sendShowAdminSupportDetailsIntentIsCalled).isTrue()
        assertThat(menuItemOnClickIsCalled).isFalse()
    }

    private fun setContent(restrictions: Restrictions) {
        val fakeMoreOptionsScope = object : MoreOptionsScope() {
            override fun dismiss() {}
        }
        composeTestRule.setContent {
            fakeMoreOptionsScope.RestrictedMenuItemImpl(
                text = TEXT,
                restrictions = restrictions,
                onClick = { menuItemOnClickIsCalled = true },
                restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
            )
        }
    }

    private companion object {
        const val TEXT = "Text"
        const val USER_ID = 0
        const val RESTRICTION_KEY = "restriction_key"
    }
}
