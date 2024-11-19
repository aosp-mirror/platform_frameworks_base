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

package com.android.systemui.volume.dialog.sliders.domain.interactor

import android.content.packageManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

private const val AUDIO_SHARING_STREAM = 99

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class VolumeDialogSlidersInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: VolumeDialogSlidersInteractor

    private var isTv: Boolean = false

    @Before
    fun setUp() {
        with(kosmos) {
            whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenAnswer {
                isTv
            }

            underTest = kosmos.volumeDialogSlidersInteractor
        }
    }

    @Test
    fun activeStreamIsSlider() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                fakeVolumeDialogController.updateState {
                    activeStream = AudioManager.STREAM_SYSTEM
                    states.put(AudioManager.STREAM_MUSIC, buildStreamState())
                    states.put(AudioManager.STREAM_SYSTEM, buildStreamState())
                }

                val slidersModel by collectLastValue(underTest.sliders)
                runCurrent()

                assertThat(slidersModel!!.slider)
                    .isEqualTo(VolumeDialogSliderType.Stream(AudioManager.STREAM_SYSTEM))
                assertThat(slidersModel!!.floatingSliders)
                    .isEqualTo(listOf(VolumeDialogSliderType.Stream(AudioManager.STREAM_MUSIC)))
            }
        }

    @Test
    fun streamsOrder() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                fakeVolumeDialogController.onAccessibilityModeChanged(true)
                fakeVolumeDialogController.updateState {
                    activeStream = AudioManager.STREAM_MUSIC
                    states.put(AUDIO_SHARING_STREAM, buildStreamState { dynamic = true })
                    states.put(AUDIO_SHARING_STREAM + 1, buildStreamState { dynamic = true })
                    states.put(AudioManager.STREAM_MUSIC, buildStreamState())
                    states.put(AudioManager.STREAM_ACCESSIBILITY, buildStreamState())
                }

                val slidersModel by collectLastValue(underTest.sliders)
                runCurrent()

                assertThat(slidersModel!!.slider)
                    .isEqualTo(VolumeDialogSliderType.Stream(AudioManager.STREAM_MUSIC))
                assertThat(slidersModel!!.floatingSliders)
                    .isEqualTo(
                        listOf(
                            VolumeDialogSliderType.Stream(AudioManager.STREAM_ACCESSIBILITY),
                            VolumeDialogSliderType.AudioSharingStream(AUDIO_SHARING_STREAM),
                            VolumeDialogSliderType.RemoteMediaStream(AUDIO_SHARING_STREAM + 1),
                        )
                    )
            }
        }

    @Test
    fun accessibilityStreamDisabled_filteredOut() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                fakeVolumeDialogController.onAccessibilityModeChanged(false)
                fakeVolumeDialogController.updateState {
                    states.put(AudioManager.STREAM_ACCESSIBILITY, buildStreamState())
                    states.put(AudioManager.STREAM_MUSIC, buildStreamState())
                }

                val slidersModel by collectLastValue(underTest.sliders)
                runCurrent()

                assertThat(slidersModel!!.slider)
                    .isEqualTo(VolumeDialogSliderType.Stream(AudioManager.STREAM_MUSIC))
                assertThat(slidersModel!!.floatingSliders).isEmpty()
            }
        }

    @Test
    fun isTv_onlyActiveStream() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                isTv = true
                fakeVolumeDialogController.updateState {
                    activeStream = AudioManager.STREAM_SYSTEM
                    states.put(AudioManager.STREAM_MUSIC, buildStreamState())
                    states.put(AudioManager.STREAM_SYSTEM, buildStreamState())
                }

                val slidersModel by collectLastValue(underTest.sliders)
                runCurrent()

                assertThat(slidersModel!!.slider)
                    .isEqualTo(VolumeDialogSliderType.Stream(AudioManager.STREAM_SYSTEM))
                assertThat(slidersModel!!.floatingSliders).isEmpty()
            }
        }

    @Test
    fun activeStreamChanges_showBoth() {
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                fakeVolumeDialogController.updateState {
                    activeStream = AudioManager.STREAM_SYSTEM
                    states.put(AudioManager.STREAM_MUSIC, buildStreamState())
                    states.put(AudioManager.STREAM_SYSTEM, buildStreamState())
                }
                val slidersModel by collectLastValue(underTest.sliders)
                runCurrent()

                fakeVolumeDialogController.updateState { activeStream = AudioManager.STREAM_MUSIC }
                runCurrent()

                assertThat(slidersModel!!.slider)
                    .isEqualTo(VolumeDialogSliderType.Stream(AudioManager.STREAM_MUSIC))
                assertThat(slidersModel!!.floatingSliders)
                    .containsExactly(VolumeDialogSliderType.Stream(AudioManager.STREAM_SYSTEM))
            }
        }
    }

    private fun buildStreamState(
        build: VolumeDialogController.StreamState.() -> Unit = {}
    ): VolumeDialogController.StreamState {
        return VolumeDialogController.StreamState().apply(build)
    }
}
