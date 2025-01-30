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
import android.view.Display
import android.view.mockIWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.data.repository.fakeLightBarControllerStore
import com.android.systemui.statusbar.data.repository.fakePrivacyDotWindowControllerStore
import com.android.systemui.testKosmos
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class MultiDisplayStatusBarStarterTest : SysuiTestCase() {
    @get:Rule val expect: Expect = Expect.create()

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fakeDisplayRepository = kosmos.displayRepository
    private val fakeOrchestratorFactory = kosmos.fakeStatusBarOrchestratorFactory
    private val fakeInitializerStore = kosmos.fakeStatusBarInitializerStore
    private val fakePrivacyDotStore = kosmos.fakePrivacyDotWindowControllerStore
    private val fakeLightBarStore = kosmos.fakeLightBarControllerStore
    private val windowManager = kosmos.mockIWindowManager

    // Lazy, so that @EnableFlags is set before initializer is instantiated.
    private val underTest by lazy { kosmos.multiDisplayStatusBarStarter }

    @Before
    fun setup() {
        whenever(windowManager.shouldShowSystemDecors(Display.DEFAULT_DISPLAY)).thenReturn(true)
        whenever(windowManager.shouldShowSystemDecors(DISPLAY_1)).thenReturn(true)
        whenever(windowManager.shouldShowSystemDecors(DISPLAY_2)).thenReturn(true)
        whenever(windowManager.shouldShowSystemDecors(DISPLAY_3)).thenReturn(true)
        whenever(windowManager.shouldShowSystemDecors(DISPLAY_4_NO_SYSTEM_DECOR)).thenReturn(false)
    }

    @Test
    fun start_startsInitializersForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_1)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_2)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)

            underTest.start()
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = DISPLAY_1).startedByCoreStartable)
                .isTrue()
            expect
                .that(fakeInitializerStore.forDisplay(displayId = DISPLAY_2).startedByCoreStartable)
                .isTrue()
            expect
                .that(
                    fakeInitializerStore
                        .forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
                        .startedByCoreStartable
                )
                .isFalse()
        }

    @Test
    fun start_startsOrchestratorForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_1)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_2)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)

            underTest.start()
            runCurrent()

            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = DISPLAY_1)!!)
                .start()
            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = DISPLAY_2)!!)
                .start()
            assertThat(
                    fakeOrchestratorFactory.createdOrchestratorForDisplay(
                        displayId = DISPLAY_4_NO_SYSTEM_DECOR
                    )
                )
                .isNull()
        }

    @Test
    fun start_startsPrivacyDotForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_1)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_2)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)

            underTest.start()
            runCurrent()

            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_1)).start()
            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_2)).start()
            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR), never())
                .start()
        }

    @Test
    fun start_doesNotStartLightBarControllerForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_1)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_2)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)

            underTest.start()
            runCurrent()

            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_1), never()).start()
            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_2), never()).start()
            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR), never())
                .start()
        }

    @Test
    fun start_createsLightBarControllerForCurrentDisplays() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_1)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_2)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)

            underTest.start()
            runCurrent()

            assertThat(fakeLightBarStore.perDisplayMocks.keys).containsExactly(1, DISPLAY_2)
        }

    @Test
    fun start_doesNotStartPrivacyDotForDefaultDisplay() =
        testScope.runTest {
            fakeDisplayRepository.addDisplay(displayId = Display.DEFAULT_DISPLAY)

            underTest.start()
            runCurrent()

            verify(fakePrivacyDotStore.forDisplay(displayId = Display.DEFAULT_DISPLAY), never())
                .start()
        }

    @Test
    fun displayAdded_orchestratorForNewDisplay() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = DISPLAY_3)!!)
                .start()
            assertThat(
                    fakeOrchestratorFactory.createdOrchestratorForDisplay(
                        displayId = DISPLAY_4_NO_SYSTEM_DECOR
                    )
                )
                .isNull()
        }

    @Test
    fun displayAdded_initializerForNewDisplay() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = DISPLAY_3).startedByCoreStartable)
                .isTrue()
            expect
                .that(
                    fakeInitializerStore
                        .forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
                        .startedByCoreStartable
                )
                .isFalse()
        }

    @Test
    fun displayAdded_privacyDotForNewDisplay() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_3)).start()
            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR), never())
                .start()
        }

    @Test
    fun displayAdded_lightBarForNewDisplayCreate() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            assertThat(fakeLightBarStore.perDisplayMocks.keys).containsExactly(DISPLAY_3)
        }

    @Test
    fun displayAdded_lightBarForNewDisplayStart() =
        testScope.runTest {
            underTest.start()
            runCurrent()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_3), never()).start()
            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR), never())
                .start()
        }

    @Test
    fun displayAddedDuringStart_initializerForNewDisplay() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            expect
                .that(fakeInitializerStore.forDisplay(displayId = DISPLAY_3).startedByCoreStartable)
                .isTrue()
            expect
                .that(
                    fakeInitializerStore
                        .forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
                        .startedByCoreStartable
                )
                .isFalse()
        }

    @Test
    fun displayAddedDuringStart_orchestratorForNewDisplay() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            verify(fakeOrchestratorFactory.createdOrchestratorForDisplay(displayId = DISPLAY_3)!!)
                .start()
            assertThat(
                    fakeOrchestratorFactory.createdOrchestratorForDisplay(
                        displayId = DISPLAY_4_NO_SYSTEM_DECOR
                    )
                )
                .isNull()
        }

    @Test
    fun displayAddedDuringStart_privacyDotForNewDisplay() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_3)).start()
            verify(fakePrivacyDotStore.forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR), never())
                .start()
        }

    @Test
    fun displayAddedDuringStart_lightBarForNewDisplayCreate() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            assertThat(fakeLightBarStore.perDisplayMocks.keys).containsExactly(DISPLAY_3)
        }

    @Test
    fun displayAddedDuringStart_lightBarForNewDisplayStart() =
        testScope.runTest {
            underTest.start()

            fakeDisplayRepository.addDisplay(displayId = DISPLAY_3)
            fakeDisplayRepository.addDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR)
            runCurrent()

            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_3), never()).start()
            verify(fakeLightBarStore.forDisplay(displayId = DISPLAY_4_NO_SYSTEM_DECOR), never())
                .start()
        }

    companion object {
        const val DISPLAY_1 = 1
        const val DISPLAY_2 = 2
        const val DISPLAY_3 = 3
        const val DISPLAY_4_NO_SYSTEM_DECOR = 4
    }
}
