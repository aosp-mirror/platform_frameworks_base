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

package com.android.systemui.shade.domain.interactor

import android.content.res.Configuration
import android.content.res.mockResources
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.scene.ui.view.mockShadeRootView
import com.android.systemui.shade.data.repository.fakeShadeDisplaysRepository
import com.android.systemui.testKosmos
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class ShadeDisplaysInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val testScope = kosmos.testScope
    private val shadeRootview = kosmos.mockShadeRootView
    private val positionRepository = kosmos.fakeShadeDisplaysRepository
    private val shadeContext = kosmos.mockedWindowContext
    private val resources = kosmos.mockResources
    private val latencyTracker = kosmos.mockedShadeDisplayChangeLatencyTracker
    private val configuration = mock<Configuration>()
    private val display = mock<Display>()

    private val underTest by lazy { kosmos.shadeDisplaysInteractor }

    @Before
    fun setup() {
        whenever(shadeRootview.display).thenReturn(display)
        whenever(display.displayId).thenReturn(0)

        whenever(resources.configuration).thenReturn(configuration)

        whenever(shadeContext.displayId).thenReturn(0)
        whenever(shadeContext.resources).thenReturn(resources)
        whenever(shadeContext.display).thenReturn(display)
    }

    @Test
    fun start_shadeInCorrectPosition_notAddedOrRemoved() {
        whenever(display.displayId).thenReturn(0)
        positionRepository.setDisplayId(0)

        underTest.start()

        verify(shadeContext, never()).reparentToDisplay(any())
    }

    @Test
    fun start_shadeInWrongPosition_changes() {
        whenever(display.displayId).thenReturn(0)
        positionRepository.setDisplayId(1)

        underTest.start()

        verify(shadeContext).reparentToDisplay(eq(1))
    }

    @Test
    fun start_shadeInWrongPosition_logsStartToLatencyTracker() =
        testScope.runTest {
            whenever(display.displayId).thenReturn(0)
            positionRepository.setDisplayId(1)

            underTest.start()
            advanceUntilIdle()

            verify(latencyTracker).onShadeDisplayChanging(eq(1))
        }
}
