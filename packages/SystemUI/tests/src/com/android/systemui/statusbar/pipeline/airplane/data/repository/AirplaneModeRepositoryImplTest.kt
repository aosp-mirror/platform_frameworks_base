/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.airplane.data.repository

import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings.Global
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class AirplaneModeRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: AirplaneModeRepositoryImpl

    @Mock private lateinit var logger: TableLogBuffer
    private lateinit var bgHandler: Handler
    private lateinit var scope: CoroutineScope
    private lateinit var settings: FakeSettings

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        bgHandler = Handler(Looper.getMainLooper())
        scope = CoroutineScope(IMMEDIATE)
        settings = FakeSettings()
        settings.userId = UserHandle.USER_ALL

        underTest =
            AirplaneModeRepositoryImpl(
                bgHandler,
                settings,
                logger,
                scope,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun isAirplaneMode_initiallyGetsSettingsValue() =
        runBlocking(IMMEDIATE) {
            settings.putInt(Global.AIRPLANE_MODE_ON, 1)

            underTest =
                AirplaneModeRepositoryImpl(
                    bgHandler,
                    settings,
                    logger,
                    scope,
                )

            val job = underTest.isAirplaneMode.launchIn(this)

            assertThat(underTest.isAirplaneMode.value).isTrue()

            job.cancel()
        }

    @Test
    fun isAirplaneMode_settingUpdated_valueUpdated() =
        runBlocking(IMMEDIATE) {
            val job = underTest.isAirplaneMode.launchIn(this)

            settings.putInt(Global.AIRPLANE_MODE_ON, 0)
            yield()
            assertThat(underTest.isAirplaneMode.value).isFalse()

            settings.putInt(Global.AIRPLANE_MODE_ON, 1)
            yield()
            assertThat(underTest.isAirplaneMode.value).isTrue()

            settings.putInt(Global.AIRPLANE_MODE_ON, 0)
            yield()
            assertThat(underTest.isAirplaneMode.value).isFalse()

            job.cancel()
        }
}

private val IMMEDIATE = Dispatchers.Main.immediate
