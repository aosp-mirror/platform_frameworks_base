/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.mediaoutput.domain

import android.media.AudioManager
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.domain.interactor.audioModeInteractor
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
@RunWithLooper(setAsMainLooper = true)
class MediaOutputAvailabilityCriteriaTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: MediaOutputAvailabilityCriteria

    @Before
    fun setup() {
        underTest =
            MediaOutputAvailabilityCriteria(
                kosmos.audioModeInteractor,
            )
    }

    @Test
    fun notInCall_isAvailable_true() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_NORMAL)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isTrue()
            }
        }
    }

    @Test
    fun inCall_isAvailable_false() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_IN_CALL)

                val isAvailable by collectLastValue(underTest.isAvailable())
                runCurrent()

                assertThat(isAvailable).isFalse()
            }
        }
    }
}
