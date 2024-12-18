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

package com.android.systemui.qs.tiles.impl.night.domain.interactor

import android.hardware.display.ColorDisplayManager
import android.hardware.display.NightDisplayListener
import android.os.UserHandle
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.NightDisplayRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dagger.NightDisplayListenerModule
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.user.utils.UserScopedService
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeGlobalSettings
import com.android.systemui.util.settings.fakeSettings
import com.android.systemui.util.time.DateFormatUtil
import com.android.systemui.utils.leaks.FakeLocationController
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt

@SmallTest
@RunWith(AndroidJUnit4::class)
class NightDisplayTileDataInteractorTest : SysuiTestCase() {
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
            whenever(setUser(anyInt())).thenReturn(this)
            whenever(build()).thenReturn(nightDisplayListener)
        }
    private val globalSettings = kosmos.fakeGlobalSettings
    private val secureSettings = kosmos.fakeSettings
    private val dateFormatUtil = mock<DateFormatUtil> { whenever(is24HourFormat).thenReturn(false) }
    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val userScopedColorDisplayManager =
        mock<UserScopedService<ColorDisplayManager>> {
            whenever(forUser(eq(testUser))).thenReturn(colorDisplayManager)
        }
    private val nightDisplayRepository =
        NightDisplayRepository(
            testDispatcher,
            scope.backgroundScope,
            globalSettings,
            secureSettings,
            listenerBuilder,
            userScopedColorDisplayManager,
            locationController,
        )

    private val underTest: NightDisplayTileDataInteractor =
        NightDisplayTileDataInteractor(context, dateFormatUtil, nightDisplayRepository)

    @Test
    fun availability_matchesColorDisplayManager() = runTest {
        val availability by collectLastValue(underTest.availability(testUser))

        val expectedAvailability = ColorDisplayManager.isNightDisplayAvailable(context)
        assertThat(availability).isEqualTo(expectedAvailability)
    }
}
