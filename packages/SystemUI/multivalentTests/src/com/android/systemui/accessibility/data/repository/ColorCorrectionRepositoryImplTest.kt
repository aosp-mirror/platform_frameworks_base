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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth
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
class ColorCorrectionRepositoryImplTest : SysuiTestCase() {
    companion object {
        val TEST_USER_1 = UserHandle.of(1)!!
        val TEST_USER_2 = UserHandle.of(2)!!
    }

    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val settings: FakeSettings = FakeSettings()

    private lateinit var underTest: ColorCorrectionRepository

    @Before
    fun setUp() {
        underTest =
            ColorCorrectionRepositoryImpl(
                testDispatcher,
                settings,
            )
    }

    @Test
    fun isEnabled_initiallyGetsSettingsValue() =
        scope.runTest {
            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                1,
                TEST_USER_1.identifier
            )

            underTest =
                ColorCorrectionRepositoryImpl(
                    testDispatcher,
                    settings,
                )

            underTest.isEnabled(TEST_USER_1).launchIn(backgroundScope)
            runCurrent()

            val actualValue: Boolean = underTest.isEnabled(TEST_USER_1).first()
            Truth.assertThat(actualValue).isTrue()
        }

    @Test
    fun isEnabled_settingUpdated_valueUpdated() =
        scope.runTest {
            underTest.isEnabled(TEST_USER_1).launchIn(backgroundScope)

            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                ColorCorrectionRepositoryImpl.DISABLED,
                TEST_USER_1.identifier
            )
            runCurrent()
            Truth.assertThat(underTest.isEnabled(TEST_USER_1).first()).isFalse()

            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                ColorCorrectionRepositoryImpl.ENABLED,
                TEST_USER_1.identifier
            )
            runCurrent()
            Truth.assertThat(underTest.isEnabled(TEST_USER_1).first()).isTrue()

            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                ColorCorrectionRepositoryImpl.DISABLED,
                TEST_USER_1.identifier
            )
            runCurrent()
            Truth.assertThat(underTest.isEnabled(TEST_USER_1).first()).isFalse()
        }

    @Test
    fun isEnabled_settingForUserOneOnly_valueUpdatedForUserOneOnly() =
        scope.runTest {
            underTest.isEnabled(TEST_USER_1).launchIn(backgroundScope)
            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                ColorCorrectionRepositoryImpl.DISABLED,
                TEST_USER_1.identifier
            )
            underTest.isEnabled(TEST_USER_2).launchIn(backgroundScope)
            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                ColorCorrectionRepositoryImpl.DISABLED,
                TEST_USER_2.identifier
            )

            runCurrent()
            Truth.assertThat(underTest.isEnabled(TEST_USER_1).first()).isFalse()
            Truth.assertThat(underTest.isEnabled(TEST_USER_2).first()).isFalse()

            settings.putIntForUser(
                ColorCorrectionRepositoryImpl.SETTING_NAME,
                ColorCorrectionRepositoryImpl.ENABLED,
                TEST_USER_1.identifier
            )
            runCurrent()
            Truth.assertThat(underTest.isEnabled(TEST_USER_1).first()).isTrue()
            Truth.assertThat(underTest.isEnabled(TEST_USER_2).first()).isFalse()
        }

    @Test
    fun setEnabled() =
        scope.runTest {
            val success = underTest.setIsEnabled(true, TEST_USER_1)
            runCurrent()
            Truth.assertThat(success).isTrue()

            val actualValue =
                settings.getIntForUser(
                    ColorCorrectionRepositoryImpl.SETTING_NAME,
                    TEST_USER_1.identifier
                )
            Truth.assertThat(actualValue).isEqualTo(ColorCorrectionRepositoryImpl.ENABLED)
        }

    @Test
    fun setDisabled() =
        scope.runTest {
            val success = underTest.setIsEnabled(false, TEST_USER_1)
            runCurrent()
            Truth.assertThat(success).isTrue()

            val actualValue =
                settings.getIntForUser(
                    ColorCorrectionRepositoryImpl.SETTING_NAME,
                    TEST_USER_1.identifier
                )
            Truth.assertThat(actualValue).isEqualTo(ColorCorrectionRepositoryImpl.DISABLED)
        }
}
