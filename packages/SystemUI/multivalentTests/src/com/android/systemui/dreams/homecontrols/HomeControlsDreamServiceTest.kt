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
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.FakeLogBuffer.Factory.Companion.create
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import java.util.Optional
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

    private lateinit var controlsSettingsRepository: FakeControlsSettingsRepository
    @Mock private lateinit var taskFragmentComponentFactory: TaskFragmentComponent.Factory
    @Mock private lateinit var taskFragmentComponent: TaskFragmentComponent
    @Mock private lateinit var activity: Activity
    private val logBuffer: LogBuffer = create()

    private lateinit var underTest: HomeControlsDreamService
    private lateinit var homeControlsComponentInteractor: HomeControlsComponentInteractor
    private lateinit var fakeDreamActivityProvider: DreamActivityProvider
    private lateinit var controlsComponent: ControlsComponent
    private lateinit var controlsListingController: ControlsListingController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(taskFragmentComponentFactory.create(any(), any(), any(), any()))
            .thenReturn(taskFragmentComponent)

        controlsSettingsRepository = FakeControlsSettingsRepository()
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)

        controlsComponent = kosmos.controlsComponent
        controlsListingController = kosmos.controlsListingController

        whenever(controlsComponent.getControlsListingController())
            .thenReturn(Optional.of(controlsListingController))

        homeControlsComponentInteractor = kosmos.homeControlsComponentInteractor

        fakeDreamActivityProvider = DreamActivityProvider { activity }
        underTest =
            HomeControlsDreamService(
                controlsSettingsRepository,
                taskFragmentComponentFactory,
                homeControlsComponentInteractor,
                fakeDreamActivityProvider,
                logBuffer
            )
    }

    @Test
    fun testOnAttachedToWindowCreatesTaskFragmentComponent() {
        underTest.onAttachedToWindow()
        verify(taskFragmentComponentFactory).create(any(), any(), any(), any())
    }

    @Test
    fun testOnDetachedFromWindowDestroyTaskFragmentComponent() {
        underTest.onAttachedToWindow()
        underTest.onDetachedFromWindow()
        verify(taskFragmentComponent).destroy()
    }

    @Test
    fun testNotCreatingTaskFragmentComponentWhenActivityIsNull() {
        fakeDreamActivityProvider = DreamActivityProvider { null }
        underTest =
            HomeControlsDreamService(
                controlsSettingsRepository,
                taskFragmentComponentFactory,
                homeControlsComponentInteractor,
                fakeDreamActivityProvider,
                logBuffer
            )

        underTest.onAttachedToWindow()
        verify(taskFragmentComponentFactory, never()).create(any(), any(), any(), any())
    }

    companion object {
        private const val TEST_PACKAGE_PANEL = "pkg.panel"
        private val TEST_COMPONENT_PANEL = ComponentName(TEST_PACKAGE_PANEL, "service")
    }
}
