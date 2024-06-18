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

package com.android.systemui.keyguard.data.repository

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth
import kotlin.test.Test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class KeyguardSmartspaceRepositoryImplTest : SysuiTestCase() {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var scope: TestScope

    private lateinit var underTest: KeyguardSmartspaceRepository
    private lateinit var fakeSettings: FakeSettings
    private lateinit var fakeUserTracker: FakeUserTracker

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeSettings = FakeSettings()
        fakeUserTracker = FakeUserTracker()
        fakeSettings.userId = fakeUserTracker.userId
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        scope = TestScope(dispatcher)
        underTest =
            KeyguardSmartspaceRepositoryImpl(
                context = context,
                secureSettings = fakeSettings,
                userTracker = fakeUserTracker,
                applicationScope = scope.backgroundScope,
            )
    }

    @Test
    fun testWeatherEnabled_true() =
        scope.runTest {
            fakeSettings.putInt(Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED, 1)
            val value = collectLastValue(underTest.isWeatherEnabled)
            Truth.assertThat(value()).isEqualTo(true)
        }

    @Test
    fun testWeatherEnabled_false() =
        scope.runTest {
            fakeSettings.putInt(Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED, 0)

            val value = collectLastValue(underTest.isWeatherEnabled)
            Truth.assertThat(value()).isEqualTo(false)
        }
}
