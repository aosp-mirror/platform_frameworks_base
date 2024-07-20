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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioSharingRepository
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class AudioSharingInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    lateinit var underTest: AudioSharingInteractor

    @Before
    fun setUp() {
        with(kosmos) { underTest = audioSharingInteractor }
    }

    @Test
    fun volumeChanges_returnVolume() {
        with(kosmos) {
            testScope.runTest {
                with(audioSharingRepository) {
                    setSecondaryGroupId(TEST_GROUP_ID)
                    setVolumeMap(mapOf(TEST_GROUP_ID to TEST_VOLUME))
                }
                val volume by collectLastValue(underTest.volume)
                runCurrent()

                Truth.assertThat(volume).isEqualTo(TEST_VOLUME)
            }
        }
    }

    @Test
    fun volumeChanges_returnNull() {
        with(kosmos) {
            testScope.runTest {
                with(audioSharingRepository) {
                    setSecondaryGroupId(TEST_GROUP_ID_INVALID)
                    setVolumeMap(mapOf(TEST_GROUP_ID to TEST_VOLUME))
                }
                val volume by collectLastValue(underTest.volume)
                runCurrent()

                Truth.assertThat(volume).isNull()
            }
        }
    }

    @Test
    fun volumeChanges_returnDefaultVolume() {
        with(kosmos) {
            testScope.runTest {
                with(audioSharingRepository) {
                    setSecondaryGroupId(TEST_GROUP_ID)
                    setVolumeMap(emptyMap())
                }
                val volume by collectLastValue(underTest.volume)
                runCurrent()

                Truth.assertThat(volume).isEqualTo(TEST_VOLUME_DEFAULT)
            }
        }
    }

    private companion object {
        const val TEST_GROUP_ID = 1
        const val TEST_GROUP_ID_INVALID = -1
        const val TEST_VOLUME = 10
        const val TEST_VOLUME_DEFAULT = 20
    }
}
