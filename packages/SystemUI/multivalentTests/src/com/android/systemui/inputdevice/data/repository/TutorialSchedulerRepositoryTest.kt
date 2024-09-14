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

package com.android.systemui.inputdevice.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.KEYBOARD
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.TOUCHPAD
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TutorialSchedulerRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: TutorialSchedulerRepository
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope

    @Before
    fun setup() {
        underTest =
            TutorialSchedulerRepository(
                context,
                testScope.backgroundScope,
                "TutorialSchedulerRepositoryTest"
            )
    }

    @After
    fun clear() {
        testScope.launch { underTest.clearDataStore() }
    }

    @Test
    fun initialState() =
        testScope.runTest {
            assertThat(underTest.wasEverConnected(KEYBOARD)).isFalse()
            assertThat(underTest.wasEverConnected(TOUCHPAD)).isFalse()
            assertThat(underTest.isLaunched(KEYBOARD)).isFalse()
            assertThat(underTest.isLaunched(TOUCHPAD)).isFalse()
        }

    @Test
    fun connectKeyboard() =
        testScope.runTest {
            val now = Instant.now()
            underTest.updateFirstConnectionTime(KEYBOARD, now)

            assertThat(underTest.wasEverConnected(KEYBOARD)).isTrue()
            assertThat(underTest.firstConnectionTime(KEYBOARD)!!.epochSecond)
                .isEqualTo(now.epochSecond)
            assertThat(underTest.wasEverConnected(TOUCHPAD)).isFalse()
        }

    @Test
    fun launchKeyboard() =
        testScope.runTest {
            val now = Instant.now()
            underTest.updateLaunchTime(KEYBOARD, now)

            assertThat(underTest.isLaunched(KEYBOARD)).isTrue()
            assertThat(underTest.launchTime(KEYBOARD)!!.epochSecond).isEqualTo(now.epochSecond)
            assertThat(underTest.isLaunched(TOUCHPAD)).isFalse()
        }
}
