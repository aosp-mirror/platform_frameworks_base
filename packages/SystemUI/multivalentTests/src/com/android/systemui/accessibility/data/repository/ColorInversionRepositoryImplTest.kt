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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ColorInversionRepositoryImplTest : SysuiTestCase() {

    private val testUser1 = UserHandle.of(1)!!
    private val testUser2 = UserHandle.of(2)!!
    private val kosmos = testKosmos()
    private val testDispatcher = kosmos.testDispatcher
    private val scope = kosmos.testScope
    private val settings = kosmos.fakeSettings

    private lateinit var underTest: ColorInversionRepository

    @Before
    fun setUp() {
        underTest =
            ColorInversionRepositoryImpl(
                testDispatcher,
                scope.backgroundScope,
                settings,
            )
    }

    @Test
    fun isEnabled_settingNotInitialized_returnsFalseByDefault() =
        scope.runTest {
            val actualValue by collectLastValue(underTest.isEnabled(testUser1))

            runCurrent()

            assertThat(actualValue).isFalse()
        }

    @Test
    fun isEnabled_initiallyGetsSettingsValue() =
        scope.runTest {
            val actualValue by collectLastValue(underTest.isEnabled(testUser1))

            settings.putIntForUser(SETTING_NAME, ENABLED, testUser1.identifier)
            runCurrent()

            assertThat(actualValue).isTrue()
        }

    @Test
    fun isEnabled_settingUpdated_valueUpdated() =
        scope.runTest {
            val flowValues: List<Boolean> by collectValues(underTest.isEnabled(testUser1))

            settings.putIntForUser(SETTING_NAME, DISABLED, testUser1.identifier)
            runCurrent()
            settings.putIntForUser(SETTING_NAME, ENABLED, testUser1.identifier)
            runCurrent()
            settings.putIntForUser(SETTING_NAME, DISABLED, testUser1.identifier)
            runCurrent()

            assertThat(flowValues.size).isEqualTo(3)
            assertThat(flowValues).containsExactly(false, true, false).inOrder()
        }

    @Test
    fun isEnabled_settingForUserOneOnly_valueUpdatedForUserOneOnly() =
        scope.runTest {
            val lastValueUser1 by collectLastValue(underTest.isEnabled(testUser1))
            val lastValueUser2 by collectLastValue(underTest.isEnabled(testUser2))

            settings.putIntForUser(SETTING_NAME, DISABLED, testUser1.identifier)
            settings.putIntForUser(SETTING_NAME, DISABLED, testUser2.identifier)
            runCurrent()
            assertThat(lastValueUser1).isFalse()
            assertThat(lastValueUser2).isFalse()

            settings.putIntForUser(SETTING_NAME, ENABLED, testUser1.identifier)
            runCurrent()
            assertThat(lastValueUser1).isTrue()
            assertThat(lastValueUser2).isFalse()
        }

    @Test
    fun setEnabled() =
        scope.runTest {
            val success = underTest.setIsEnabled(true, testUser1)
            runCurrent()
            assertThat(success).isTrue()

            val actualValue = settings.getIntForUser(SETTING_NAME, testUser1.identifier)
            assertThat(actualValue).isEqualTo(ENABLED)
        }

    @Test
    fun setDisabled() =
        scope.runTest {
            val success = underTest.setIsEnabled(false, testUser1)
            runCurrent()
            assertThat(success).isTrue()

            val actualValue = settings.getIntForUser(SETTING_NAME, testUser1.identifier)
            assertThat(actualValue).isEqualTo(DISABLED)
        }

    companion object {
        private const val SETTING_NAME = ACCESSIBILITY_DISPLAY_INVERSION_ENABLED
        private const val DISABLED = 0
        private const val ENABLED = 1
    }
}
