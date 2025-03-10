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

package com.android.systemui.keyguard.domain.interactor

import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMPOSE_BOUNCER
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.telephony.data.repository.fakeTelephonyRepository
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.fakeAudioRepository
import com.google.common.truth.Correspondence.transforming
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(FLAG_COMPOSE_BOUNCER)
class KeyguardMediaKeyInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest = kosmos.keyguardMediaKeyInteractor

    @Before
    fun setup() {
        underTest.activateIn(testScope)
    }

    @Test
    fun test_onKeyEvent_playPauseKeyEvents_areSkipped_whenACallIsActive() =
        testScope.runTest {
            kosmos.fakeTelephonyRepository.setIsInCall(true)

            assertEventConsumed(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PLAY))
            assertEventConsumed(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PAUSE))
            assertEventConsumed(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PLAY_PAUSE))

            assertThat(kosmos.fakeAudioRepository.dispatchedKeyEvents).isEmpty()
        }

    @Test
    fun test_onKeyEvent_playPauseKeyEvents_areNotSkipped_whenACallIsNotActive() =
        testScope.runTest {
            kosmos.fakeTelephonyRepository.setIsInCall(false)

            assertEventNotConsumed(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PAUSE))
            assertEventConsumed(KeyEvent(ACTION_UP, KEYCODE_MEDIA_PAUSE))
            assertEventNotConsumed(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PLAY))
            assertEventConsumed(KeyEvent(ACTION_UP, KEYCODE_MEDIA_PLAY))
            assertEventNotConsumed(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PLAY_PAUSE))
            assertEventConsumed(KeyEvent(ACTION_UP, KEYCODE_MEDIA_PLAY_PAUSE))

            assertThat(kosmos.fakeAudioRepository.dispatchedKeyEvents)
                .comparingElementsUsing<KeyEvent, Pair<Int, Int>>(
                    transforming({ Pair(it!!.action, it.keyCode) }, "action and keycode")
                )
                .containsExactly(
                    Pair(ACTION_UP, KEYCODE_MEDIA_PAUSE),
                    Pair(ACTION_UP, KEYCODE_MEDIA_PLAY),
                    Pair(ACTION_UP, KEYCODE_MEDIA_PLAY_PAUSE),
                )
                .inOrder()
        }

    @Test
    fun test_onKeyEvent_nonPlayPauseKeyEvents_areNotSkipped_whenACallIsActive() =
        testScope.runTest {
            kosmos.fakeTelephonyRepository.setIsInCall(true)

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MUTE))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MUTE))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_REWIND))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_RECORD))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_RECORD))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))

            assertEventConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK))
            assertEventConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK))

            assertThat(kosmos.fakeAudioRepository.dispatchedKeyEvents)
                .comparingElementsUsing<KeyEvent, Pair<Int, Int>>(
                    transforming({ Pair(it!!.action, it.keyCode) }, "action and keycode")
                )
                .containsExactly(
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MUTE),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MUTE),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_REWIND),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_RECORD),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_RECORD),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
                    Pair(ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK),
                    Pair(ACTION_UP, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK),
                )
                .inOrder()
        }

    @Test
    fun volumeKeyEvents_keyEvents_areSkipped() =
        testScope.runTest {
            kosmos.fakeTelephonyRepository.setIsInCall(false)

            assertEventNotConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            assertEventNotConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
            assertEventNotConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
            assertEventNotConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
            assertEventNotConsumed(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE))
            assertEventNotConsumed(KeyEvent(ACTION_UP, KeyEvent.KEYCODE_VOLUME_MUTE))
        }

    private fun assertEventConsumed(keyEvent: KeyEvent) {
        assertThat(underTest.processMediaKeyEvent(keyEvent)).isTrue()
    }

    private fun assertEventNotConsumed(keyEvent: KeyEvent) {
        assertThat(underTest.processMediaKeyEvent(keyEvent)).isFalse()
    }
}
