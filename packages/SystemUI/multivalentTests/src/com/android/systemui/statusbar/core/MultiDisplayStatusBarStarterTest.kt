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

package com.android.systemui.statusbar.core

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Expect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class MultiDisplayStatusBarStarterTest : SysuiTestCase() {
    @get:Rule val expect: Expect = Expect.create()

    private val kosmos =
        testKosmos().also {
            it.statusBarOrchestratorFactory = it.fakeStatusBarOrchestratorFactory
            it.statusBarInitializerStore = it.fakeStatusBarInitializerStore
        }
    private val testScope = kosmos.testScope
    private val fakeDisplayRepository = kosmos.displayRepository
    private val fakeOrchestratorFactory = kosmos.fakeStatusBarOrchestratorFactory
    private val fakeInitializerStore = kosmos.fakeStatusBarInitializerStore

    // Lazy, so that @EnableFlags is set before initializer is instantiated.
    private val underTest by lazy { kosmos.multiDisplayStatusBarStarter }

    @Test
    fun start_startsInitializersForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = 1)
            fakeDisplayRepository.addDisplay(displayId = 2)

            underTest.start()
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = 1).startedByCoreStartable)
                .isTrue()
            expect
                .that(fakeInitializerStore.forDisplay(displayId = 2).startedByCoreStartable)
                .isTrue()
        }

    @Test
    fun start_startsOrchestratorForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = 1)
            fakeDisplayRepository.addDisplay(displayId = 2)

            underTest.start()
            runCurrent()

            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = 1)!!).start()
            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = 2)!!).start()
        }

    @Test
    fun displayAdded_orchestratorForNewDisplayIsStarted() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = 3)
            runCurrent()

            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = 3)!!).start()
        }

    @Test
    fun displayAdded_initializerForNewDisplayIsStarted() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = 3)
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = 3).startedByCoreStartable)
                .isTrue()
        }

    @Test
    fun displayAddedDuringStart_initializerForNewDisplayIsStarted() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = 3)
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = 3).startedByCoreStartable)
                .isTrue()
        }

    @Test
    fun displayAddedDuringStart_orchestratorForNewDisplayIsStarted() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = 3)
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = 3).startedByCoreStartable)
                .isTrue()
        }
}
