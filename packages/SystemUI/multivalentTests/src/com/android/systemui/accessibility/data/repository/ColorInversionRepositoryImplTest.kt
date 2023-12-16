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

package com.android.systemui.accessibility.data.repository

import android.os.UserHandle
import android.provider.Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ColorInversionRepositoryImplTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val settings: FakeSettings = FakeSettings()

    private lateinit var underTest: ColorInversionRepository

    @Before
    fun setUp() {
        underTest =
            ColorInversionRepositoryImpl(
                testDispatcher,
                settings,
            )
    }

    @Test
    fun isEnabled_initiallyGetsSettingsValue() =
        scope.runTest {
            settings.putIntForUser(SETTING_NAME, 1, TEST_USER_1.identifier)

            underTest =
                ColorInversionRepositoryImpl(
                    testDispatcher,
                    settings,
                )

            underTest.isEnabled(TEST_USER_1).launchIn(backgroundScope)
            runCurrent()

            val actualValue: Boolean = underTest.isEnabled(TEST_USER_1).first()
            assertThat(actualValue).isTrue()
        }

    @Test
    fun isEnabled_settingUpdated_valueUpdated() =
        scope.runTest {
            underTest.isEnabled(TEST_USER_1).launchIn(backgroundScope)

            settings.putIntForUser(SETTING_NAME, DISABLED, TEST_USER_1.identifier)
            runCurrent()
            assertThat(underTest.isEnabled(TEST_USER_1).first()).isFalse()

            settings.putIntForUser(SETTING_NAME, ENABLED, TEST_USER_1.identifier)
            runCurrent()
            assertThat(underTest.isEnabled(TEST_USER_1).first()).isTrue()

            settings.putIntForUser(SETTING_NAME, DISABLED, TEST_USER_1.identifier)
            runCurrent()
            assertThat(underTest.isEnabled(TEST_USER_1).first()).isFalse()
        }

    @Test
    fun isEnabled_settingForUserOneOnly_valueUpdatedForUserOneOnly() =
        scope.runTest {
            underTest.isEnabled(TEST_USER_1).launchIn(backgroundScope)
            settings.putIntForUser(SETTING_NAME, DISABLED, TEST_USER_1.identifier)
            underTest.isEnabled(TEST_USER_2).launchIn(backgroundScope)
            settings.putIntForUser(SETTING_NAME, DISABLED, TEST_USER_2.identifier)

            runCurrent()
            assertThat(underTest.isEnabled(TEST_USER_1).first()).isFalse()
            assertThat(underTest.isEnabled(TEST_USER_2).first()).isFalse()

            settings.putIntForUser(SETTING_NAME, ENABLED, TEST_USER_1.identifier)
            runCurrent()
            assertThat(underTest.isEnabled(TEST_USER_1).first()).isTrue()
            assertThat(underTest.isEnabled(TEST_USER_2).first()).isFalse()
        }

    @Test
    fun setEnabled() =
        scope.runTest {
            val success = underTest.setIsEnabled(true, TEST_USER_1)
            runCurrent()
            assertThat(success).isTrue()

            val actualValue = settings.getIntForUser(SETTING_NAME, TEST_USER_1.identifier)
            assertThat(actualValue).isEqualTo(ENABLED)
        }

    @Test
    fun setDisabled() =
        scope.runTest {
            val success = underTest.setIsEnabled(false, TEST_USER_1)
            runCurrent()
            assertThat(success).isTrue()

            val actualValue = settings.getIntForUser(SETTING_NAME, TEST_USER_1.identifier)
            assertThat(actualValue).isEqualTo(DISABLED)
        }

    companion object {
        private const val SETTING_NAME = ACCESSIBILITY_DISPLAY_INVERSION_ENABLED
        private const val DISABLED = 0
        private const val ENABLED = 1
        private val TEST_USER_1 = UserHandle.of(1)!!
        private val TEST_USER_2 = UserHandle.of(2)!!
    }
}
