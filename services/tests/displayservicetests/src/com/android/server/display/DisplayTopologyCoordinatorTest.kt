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

import android.hardware.display.DisplayTopology
import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DisplayTopologyCoordinatorTest {
    private lateinit var coordinator: DisplayTopologyCoordinator
    private val displayInfo = DisplayInfo()
    private val topologyChangeExecutor = Runnable::run

    private val mockTopology = mock<DisplayTopology>()
    private val mockTopologyCopy = mock<DisplayTopology>()
    private val mockIsExtendedDisplayEnabled = mock<() -> Boolean>()
    private val mockTopologyChangedCallback = mock<(DisplayTopology) -> Unit>()

    @Before
    fun setUp() {
        displayInfo.displayId = 2
        displayInfo.logicalWidth = 300
        displayInfo.logicalHeight = 200
        displayInfo.logicalDensityDpi = 100

        val injector = object : DisplayTopologyCoordinator.Injector() {
            override fun getTopology() = mockTopology
        }
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(true)
        whenever(mockTopology.copy()).thenReturn(mockTopologyCopy)
        coordinator = DisplayTopologyCoordinator(injector, mockIsExtendedDisplayEnabled,
            mockTopologyChangedCallback, topologyChangeExecutor, DisplayManagerService.SyncRoot())
    }

    @Test
    fun addDisplay() {
        coordinator.onDisplayAdded(displayInfo)

        val widthDp = displayInfo.logicalWidth * (DisplayMetrics.DENSITY_DEFAULT.toFloat()
                / displayInfo.logicalDensityDpi)
        val heightDp = displayInfo.logicalHeight * (DisplayMetrics.DENSITY_DEFAULT.toFloat()
                / displayInfo.logicalDensityDpi)
        verify(mockTopology).addDisplay(displayInfo.displayId, widthDp, heightDp)
        verify(mockTopologyChangedCallback).invoke(mockTopologyCopy)
    }

    @Test
    fun addDisplay_extendedDisplaysDisabled() {
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(false)

        coordinator.onDisplayAdded(displayInfo)

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun addDisplay_notInDefaultDisplayGroup() {
        displayInfo.displayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1

        coordinator.onDisplayAdded(displayInfo)

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun getTopology_copy() {
        assertThat(coordinator.topology).isEqualTo(mockTopologyCopy)
    }

    @Test
    fun setTopology_normalize() {
        val topology = mock<DisplayTopology>()
        val topologyCopy = mock<DisplayTopology>()
        whenever(topology.copy()).thenReturn(topologyCopy)

        coordinator.topology = topology

        verify(topology).normalize()
        verify(mockTopologyChangedCallback).invoke(topologyCopy)
    }
}