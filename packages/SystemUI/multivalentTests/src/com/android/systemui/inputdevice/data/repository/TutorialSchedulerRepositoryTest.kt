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
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TutorialSchedulerRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: TutorialSchedulerRepository
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Before
    fun setup() {
        underTest =
            TutorialSchedulerRepository(
                context,
                kosmos.backgroundScope,
                "TutorialSchedulerRepositoryTest",
            )
    }

    @Test
    fun initialState() = runTestAndClear {
        assertThat(underTest.wasEverConnected(KEYBOARD)).isFalse()
        assertThat(underTest.wasEverConnected(TOUCHPAD)).isFalse()
        assertThat(underTest.isNotified(KEYBOARD)).isFalse()
        assertThat(underTest.isNotified(TOUCHPAD)).isFalse()
        assertThat(underTest.isScheduledTutorialLaunched(KEYBOARD)).isFalse()
        assertThat(underTest.isScheduledTutorialLaunched(TOUCHPAD)).isFalse()
    }

    @Test
    fun connectKeyboard() = runTestAndClear {
        val now = Instant.now()
        underTest.setFirstConnectionTime(KEYBOARD, now)

        assertThat(underTest.wasEverConnected(KEYBOARD)).isTrue()
        assertThat(underTest.getFirstConnectionTime(KEYBOARD)!!.epochSecond)
            .isEqualTo(now.epochSecond)
        assertThat(underTest.wasEverConnected(TOUCHPAD)).isFalse()
    }

    @Test
    fun launchKeyboard() = runTestAndClear {
        val now = Instant.now()
        underTest.setScheduledTutorialLaunchTime(KEYBOARD, now)

        assertThat(underTest.isScheduledTutorialLaunched(KEYBOARD)).isTrue()
        assertThat(underTest.getScheduledTutorialLaunchTime(KEYBOARD)!!.epochSecond)
            .isEqualTo(now.epochSecond)
        assertThat(underTest.isScheduledTutorialLaunched(TOUCHPAD)).isFalse()
    }

    @Test
    fun notifyKeyboard() = runTestAndClear {
        val now = Instant.now()
        underTest.setNotifiedTime(KEYBOARD, now)

        assertThat(underTest.isNotified(KEYBOARD)).isTrue()
        assertThat(underTest.getNotifiedTime(KEYBOARD)!!.epochSecond).isEqualTo(now.epochSecond)
        assertThat(underTest.isNotified(TOUCHPAD)).isFalse()
    }

    private fun runTestAndClear(block: suspend () -> Unit) =
        testScope.runTest {
            try {
                block()
            } finally {
                underTest.clear()
            }
        }
}
