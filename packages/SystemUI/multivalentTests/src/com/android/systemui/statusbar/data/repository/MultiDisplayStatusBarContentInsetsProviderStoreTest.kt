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

package com.android.systemui.statusbar.data.repository

import android.view.Display.DEFAULT_DISPLAY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class MultiDisplayStatusBarContentInsetsProviderStoreTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val fakeDisplayRepository = kosmos.displayRepository
    private val underTest = kosmos.multiDisplayStatusBarContentInsetsProviderStore

    @Before
    fun start() {
        underTest.start()
    }

    @Before fun addDisplays() = runBlocking { fakeDisplayRepository.addDisplay(DEFAULT_DISPLAY) }

    @Test
    fun forDisplay_startsInstances() =
        testScope.runTest {
            val instance = underTest.forDisplay(DEFAULT_DISPLAY)

            verify(instance).start()
        }

    @Test
    fun beforeDisplayRemoved_doesNotStopInstances() =
        testScope.runTest {
            val instance = underTest.forDisplay(DEFAULT_DISPLAY)

            verify(instance, never()).stop()
        }

    @Test
    fun displayRemoved_stopsInstance() =
        testScope.runTest {
            val instance = underTest.forDisplay(DEFAULT_DISPLAY)

            fakeDisplayRepository.removeDisplay(DEFAULT_DISPLAY)

            verify(instance).stop()
        }
}
