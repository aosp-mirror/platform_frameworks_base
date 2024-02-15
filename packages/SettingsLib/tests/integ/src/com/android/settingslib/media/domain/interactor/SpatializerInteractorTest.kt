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

package com.android.settingslib.media.domain.interactor

import android.media.AudioDeviceAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SpatializerInteractorTest {

    private val testScope = TestScope()
    private val underTest = SpatializerInteractor(FakeSpatializerRepository())

    @Test
    fun setEnabledFalse_isEnabled_false() {
        testScope.runTest {
            underTest.setEnabled(deviceAttributes, false)

            assertThat(underTest.isEnabled(deviceAttributes)).isFalse()
        }
    }

    @Test
    fun setEnabledTrue_isEnabled_true() {
        testScope.runTest {
            underTest.setEnabled(deviceAttributes, true)

            assertThat(underTest.isEnabled(deviceAttributes)).isTrue()
        }
    }

    private companion object {
        val deviceAttributes = AudioDeviceAttributes(0, 0, "test_device")
    }
}
