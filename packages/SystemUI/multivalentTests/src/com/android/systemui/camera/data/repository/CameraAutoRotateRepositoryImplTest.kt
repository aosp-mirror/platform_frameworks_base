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
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CameraAutoRotateRepositoryImplTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val settings = kosmos.fakeSettings
    private val testUser = UserHandle.of(1)

    private val underTest =
        CameraAutoRotateRepositoryImpl(settings, testScope.testScheduler, testScope.backgroundScope)

    /** 3 changes => 3 change signals + 1 signal emitted at start => 4 signals */
    @Test
    fun isCameraAutoRotateSettingEnabled_3times() =
        testScope.runTest {
            settings.putIntForUser(SETTING_NAME, DISABLE, testUser.identifier)
            val isCameraAutoRotateSettingEnabled by
                collectValues(underTest.isCameraAutoRotateSettingEnabled(testUser))
            runCurrent()
            assertThat(isCameraAutoRotateSettingEnabled.last()).isFalse()

            settings.putIntForUser(SETTING_NAME, ENABLE, testUser.identifier)
            runCurrent()
            assertThat(isCameraAutoRotateSettingEnabled.last()).isTrue()

            settings.putIntForUser(SETTING_NAME, DISABLE, testUser.identifier)
            runCurrent()
            assertThat(isCameraAutoRotateSettingEnabled.last()).isFalse()

            settings.putIntForUser(SETTING_NAME, ENABLE, testUser.identifier)
            runCurrent()
            assertThat(isCameraAutoRotateSettingEnabled.last()).isTrue()

            assertThat(isCameraAutoRotateSettingEnabled).hasSize(4)
        }

    @Test
    fun isCameraAutoRotateSettingEnabled_emitsOnStart() =
        testScope.runTest {
            val isCameraAutoRotateSettingEnabled: List<Boolean> by
                collectValues(underTest.isCameraAutoRotateSettingEnabled(testUser))

            runCurrent()

            assertThat(isCameraAutoRotateSettingEnabled).hasSize(1)
        }

    /** 0 for 0 changes + 1 signal emitted on start => 1 signal */
    @Test
    fun isCameraAutoRotateSettingEnabled_0Times() =
        testScope.runTest {
            settings.putIntForUser(SETTING_NAME, DISABLE, testUser.identifier)
            val isCameraAutoRotateSettingEnabled: List<Boolean> by
                collectValues(underTest.isCameraAutoRotateSettingEnabled(testUser))
            runCurrent()

            settings.putIntForUser(SETTING_NAME, DISABLE, testUser.identifier)
            runCurrent()

            assertThat(isCameraAutoRotateSettingEnabled).hasSize(1)
            assertThat(isCameraAutoRotateSettingEnabled[0]).isFalse()
        }

    /** Maintain that flows are cached by user */
    @Test
    fun sameUserCallsIsCameraAutoRotateSettingEnabledTwice_getsSameFlow() =
        testScope.runTest {
            val flow1 = underTest.isCameraAutoRotateSettingEnabled(testUser)
            val flow2 = underTest.isCameraAutoRotateSettingEnabled(testUser)

            assertThat(flow1).isEqualTo(flow2)
        }

    @Test
    fun differentUsersCallIsCameraAutoRotateSettingEnabled_getDifferentFlow() =
        testScope.runTest {
            val user2 = UserHandle.of(2)
            val flow1 = underTest.isCameraAutoRotateSettingEnabled(testUser)
            val flow2 = underTest.isCameraAutoRotateSettingEnabled(user2)

            assertThat(flow1).isNotEqualTo(flow2)
        }

    private companion object {
        private const val SETTING_NAME = Settings.Secure.CAMERA_AUTOROTATE
        private const val DISABLE = 0
        private const val ENABLE = 1
    }
}
