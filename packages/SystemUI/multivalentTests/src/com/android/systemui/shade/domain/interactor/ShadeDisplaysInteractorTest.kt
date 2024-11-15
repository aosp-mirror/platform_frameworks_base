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

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.Display
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.FakeDisplayWindowPropertiesRepository
import com.android.systemui.display.shared.model.DisplayWindowProperties
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.data.repository.FakeShadeDisplayRepository
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class ShadeDisplaysInteractorTest : SysuiTestCase() {

    private val shadeRootview = mock<WindowRootView>()
    private val positionRepository = FakeShadeDisplayRepository()
    private val shadeContext = mock<Context>()
    private val contextStore = FakeDisplayWindowPropertiesRepository(context)
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val shadeWm = mock<WindowManager>()
    private val resources = mock<Resources>()
    private val configuration = mock<Configuration>()
    private val display = mock<Display>()

    private val interactor =
        ShadeDisplaysInteractor(
            Optional.of(shadeRootview),
            positionRepository,
            shadeContext,
            shadeWm,
            testScope.backgroundScope,
            testScope.backgroundScope.coroutineContext,
        )

    @Before
    fun setup() {
        whenever(shadeRootview.display).thenReturn(display)
        whenever(display.displayId).thenReturn(0)

        whenever(resources.configuration).thenReturn(configuration)

        whenever(shadeContext.displayId).thenReturn(0)
        whenever(shadeContext.getSystemService(any())).thenReturn(shadeWm)
        whenever(shadeContext.resources).thenReturn(resources)
        contextStore.insert(
            DisplayWindowProperties(
                displayId = 0,
                windowType = TYPE_NOTIFICATION_SHADE,
                context = shadeContext,
                windowManager = shadeWm,
                layoutInflater = mock(),
            )
        )
    }

    @Test
    fun start_shadeInCorrectPosition_notAddedOrRemoved() {
        whenever(display.displayId).thenReturn(0)
        positionRepository.setDisplayId(0)
        interactor.start()
        testScope.advanceUntilIdle()

        verifyNoMoreInteractions(shadeWm)
    }

    @Test
    fun start_shadeInWrongPosition_changes() {
        whenever(display.displayId).thenReturn(0)
        positionRepository.setDisplayId(1)
        interactor.start()

        inOrder(shadeWm).apply {
            verify(shadeWm).removeView(eq(shadeRootview))
            verify(shadeWm).addView(eq(shadeRootview), any())
        }
    }

    @Test
    fun start_shadePositionChanges_removedThenAdded() {
        whenever(display.displayId).thenReturn(0)
        positionRepository.setDisplayId(0)
        interactor.start()

        positionRepository.setDisplayId(1)

        inOrder(shadeWm).apply {
            verify(shadeWm).removeView(eq(shadeRootview))
            verify(shadeWm).addView(eq(shadeRootview), any())
        }
    }
}
