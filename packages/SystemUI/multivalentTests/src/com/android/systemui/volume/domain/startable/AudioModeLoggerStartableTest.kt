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

package com.android.systemui.volume.domain.startable

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.domain.interactor.audioModeInteractor
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class AudioModeLoggerStartableTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private val kosmos = testKosmos()

    private lateinit var underTest: AudioModeLoggerStartable

    @Before
    fun setUp() {
        with(kosmos) {
            underTest =
                AudioModeLoggerStartable(
                    applicationCoroutineScope,
                    uiEventLogger,
                    audioModeInteractor,
                )
        }
    }

    @Test
    fun audioMode_inCall() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_IN_CALL)

                underTest.start()
                runCurrent()

                assertThat(uiEventLoggerFake.eventId(0))
                    .isEqualTo(VolumePanelUiEvent.VOLUME_PANEL_AUDIO_MODE_CHANGE_TO_CALLING.id)
            }
        }
    }

    @Test
    fun audioMode_notInCall() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_NORMAL)

                underTest.start()
                runCurrent()

                assertThat(uiEventLoggerFake.eventId(0))
                    .isEqualTo(VolumePanelUiEvent.VOLUME_PANEL_AUDIO_MODE_CHANGE_TO_NORMAL.id)
            }
        }
    }
}
