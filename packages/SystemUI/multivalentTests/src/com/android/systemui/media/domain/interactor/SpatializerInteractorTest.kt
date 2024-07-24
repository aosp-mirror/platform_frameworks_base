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

package com.android.systemui.media.domain.interactor

import android.media.AudioDeviceAttributes
import android.media.AudioDeviceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.media.domain.interactor.SpatializerInteractor
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.spatializerRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SpatializerInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val underTest = SpatializerInteractor(kosmos.spatializerRepository)

    @Test
    fun setSpatialAudioEnabledFalse_isEnabled_false() {
        with(kosmos) {
            testScope.runTest {
                underTest.setSpatialAudioEnabled(deviceAttributes, false)

                assertThat(underTest.isSpatialAudioEnabled(deviceAttributes)).isFalse()
            }
        }
    }

    @Test
    fun setSpatialAudioEnabledTrue_isEnabled_true() {
        with(kosmos) {
            testScope.runTest {
                underTest.setSpatialAudioEnabled(deviceAttributes, true)

                assertThat(underTest.isSpatialAudioEnabled(deviceAttributes)).isTrue()
            }
        }
    }

    @Test
    fun setHeadTrackingEnabledFalse_isEnabled_false() {
        with(kosmos) {
            testScope.runTest {
                underTest.setHeadTrackingEnabled(deviceAttributes, false)

                assertThat(underTest.isHeadTrackingEnabled(deviceAttributes)).isFalse()
            }
        }
    }

    @Test
    fun setHeadTrackingEnabledTrue_isEnabled_true() {
        with(kosmos) {
            testScope.runTest {
                underTest.setHeadTrackingEnabled(deviceAttributes, true)

                assertThat(underTest.isHeadTrackingEnabled(deviceAttributes)).isTrue()
            }
        }
    }

    private companion object {
        val deviceAttributes =
            AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                "test_address",
            )
    }
}
