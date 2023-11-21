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

package com.android.settingslib.spaprivileged.template.preference

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
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
class RestrictedMainSwitchPreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val fakeBlockedByAdmin = FakeBlockedByAdmin()

    private val fakeRestrictionsProvider = FakeRestrictionsProvider()

    private val switchPreferenceModel = object : SwitchPreferenceModel {
        override val title = TITLE
        private val checkedState = mutableStateOf(true)
        override val checked = { checkedState.value }
        override val onCheckedChange: (Boolean) -> Unit = { checkedState.value = it }
    }

    @Test
    fun whenRestrictionsKeysIsEmpty_enabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = emptyList())

        setContent(restrictions)

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNode(isOn()).assertIsDisplayed()
    }

    @Test
    fun whenRestrictionsKeysIsEmpty_toggleable() {
        val restrictions = Restrictions(userId = USER_ID, keys = emptyList())

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        composeTestRule.onNode(isOff()).assertIsDisplayed()
    }

    @Test
    fun whenNoRestricted_enabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = NoRestricted

        setContent(restrictions)

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNode(isOn()).assertIsDisplayed()
    }

    @Test
    fun whenNoRestricted_toggleable() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = NoRestricted

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        composeTestRule.onNode(isOff()).assertIsDisplayed()
    }

    @Test
    fun whenBaseUserRestricted_disabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = BaseUserRestricted

        setContent(restrictions)

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNode(isOff()).assertIsDisplayed()
    }

    @Test
    fun whenBaseUserRestricted_notToggleable() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = BaseUserRestricted

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        composeTestRule.onNode(isOff()).assertIsDisplayed()
    }

    @Test
    fun whenBlockedByAdmin_disabled() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = fakeBlockedByAdmin

        setContent(restrictions)

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText(FakeBlockedByAdmin.SUMMARY).assertDoesNotExist()
        composeTestRule.onNode(isOn()).assertIsDisplayed()
    }

    @Test
    fun whenBlockedByAdmin_click() {
        val restrictions = Restrictions(userId = USER_ID, keys = listOf(RESTRICTION_KEY))
        fakeRestrictionsProvider.restrictedMode = fakeBlockedByAdmin

        setContent(restrictions)
        composeTestRule.onRoot().performClick()

        assertThat(fakeBlockedByAdmin.sendShowAdminSupportDetailsIntentIsCalled).isTrue()
    }

    private fun setContent(restrictions: Restrictions) {
        composeTestRule.setContent {
            RestrictedMainSwitchPreference(switchPreferenceModel, restrictions) { _, _ ->
                fakeRestrictionsProvider
            }
        }
    }

    private companion object {
        const val TITLE = "Title"
        const val USER_ID = 0
        const val RESTRICTION_KEY = "restriction_key"
    }
}
