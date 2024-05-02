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

import android.os.Handler
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.mediaOutputInteractor
import com.android.systemui.volume.panel.shared.model.filterData
import com.android.systemui.volume.remoteMediaController
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
class MediaDeviceSessionInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: MediaDeviceSessionInteractor

    @Before
    fun setup() {
        with(kosmos) {
            mediaControllerRepository.setActiveSessions(
                listOf(localMediaController, remoteMediaController)
            )

            underTest =
                MediaDeviceSessionInteractor(
                    testScope.testScheduler,
                    Handler(TestableLooper.get(kosmos.testCase).looper),
                    mediaControllerRepository,
                )
        }
    }

    @Test
    fun playbackInfo_returnsPlaybackInfo() {
        with(kosmos) {
            testScope.runTest {
                val session by
                    collectLastValue(mediaOutputInteractor.defaultActiveMediaSession.filterData())
                runCurrent()
                val info by collectLastValue(underTest.playbackInfo(session!!))
                runCurrent()

                assertThat(info).isEqualTo(localMediaController.playbackInfo)
            }
        }
    }

    @Test
    fun playbackState_returnsPlaybackState() {
        with(kosmos) {
            testScope.runTest {
                val session by
                    collectLastValue(mediaOutputInteractor.defaultActiveMediaSession.filterData())
                runCurrent()
                val state by collectLastValue(underTest.playbackState(session!!))
                runCurrent()

                assertThat(state).isEqualTo(localMediaController.playbackState)
            }
        }
    }
}
