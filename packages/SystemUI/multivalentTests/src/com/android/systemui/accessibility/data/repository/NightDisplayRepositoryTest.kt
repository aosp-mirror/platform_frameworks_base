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

package com.android.systemui.accessibility.data.repository

import android.hardware.display.ColorDisplayManager
import android.hardware.display.NightDisplayListener
import android.os.UserHandle
import android.provider.Settings
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dagger.NightDisplayListenerModule
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.user.utils.UserScopedService
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeGlobalSettings
import com.android.systemui.util.settings.fakeSettings
import com.android.systemui.utils.leaks.FakeLocationController
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NightDisplayRepositoryTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testUser = UserHandle.of(1)!!
    private val testStartTime = LocalTime.MIDNIGHT
    private val testEndTime = LocalTime.NOON
    private val colorDisplayManager =
        mock<ColorDisplayManager> {
            whenever(nightDisplayAutoMode).thenReturn(ColorDisplayManager.AUTO_MODE_DISABLED)
            whenever(isNightDisplayActivated).thenReturn(false)
            whenever(nightDisplayCustomStartTime).thenReturn(testStartTime)
            whenever(nightDisplayCustomEndTime).thenReturn(testEndTime)
        }
    private val locationController = FakeLocationController(LeakCheck())
    private val nightDisplayListener = mock<NightDisplayListener>()
    private val listenerBuilder =
        mock<NightDisplayListenerModule.Builder> {
            whenever(setUser(ArgumentMatchers.anyInt())).thenReturn(this)
            whenever(build()).thenReturn(nightDisplayListener)
        }
    private val globalSettings = kosmos.fakeGlobalSettings
    private val secureSettings = kosmos.fakeSettings
    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val userScopedColorDisplayManager =
        mock<UserScopedService<ColorDisplayManager>> {
            whenever(forUser(eq(testUser))).thenReturn(colorDisplayManager)
        }

    private val underTest =
        NightDisplayRepository(
            testDispatcher,
            scope.backgroundScope,
            globalSettings,
            secureSettings,
            listenerBuilder,
            userScopedColorDisplayManager,
            locationController,
        )

    @Before
    fun setup() {
        enrollInForcedNightDisplayAutoMode(INITIALLY_FORCE_AUTO_MODE, testUser)
    }

    @Test
    fun nightDisplayState_matchesAutoMode() =
        scope.runTest {
            enrollInForcedNightDisplayAutoMode(INITIALLY_FORCE_AUTO_MODE, testUser)
            val callbackCaptor = argumentCaptor<NightDisplayListener.Callback>()
            val lastState by collectLastValue(underTest.nightDisplayState(testUser))

            runCurrent()

            verify(nightDisplayListener).setCallback(callbackCaptor.capture())
            val callback = callbackCaptor.value

            assertThat(lastState!!.autoMode).isEqualTo(ColorDisplayManager.AUTO_MODE_DISABLED)

            callback.onAutoModeChanged(ColorDisplayManager.AUTO_MODE_CUSTOM_TIME)
            assertThat(lastState!!.autoMode).isEqualTo(ColorDisplayManager.AUTO_MODE_CUSTOM_TIME)

            callback.onCustomStartTimeChanged(testStartTime)
            assertThat(lastState!!.startTime).isEqualTo(testStartTime)

            callback.onCustomEndTimeChanged(testEndTime)
            assertThat(lastState!!.endTime).isEqualTo(testEndTime)

            callback.onAutoModeChanged(ColorDisplayManager.AUTO_MODE_TWILIGHT)

            assertThat(lastState!!.autoMode).isEqualTo(ColorDisplayManager.AUTO_MODE_TWILIGHT)
        }

    @Test
    fun nightDisplayState_matchesIsNightDisplayActivated() =
        scope.runTest {
            val callbackCaptor = argumentCaptor<NightDisplayListener.Callback>()

            val lastState by collectLastValue(underTest.nightDisplayState(testUser))
            runCurrent()

            verify(nightDisplayListener).setCallback(callbackCaptor.capture())
            val callback = callbackCaptor.value
            assertThat(lastState!!.isActivated)
                .isEqualTo(colorDisplayManager.isNightDisplayActivated)

            callback.onActivated(true)
            assertThat(lastState!!.isActivated).isTrue()

            callback.onActivated(false)
            assertThat(lastState!!.isActivated).isFalse()
        }

    @Test
    fun nightDisplayState_matchesController_initiallyCustomAutoMode() =
        scope.runTest {
            whenever(colorDisplayManager.nightDisplayAutoMode)
                .thenReturn(ColorDisplayManager.AUTO_MODE_CUSTOM_TIME)

            val lastState by collectLastValue(underTest.nightDisplayState(testUser))
            runCurrent()

            assertThat(lastState!!.autoMode).isEqualTo(ColorDisplayManager.AUTO_MODE_CUSTOM_TIME)
        }

    @Test
    fun nightDisplayState_matchesController_initiallyTwilightAutoMode() =
        scope.runTest {
            whenever(colorDisplayManager.nightDisplayAutoMode)
                .thenReturn(ColorDisplayManager.AUTO_MODE_TWILIGHT)

            val lastState by collectLastValue(underTest.nightDisplayState(testUser))
            runCurrent()

            assertThat(lastState!!.autoMode).isEqualTo(ColorDisplayManager.AUTO_MODE_TWILIGHT)
        }

    @Test
    fun nightDisplayState_matchesForceAutoMode() =
        scope.runTest {
            enrollInForcedNightDisplayAutoMode(false, testUser)
            val lastState by collectLastValue(underTest.nightDisplayState(testUser))
            runCurrent()

            assertThat(lastState!!.shouldForceAutoMode).isEqualTo(false)

            enrollInForcedNightDisplayAutoMode(true, testUser)
            assertThat(lastState!!.shouldForceAutoMode).isEqualTo(true)
        }

    private fun enrollInForcedNightDisplayAutoMode(enroll: Boolean, userHandle: UserHandle) {
        globalSettings.putString(
            Settings.Global.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE,
            if (enroll) NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE
            else NIGHT_DISPLAY_FORCED_AUTO_MODE_UNAVAILABLE
        )
        secureSettings.putIntForUser(
            Settings.Secure.NIGHT_DISPLAY_AUTO_MODE,
            if (enroll) NIGHT_DISPLAY_AUTO_MODE_RAW_NOT_SET else NIGHT_DISPLAY_AUTO_MODE_RAW_SET,
            userHandle.identifier
        )
    }

    private companion object {
        const val INITIALLY_FORCE_AUTO_MODE = false
        const val NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE = "1"
        const val NIGHT_DISPLAY_FORCED_AUTO_MODE_UNAVAILABLE = "0"
        const val NIGHT_DISPLAY_AUTO_MODE_RAW_NOT_SET = -1
        const val NIGHT_DISPLAY_AUTO_MODE_RAW_SET = 0
    }
}
