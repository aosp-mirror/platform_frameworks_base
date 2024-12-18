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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.data.repository.FakeLocalMediaRepository
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepositoryFactory
import com.android.systemui.volume.localPlaybackInfo
import com.android.systemui.volume.localPlaybackStateBuilder
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.mediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession
import com.android.systemui.volume.panel.shared.model.Result
import com.android.systemui.volume.remoteMediaController
import com.android.systemui.volume.remotePlaybackInfo
import com.android.systemui.volume.remotePlaybackStateBuilder
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
class MediaOutputInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: MediaOutputInteractor

    @Before
    fun setUp() =
        with(kosmos) {
            localMediaRepositoryFactory.setLocalMediaRepository(
                "local.test.pkg",
                FakeLocalMediaRepository().apply {
                    updateCurrentConnectedDevice(
                        mock { whenever(name).thenReturn("local_media_device") }
                    )
                },
            )
            localMediaRepositoryFactory.setLocalMediaRepository(
                "remote.test.pkg",
                FakeLocalMediaRepository().apply {
                    updateCurrentConnectedDevice(
                        mock { whenever(name).thenReturn("remote_media_device") }
                    )
                },
            )

            underTest = kosmos.mediaOutputInteractor
        }

    @Test
    fun noActiveMediaDeviceSessions_nulls() =
        with(kosmos) {
            testScope.runTest {
                mediaControllerRepository.setActiveSessions(emptyList())

                val activeMediaDeviceSessions by
                    collectLastValue(underTest.activeMediaDeviceSessions)
                runCurrent()

                assertThat(activeMediaDeviceSessions!!.local).isNull()
                assertThat(activeMediaDeviceSessions!!.remote).isNull()
            }
        }

    @Test
    fun activeMediaDeviceSessions_areParsed() =
        with(kosmos) {
            testScope.runTest {
                mediaControllerRepository.setActiveSessions(
                    listOf(localMediaController, remoteMediaController)
                )

                val activeMediaDeviceSessions by
                    collectLastValue(underTest.activeMediaDeviceSessions)
                runCurrent()

                with(activeMediaDeviceSessions!!.local!!) {
                    assertThat(packageName).isEqualTo("local.test.pkg")
                    assertThat(appLabel).isEqualTo("local_media_controller_label")
                    assertThat(canAdjustVolume).isTrue()
                }
                with(activeMediaDeviceSessions!!.remote!!) {
                    assertThat(packageName).isEqualTo("remote.test.pkg")
                    assertThat(appLabel).isEqualTo("remote_media_controller_label")
                    assertThat(canAdjustVolume).isTrue()
                }
            }
        }

    @Test
    fun activeMediaDeviceSessions_volumeControlFixed_cantAdjustVolume() =
        with(kosmos) {
            testScope.runTest {
                localPlaybackInfo =
                    MediaController.PlaybackInfo(
                        MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
                        VolumeProvider.VOLUME_CONTROL_FIXED,
                        0,
                        0,
                        AudioAttributes.Builder().build(),
                        "",
                    )
                remotePlaybackInfo =
                    MediaController.PlaybackInfo(
                        MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
                        VolumeProvider.VOLUME_CONTROL_FIXED,
                        0,
                        0,
                        AudioAttributes.Builder().build(),
                        "",
                    )
                mediaControllerRepository.setActiveSessions(
                    listOf(localMediaController, remoteMediaController)
                )

                val activeMediaDeviceSessions by
                    collectLastValue(underTest.activeMediaDeviceSessions)
                runCurrent()

                assertThat(activeMediaDeviceSessions!!.local!!.canAdjustVolume).isFalse()
                assertThat(activeMediaDeviceSessions!!.remote!!.canAdjustVolume).isFalse()
            }
        }

    @Test
    fun activeLocalAndRemoteSession_defaultSession_local() =
        with(kosmos) {
            testScope.runTest {
                localPlaybackStateBuilder.setState(PlaybackState.STATE_PLAYING, 0, 0f)
                remotePlaybackStateBuilder.setState(PlaybackState.STATE_PLAYING, 0, 0f)
                mediaControllerRepository.setActiveSessions(
                    listOf(localMediaController, remoteMediaController)
                )

                val defaultActiveMediaSession by
                    collectLastValue(underTest.defaultActiveMediaSession)
                val currentDevice by collectLastValue(underTest.currentConnectedDevice)
                runCurrent()

                with((defaultActiveMediaSession as Result.Data<MediaDeviceSession?>).data!!) {
                    assertThat(packageName).isEqualTo("local.test.pkg")
                    assertThat(appLabel).isEqualTo("local_media_controller_label")
                    assertThat(canAdjustVolume).isTrue()
                }
                assertThat(currentDevice!!.name).isEqualTo("local_media_device")
            }
        }

    @Test
    fun activeRemoteSession_defaultSession_remote() =
        with(kosmos) {
            testScope.runTest {
                localPlaybackStateBuilder.setState(PlaybackState.STATE_PAUSED, 0, 0f)
                remotePlaybackStateBuilder.setState(PlaybackState.STATE_PLAYING, 0, 0f)
                mediaControllerRepository.setActiveSessions(
                    listOf(localMediaController, remoteMediaController)
                )

                val defaultActiveMediaSession by
                    collectLastValue(underTest.defaultActiveMediaSession)
                val currentDevice by collectLastValue(underTest.currentConnectedDevice)
                runCurrent()

                with((defaultActiveMediaSession as Result.Data<MediaDeviceSession?>).data!!) {
                    assertThat(packageName).isEqualTo("remote.test.pkg")
                    assertThat(appLabel).isEqualTo("remote_media_controller_label")
                    assertThat(canAdjustVolume).isTrue()
                }
                assertThat(currentDevice!!.name).isEqualTo("remote_media_device")
            }
        }

    @Test
    fun inactiveLocalAndRemoteSession_defaultSession_local() =
        with(kosmos) {
            testScope.runTest {
                localPlaybackStateBuilder.setState(PlaybackState.STATE_PAUSED, 0, 0f)
                remotePlaybackStateBuilder.setState(PlaybackState.STATE_PAUSED, 0, 0f)
                mediaControllerRepository.setActiveSessions(
                    listOf(localMediaController, remoteMediaController)
                )

                val defaultActiveMediaSession by
                    collectLastValue(underTest.defaultActiveMediaSession)
                val currentDevice by collectLastValue(underTest.currentConnectedDevice)
                runCurrent()

                with((defaultActiveMediaSession as Result.Data<MediaDeviceSession?>).data!!) {
                    assertThat(packageName).isEqualTo("local.test.pkg")
                    assertThat(appLabel).isEqualTo("local_media_controller_label")
                    assertThat(canAdjustVolume).isTrue()
                }
                assertThat(currentDevice!!.name).isEqualTo("local_media_device")
            }
        }
}
