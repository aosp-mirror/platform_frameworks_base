/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardMediaViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val underTest = kosmos.keyguardMediaViewModelFactory.create()

    @Before
    fun setUp() {
        underTest.activateIn(kosmos.testScope)
    }

    @Test
    fun onDozing_noActiveMedia_mediaIsHidden() =
        kosmos.runTest {
            keyguardRepository.setIsDozing(true)

            assertThat(underTest.isMediaVisible).isFalse()
        }

    @Test
    fun onDozing_activeMediaExists_mediaIsHidden() =
        kosmos.runTest {
            val userMedia = MediaData(active = true)

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            keyguardRepository.setIsDozing(true)

            assertThat(underTest.isMediaVisible).isFalse()
        }

    @Test
    fun onDeviceAwake_activeMediaExists_mediaIsVisible() =
        kosmos.runTest {
            val userMedia = MediaData(active = true)

            mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            keyguardRepository.setIsDozing(false)

            assertThat(underTest.isMediaVisible).isTrue()
        }

    @Test
    fun onDeviceAwake_noActiveMedia_mediaIsHidden() =
        kosmos.runTest {
            keyguardRepository.setIsDozing(false)

            assertThat(underTest.isMediaVisible).isFalse()
        }
}
