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

package com.android.systemui.volume.domain.interactor

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.systemui.SysuiTestCase
import com.android.systemui.volume.data.repository.FakeAudioRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class AudioModeInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val fakeAudioRepository = FakeAudioRepository()

    private val underTest = AudioModeInteractor(fakeAudioRepository)

    @Test
    fun ongoingCallModes_isOnGoingCall() {
        testScope.runTest {
            for (mode in ongoingCallModes) {
                var isOngoingCall = false
                underTest.isOngoingCall.onEach { isOngoingCall = it }.launchIn(backgroundScope)

                fakeAudioRepository.setMode(mode)
                runCurrent()

                assertThat(isOngoingCall).isTrue()
            }
        }
    }

    @Test
    fun notOngoingCallModes_isNotOnGoingCall() {
        testScope.runTest {
            var isOngoingCall = true
            underTest.isOngoingCall.onEach { isOngoingCall = it }.launchIn(backgroundScope)

            fakeAudioRepository.setMode(AudioManager.MODE_CURRENT)
            runCurrent()

            assertThat(isOngoingCall).isFalse()
        }
    }

    private companion object {
        private val ongoingCallModes =
            setOf(
                AudioManager.MODE_RINGTONE,
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION,
            )
    }
}
