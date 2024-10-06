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

package com.android.server.display

import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayInfo
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.function.BooleanSupplier

class DisplayTopologyCoordinatorTest {
    private lateinit var coordinator: DisplayTopologyCoordinator
    private val displayInfo = DisplayInfo()

    private val mockTopology = mock<DisplayTopology>()
    private val mockIsExtendedDisplayEnabled = mock<BooleanSupplier>()

    @Before
    fun setUp() {
        displayInfo.displayId = 2
        displayInfo.logicalWidth = 300
        displayInfo.logicalHeight = 200
        displayInfo.logicalDensityDpi = 100

        val injector = object : DisplayTopologyCoordinator.Injector() {
            override fun getTopology() = mockTopology
        }
        coordinator = DisplayTopologyCoordinator(injector, mockIsExtendedDisplayEnabled)
    }

    @Test
    fun addDisplay() {
        whenever(mockIsExtendedDisplayEnabled.asBoolean).thenReturn(true)

        coordinator.onDisplayAdded(displayInfo)

        val widthDp = displayInfo.logicalWidth * (DisplayMetrics.DENSITY_DEFAULT.toDouble()
                / displayInfo.logicalDensityDpi)
        val heightDp = displayInfo.logicalHeight * (DisplayMetrics.DENSITY_DEFAULT.toDouble()
                / displayInfo.logicalDensityDpi)
        verify(mockTopology).addDisplay(displayInfo.displayId, widthDp, heightDp)
    }

    @Test
    fun addDisplay_extendedDisplaysDisabled() {
        whenever(mockIsExtendedDisplayEnabled.asBoolean).thenReturn(false)

        coordinator.onDisplayAdded(displayInfo)

        verify(mockTopology, never()).addDisplay(anyInt(), anyDouble(), anyDouble())
    }

    @Test
    fun addDisplay_notInDefaultDisplayGroup() {
        whenever(mockIsExtendedDisplayEnabled.asBoolean).thenReturn(true)
        displayInfo.displayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1

        coordinator.onDisplayAdded(displayInfo)

        verify(mockTopology, never()).addDisplay(anyInt(), anyDouble(), anyDouble())
    }
}