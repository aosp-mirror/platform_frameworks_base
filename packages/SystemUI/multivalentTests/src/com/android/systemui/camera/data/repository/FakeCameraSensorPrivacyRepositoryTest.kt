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

package com.android.systemui.camera.data.repository

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class FakeCameraSensorPrivacyRepositoryTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val underTest = kosmos.fakeCameraSensorPrivacyRepository
    private val testUser = UserHandle.of(1)

    @Test
    fun isCameraSensorPrivacyEnabled_emitsFalseOnStart() = runTest {
        val isCameraSensorPrivacySettingEnabled by collectValues(underTest.isEnabled(testUser))

        assertThat(isCameraSensorPrivacySettingEnabled).hasSize(1)
        assertThat(isCameraSensorPrivacySettingEnabled.first()).isFalse()
    }

    /**
     * The value explicitly set in this test is not distinct, therefore only 1 value is collected.
     */
    @Test
    fun isCameraSensorPrivacyEnabled_emitsDistinctValueOnly() = runTest {
        val isCameraSensorPrivacySettingEnabled by collectValues(underTest.isEnabled(testUser))
        underTest.setEnabled(testUser, false)
        runCurrent()

        assertThat(isCameraSensorPrivacySettingEnabled).hasSize(1)
        assertThat(isCameraSensorPrivacySettingEnabled.first()).isFalse()
    }

    @Test
    fun isCameraSensorPrivacyEnabled_canSetValue3Times() = runTest {
        val isCameraSensorPrivacySettingEnabled by collectValues(underTest.isEnabled(testUser))
        runCurrent()
        underTest.setEnabled(testUser, true)
        runCurrent()
        underTest.setEnabled(testUser, false)
        runCurrent()
        underTest.setEnabled(testUser, true)
        runCurrent()
        assertThat(isCameraSensorPrivacySettingEnabled).hasSize(4)
        assertThat(isCameraSensorPrivacySettingEnabled.last()).isTrue()
    }
}
