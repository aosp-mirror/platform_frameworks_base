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
import android.hardware.display.DisplayTopology.pxToDp
import android.hardware.display.DisplayTopologyGraph
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.Display
import android.view.DisplayInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DisplayTopologyCoordinatorTest {
    private lateinit var coordinator: DisplayTopologyCoordinator
    private lateinit var displayInfos: List<DisplayInfo>
    private val topologyChangeExecutor = Runnable::run

    private val mockTopologyStore = mock<DisplayTopologyStore>()
    private val mockTopology = mock<DisplayTopology>()
    private val mockTopologyCopy = mock<DisplayTopology>()
    private val mockTopologyGraph = mock<DisplayTopologyGraph>()
    private val mockIsExtendedDisplayEnabled = mock<() -> Boolean>()
    private val mockTopologySavedCallback = mock<() -> Unit>()
    private val mockTopologyChangedCallback =
        mock<(android.util.Pair<DisplayTopology, DisplayTopologyGraph>) -> Unit>()

    @Before
    fun setUp() {
        displayInfos = (1..10).map { i ->
            val info = DisplayInfo()
            info.displayId = i
            info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP
            info.logicalWidth = i * 300
            info.logicalHeight = i * 200
            info.logicalDensityDpi = i * 100
            info.type = Display.TYPE_EXTERNAL
            return@map info
        }

        val injector = object : DisplayTopologyCoordinator.Injector() {
            override fun getTopology() = mockTopology
            override fun createTopologyStore(
                displayIdToUniqueId: SparseArray<String>,
                uniqueIdToDisplayId: MutableMap<String, Int>
            ) =
                mockTopologyStore
        }
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(true)
        whenever(mockTopology.copy()).thenReturn(mockTopologyCopy)
        whenever(mockTopologyCopy.getGraph(any())).thenReturn(mockTopologyGraph)
        coordinator = DisplayTopologyCoordinator(injector, mockIsExtendedDisplayEnabled,
            mockTopologyChangedCallback, topologyChangeExecutor, DisplayManagerService.SyncRoot(),
            mockTopologySavedCallback)
    }

    @Test
    fun addDisplay() {
        displayInfos.forEachIndexed { i, displayInfo ->
            coordinator.onDisplayAdded(displayInfo)

            val widthDp = pxToDp(displayInfo.logicalWidth.toFloat(), displayInfo.logicalDensityDpi)
            val heightDp =
                pxToDp(displayInfo.logicalHeight.toFloat(), displayInfo.logicalDensityDpi)
            verify(mockTopology).addDisplay(displayInfo.displayId, widthDp, heightDp)
        }

        val captor = ArgumentCaptor.forClass(SparseIntArray::class.java)
        verify(mockTopologyCopy, times(displayInfos.size)).getGraph(captor.capture())
        val densities = captor.value
        assertThat(densities.size()).isEqualTo(displayInfos.size)
        for (displayInfo in displayInfos) {
            assertThat(densities.get(displayInfo.displayId))
                .isEqualTo(displayInfo.logicalDensityDpi)
        }

        verify(mockTopologyChangedCallback, times(displayInfos.size)).invoke(
            android.util.Pair(
                mockTopologyCopy,
                mockTopologyGraph
            )
        )
        verify(mockTopologyStore, times(displayInfos.size)).restoreTopology(mockTopology)

        // Clear invocations for other tests that call this method
        clearInvocations(mockTopologyCopy)
        clearInvocations(mockTopologyChangedCallback)
        clearInvocations(mockTopologyStore)
    }

    @Test
    fun addDisplay_internal() {
        displayInfos[0].displayId = Display.DEFAULT_DISPLAY
        displayInfos[0].type = Display.TYPE_INTERNAL
        coordinator.onDisplayAdded(displayInfos[0])

        val widthDp =
            pxToDp(displayInfos[0].logicalWidth.toFloat(), displayInfos[0].logicalDensityDpi)
        val heightDp =
            pxToDp(displayInfos[0].logicalHeight.toFloat(), displayInfos[0].logicalDensityDpi)
        verify(mockTopology).addDisplay(displayInfos[0].displayId, widthDp, heightDp)
        verify(mockTopologyChangedCallback).invoke(
            android.util.Pair(
                mockTopologyCopy,
                mockTopologyGraph
            )
        )
    }

    @Test
    fun addDisplay_overlay() {
        displayInfos[0].type = Display.TYPE_OVERLAY
        coordinator.onDisplayAdded(displayInfos[0])

        val widthDp =
            pxToDp(displayInfos[0].logicalWidth.toFloat(), displayInfos[0].logicalDensityDpi)
        val heightDp =
            pxToDp(displayInfos[0].logicalHeight.toFloat(), displayInfos[0].logicalDensityDpi)
        verify(mockTopology).addDisplay(displayInfos[0].displayId, widthDp, heightDp)
        verify(mockTopologyChangedCallback).invoke(
            android.util.Pair(
                mockTopologyCopy,
                mockTopologyGraph
            )
        )
    }

    @Test
    fun addDisplay_typeUnknown() {
        displayInfos[0].type = Display.TYPE_UNKNOWN

        coordinator.onDisplayAdded(displayInfos[0])

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun addDisplay_wifi() {
        displayInfos[0].type = Display.TYPE_WIFI

        coordinator.onDisplayAdded(displayInfos[0])

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun addDisplay_virtual() {
        displayInfos[0].type = Display.TYPE_VIRTUAL

        coordinator.onDisplayAdded(displayInfos[0])

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun addDisplay_internal_nonDefault() {
        displayInfos[0].displayId = 2
        displayInfos[0].type = Display.TYPE_INTERNAL

        coordinator.onDisplayAdded(displayInfos[0])

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun addDisplay_external_extendedDisplaysDisabled() {
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(false)

        for (displayInfo in displayInfos) {
            coordinator.onDisplayAdded(displayInfo)
        }

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun addDisplay_overlay_extendedDisplaysDisabled() {
        displayInfos[0].type = Display.TYPE_OVERLAY
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(false)

        for (displayInfo in displayInfos) {
            coordinator.onDisplayAdded(displayInfo)
        }

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
        verify(mockTopologyStore, never()).restoreTopology(any())
    }

    @Test
    fun addDisplay_notInDefaultDisplayGroup() {
        displayInfos[0].displayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1

        coordinator.onDisplayAdded(displayInfos[0])

        verify(mockTopology, never()).addDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
        verify(mockTopologyStore, never()).restoreTopology(any())
    }

    @Test
    fun updateDisplay() {
        whenever(mockTopology.updateDisplay(eq(displayInfos[0].displayId), anyFloat(), anyFloat()))
            .thenReturn(true)
        addDisplay()

        displayInfos[0].logicalWidth += 100
        displayInfos[0].logicalHeight += 100
        coordinator.onDisplayChanged(displayInfos[0])

        val widthDp =
            pxToDp(displayInfos[0].logicalWidth.toFloat(), displayInfos[0].logicalDensityDpi)
        val heightDp =
            pxToDp(displayInfos[0].logicalHeight.toFloat(), displayInfos[0].logicalDensityDpi)
        verify(mockTopology).updateDisplay(displayInfos[0].displayId, widthDp, heightDp)

        val captor = ArgumentCaptor.forClass(SparseIntArray::class.java)
        verify(mockTopologyCopy).getGraph(captor.capture())
        val densities = captor.value
        assertThat(densities.size()).isEqualTo(displayInfos.size)
        assertThat(densities.get(displayInfos[0].displayId))
            .isEqualTo(displayInfos[0].logicalDensityDpi)

        verify(mockTopologyChangedCallback).invoke(
            android.util.Pair(
                mockTopologyCopy,
                mockTopologyGraph
            )
        )
    }

    @Test
    fun updateDisplay_notChanged() {
        addDisplay()

        for (displayInfo in displayInfos) {
            coordinator.onDisplayChanged(displayInfo)
        }

        // Try to update a display that does not exist
        val info = DisplayInfo()
        info.displayId = 100
        coordinator.onDisplayChanged(info)

        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun updateDisplay_typeUnknown() {
        displayInfos[0].type = Display.TYPE_UNKNOWN

        coordinator.onDisplayChanged(displayInfos[0])

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun updateDisplay_wifi() {
        displayInfos[0].type = Display.TYPE_WIFI

        coordinator.onDisplayChanged(displayInfos[0])

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun updateDisplay_virtual() {
        displayInfos[0].type = Display.TYPE_VIRTUAL

        coordinator.onDisplayChanged(displayInfos[0])

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun updateDisplay_internal_nonDefault() {
        displayInfos[0].displayId = 2
        displayInfos[0].type = Display.TYPE_INTERNAL

        coordinator.onDisplayChanged(displayInfos[0])

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun updateDisplay_external_extendedDisplaysDisabled() {
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(false)

        for (displayInfo in displayInfos) {
            coordinator.onDisplayChanged(displayInfo)
        }

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun updateDisplay_overlay_extendedDisplaysDisabled() {
        displayInfos[0].type = Display.TYPE_OVERLAY
        whenever(mockIsExtendedDisplayEnabled()).thenReturn(false)

        coordinator.onDisplayChanged(displayInfos[0])

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyChangedCallback, never()).invoke(any())
        verify(mockTopologyStore, never()).restoreTopology(any())
    }

    @Test
    fun updateDisplay_notInDefaultDisplayGroup() {
        displayInfos[0].displayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1

        coordinator.onDisplayChanged(displayInfos[0])

        verify(mockTopology, never()).updateDisplay(anyInt(), anyFloat(), anyFloat())
        verify(mockTopologyCopy, never()).getGraph(any())
        verify(mockTopologyChangedCallback, never()).invoke(any())
    }

    @Test
    fun removeDisplay() {
        addDisplay()

        val displaysToRemove = listOf(0, 2, 3).map { displayInfos[it] }
        for (displayInfo in displaysToRemove) {
            whenever(mockTopology.removeDisplay(displayInfo.displayId)).thenReturn(true)

            coordinator.onDisplayRemoved(displayInfo.displayId)
        }

        val captor = ArgumentCaptor.forClass(SparseIntArray::class.java)
        verify(mockTopologyCopy, times(displaysToRemove.size)).getGraph(captor.capture())
        val densities = captor.value
        assertThat(densities.size()).isEqualTo(displayInfos.size - displaysToRemove.size)
        for (displayInfo in displaysToRemove) {
            assertThat(densities.get(displayInfo.displayId)).isEqualTo(0)
        }

        verify(mockTopologyChangedCallback, times(displaysToRemove.size)).invoke(
            android.util.Pair(
                mockTopologyCopy,
                mockTopologyGraph
            )
        )
        verify(mockTopologyStore, times(displaysToRemove.size)).restoreTopology(mockTopology)
    }

    @Test
    fun removeDisplay_notChanged() {
        coordinator.onDisplayRemoved(100)

        verify(mockTopologyChangedCallback, never()).invoke(any())
        verify(mockTopologyStore, never()).restoreTopology(any())
    }

    @Test
    fun getTopology_copy() {
        assertThat(coordinator.topology).isEqualTo(mockTopologyCopy)
    }

    @Test
    fun setTopology_normalize() {
        val topology = mock<DisplayTopology>()
        val topologyCopy = mock<DisplayTopology>()
        val topologyGraph = mock<DisplayTopologyGraph>()
        whenever(topology.copy()).thenReturn(topologyCopy)
        whenever(topologyCopy.getGraph(any())).thenReturn(topologyGraph)
        whenever(mockTopologyStore.saveTopology(topology)).thenReturn(true)

        coordinator.topology = topology

        verify(topology).normalize()
        verify(mockTopologyChangedCallback).invoke(
            android.util.Pair(
                topologyCopy,
                topologyGraph
            )
        )
        verify(mockTopologyStore).saveTopology(topology)
        verify(mockTopologySavedCallback).invoke()
    }
}