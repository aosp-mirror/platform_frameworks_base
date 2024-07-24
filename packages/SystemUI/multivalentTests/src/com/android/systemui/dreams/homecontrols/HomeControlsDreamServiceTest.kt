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
package com.android.systemui.dreams.homecontrols

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.core.FakeLogBuffer.Factory.Companion.create
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import java.util.Optional
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeControlsDreamServiceTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var taskFragmentComponentFactory: TaskFragmentComponent.Factory
    @Mock private lateinit var taskFragmentComponent: TaskFragmentComponent
    @Mock private lateinit var activity: Activity

    private lateinit var underTest: HomeControlsDreamService

    @Before
    fun setup() =
        with(kosmos) {
            MockitoAnnotations.initMocks(this@HomeControlsDreamServiceTest)
            whenever(taskFragmentComponentFactory.create(any(), any(), any(), any()))
                .thenReturn(taskFragmentComponent)

            whenever(controlsComponent.getControlsListingController())
                .thenReturn(Optional.of(controlsListingController))

            underTest = buildService { activity }
        }

    @Test
    fun testOnAttachedToWindowCreatesTaskFragmentComponent() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            verify(taskFragmentComponentFactory).create(any(), any(), any(), any())
        }

    @Test
    fun testOnDetachedFromWindowDestroyTaskFragmentComponent() =
        testScope.runTest {
            underTest.onAttachedToWindow()
            underTest.onDetachedFromWindow()
            verify(taskFragmentComponent).destroy()
        }

    @Test
    fun testNotCreatingTaskFragmentComponentWhenActivityIsNull() =
        testScope.runTest {
            underTest = buildService { null }

            underTest.onAttachedToWindow()
            verify(taskFragmentComponentFactory, never()).create(any(), any(), any(), any())
        }

    private fun buildService(activityProvider: DreamActivityProvider): HomeControlsDreamService =
        with(kosmos) {
            return HomeControlsDreamService(
                controlsSettingsRepository = FakeControlsSettingsRepository(),
                taskFragmentFactory = taskFragmentComponentFactory,
                homeControlsComponentInteractor = homeControlsComponentInteractor,
                dreamActivityProvider = activityProvider,
                bgDispatcher = testDispatcher,
                logBuffer = logcatLogBuffer("HomeControlsDreamServiceTest")
            )
        }
}
