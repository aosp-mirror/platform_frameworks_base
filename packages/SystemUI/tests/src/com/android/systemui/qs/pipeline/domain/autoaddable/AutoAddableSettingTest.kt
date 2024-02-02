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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class AutoAddableSettingTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val secureSettings = FakeSettings()
    private val underTest =
        AutoAddableSetting(
            secureSettings,
            testDispatcher,
            SETTING,
            SPEC,
        )

    @Test
    fun settingNotSet_noSignal() =
        testScope.runTest {
            val userId = 0
            val signal by collectLastValue(underTest.autoAddSignal(userId))

            assertThat(signal).isNull() // null means no emitted value
        }

    @Test
    fun settingSetTo0_noSignal() =
        testScope.runTest {
            val userId = 0
            val signal by collectLastValue(underTest.autoAddSignal(userId))

            secureSettings.putIntForUser(SETTING, 0, userId)

            assertThat(signal).isNull() // null means no emitted value
        }

    @Test
    fun settingSetToNon0_signal() =
        testScope.runTest {
            val userId = 0
            val signal by collectLastValue(underTest.autoAddSignal(userId))

            secureSettings.putIntForUser(SETTING, 42, userId)

            assertThat(signal).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun settingSetForUser_onlySignalInThatUser() =
        testScope.runTest {
            val signal0 by collectLastValue(underTest.autoAddSignal(0))
            val signal1 by collectLastValue(underTest.autoAddSignal(1))

            secureSettings.putIntForUser(SETTING, /* value */ 42, /* userHandle */ 1)

            assertThat(signal0).isNull()
            assertThat(signal1).isEqualTo(AutoAddSignal.Add(SPEC))
        }

    @Test
    fun multipleNonZeroChanges_onlyOneSignal() =
        testScope.runTest {
            val userId = 0
            val signals by collectValues(underTest.autoAddSignal(userId))

            secureSettings.putIntForUser(SETTING, 1, userId)
            secureSettings.putIntForUser(SETTING, 2, userId)

            assertThat(signals.size).isEqualTo(1)
        }

    @Test
    fun strategyIfNotAdded() {
        assertThat(underTest.autoAddTracking).isEqualTo(AutoAddTracking.IfNotAdded(SPEC))
    }

    companion object {
        private const val SETTING = "setting"
        private val SPEC = TileSpec.create("spec")
    }
}
