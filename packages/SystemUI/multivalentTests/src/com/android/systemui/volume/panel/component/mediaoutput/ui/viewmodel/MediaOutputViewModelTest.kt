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

package com.android.systemui.volume.panel.component.mediaoutput.ui.viewmodel

import android.content.applicationContext
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.domain.interactor.audioModeInteractor
import com.android.systemui.volume.domain.interactor.audioOutputInteractor
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.mediaDeviceSessionInteractor
import com.android.systemui.volume.mediaOutputActionsInteractor
import com.android.systemui.volume.mediaOutputInteractor
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
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MediaOutputViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val playbackStateBuilder = PlaybackState.Builder()

    private lateinit var underTest: MediaOutputViewModel

    @Before
    fun setup() {
        with(kosmos) {
            underTest =
                MediaOutputViewModel(
                    applicationContext,
                    testScope.backgroundScope,
                    mediaOutputActionsInteractor,
                    mediaDeviceSessionInteractor,
                    audioOutputInteractor,
                    audioModeInteractor,
                    mediaOutputInteractor,
                    uiEventLogger,
                )

            with(context.orCreateTestableResources) {
                addOverride(R.string.media_output_label_title, "media_output_label_title")
                addOverride(
                    R.string.media_output_title_without_playing,
                    "media_output_title_without_playing"
                )
            }

            whenever(localMediaController.packageName).thenReturn("test.pkg")
            whenever(localMediaController.sessionToken).thenReturn(MediaSession.Token(0, mock {}))
            whenever(localMediaController.playbackState).then { playbackStateBuilder.build() }

            mediaControllerRepository.setActiveSessions(listOf(localMediaController))
        }
    }

    @Test
    fun playingSession_connectedDeviceViewMode_hasTheDevice() {
        with(kosmos) {
            testScope.runTest {
                playbackStateBuilder.setState(PlaybackState.STATE_PLAYING, 0, 0f)
                localMediaRepository.updateCurrentConnectedDevice(
                    mock { whenever(name).thenReturn("test_device") }
                )

                val connectedDeviceViewModel by collectLastValue(underTest.connectedDeviceViewModel)
                runCurrent()

                assertThat(connectedDeviceViewModel!!.label).isEqualTo("media_output_label_title")
                assertThat(connectedDeviceViewModel!!.deviceName).isEqualTo("test_device")
            }
        }
    }

    @Test
    fun notPlaying_connectedDeviceViewMode_hasTheDevice() {
        with(kosmos) {
            testScope.runTest {
                playbackStateBuilder.setState(PlaybackState.STATE_STOPPED, 0, 0f)
                localMediaRepository.updateCurrentConnectedDevice(
                    mock { whenever(name).thenReturn("test_device") }
                )

                val connectedDeviceViewModel by collectLastValue(underTest.connectedDeviceViewModel)
                runCurrent()

                assertThat(connectedDeviceViewModel!!.label)
                    .isEqualTo("media_output_title_without_playing")
                assertThat(connectedDeviceViewModel!!.deviceName).isEqualTo("test_device")
            }
        }
    }
}
