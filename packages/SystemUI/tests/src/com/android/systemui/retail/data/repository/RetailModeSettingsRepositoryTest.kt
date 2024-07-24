/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.retail.data.repository

import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.settings.FakeGlobalSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class RetailModeSettingsRepositoryTest : SysuiTestCase() {

    private val globalSettings = FakeGlobalSettings()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val underTest =
        RetailModeSettingsRepository(
            globalSettings,
            backgroundDispatcher = testDispatcher,
            scope = testScope.backgroundScope,
        )

    @Test
    fun retailMode_defaultFalse() =
        testScope.runTest {
            val value by collectLastValue(underTest.retailMode)
            runCurrent()

            assertThat(value).isFalse()
            assertThat(underTest.inRetailMode).isFalse()
        }

    @Test
    fun retailMode_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.retailMode)
            runCurrent()

            globalSettings.putInt(SETTING, 0)

            assertThat(value).isFalse()
            assertThat(underTest.inRetailMode).isFalse()
        }

    @Test
    fun retailMode_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.retailMode)
            runCurrent()

            globalSettings.putInt(SETTING, 1)

            assertThat(value).isTrue()
            assertThat(underTest.inRetailMode).isTrue()
        }

    companion object {
        private const val SETTING = Settings.Global.DEVICE_DEMO_MODE
    }
}
