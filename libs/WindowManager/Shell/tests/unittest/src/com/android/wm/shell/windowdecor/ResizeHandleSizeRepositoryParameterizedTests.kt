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

package com.android.wm.shell.windowdecor

import android.content.Context
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import java.util.function.Consumer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Tests for {@link ResizeHandleSizeRepository}.
 *
 * Build/Install/Run: atest WMShellUnitTests:ResizeHandleSizeRepositoryParameterizedTests
 */
@SmallTest
@RunWith(Parameterized::class)
class ResizeHandleSizeRepositoryParameterizedTests {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val resizeHandleSizeRepository = ResizeHandleSizeRepository()
    @Mock private lateinit var mockSizeChangeFunctionOne: Consumer<ResizeHandleSizeRepository>
    @Mock private lateinit var mockSizeChangeFunctionTwo: Consumer<ResizeHandleSizeRepository>

    @JvmField @Rule val setFlagsRule = SetFlagsRule()

    @Parameter(0) lateinit var name: String
    // The current ResizeHandleSizeRepository API under test.

    @Parameter(1) lateinit var operation: (ResizeHandleSizeRepository) -> Unit

    @Before
    fun setOverrideBeforeResetResizeHandle() {
        MockitoAnnotations.initMocks(this)
        if (name != "reset") return
        val originalEdgeHandle =
            resizeHandleSizeRepository.getResizeEdgeHandlePixels(resources)
        resizeHandleSizeRepository.setResizeEdgeHandlePixels(originalEdgeHandle + 2)
    }

    companion object {
        @Parameters(name = "{index}: {0}")
        @JvmStatic
        fun data(): Iterable<Array<Any>> {
            return listOf(
                arrayOf(
                    "reset",
                    { sizeRepository: ResizeHandleSizeRepository ->
                        sizeRepository.resetResizeEdgeHandlePixels()
                    }
                ),
                arrayOf(
                    "set",
                    { sizeRepository: ResizeHandleSizeRepository ->
                        sizeRepository.setResizeEdgeHandlePixels(99)
                    }
                )
            )
        }
    }

    // =================
    // Validate that listeners are notified correctly for reset resize handle API.
    // =================

    @Test
    fun testUpdateResizeHandleSize_flagDisabled() {
        setFlagsRule.disableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        registerSizeChangeFunctions()
        operation.invoke(resizeHandleSizeRepository)
        // Nothing is notified since flag is disabled.
        verify(mockSizeChangeFunctionOne, never()).accept(any())
        verify(mockSizeChangeFunctionTwo, never()).accept(any())
    }

    @Test
    fun testUpdateResizeHandleSize_flagEnabled_noListeners() {
        setFlagsRule.enableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        operation.invoke(resizeHandleSizeRepository)
        // Nothing is notified since nothing was registered.
        verify(mockSizeChangeFunctionOne, never()).accept(any())
        verify(mockSizeChangeFunctionTwo, never()).accept(any())
    }

    @Test
    fun testUpdateResizeHandleSize_flagEnabled_listenersNotified() {
        setFlagsRule.enableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        registerSizeChangeFunctions()
        operation.invoke(resizeHandleSizeRepository)
        // Functions notified when reset.
        verify(mockSizeChangeFunctionOne).accept(any())
        verify(mockSizeChangeFunctionTwo).accept(any())
    }

    @Test
    fun testUpdateResizeHandleSize_flagEnabled_listenerFails() {
        setFlagsRule.enableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        registerSizeChangeFunctions()
        operation.invoke(resizeHandleSizeRepository)
        // Functions notified when reset.
        verify(mockSizeChangeFunctionOne).accept(any())
        verify(mockSizeChangeFunctionTwo).accept(any())
    }

    @Test
    fun testUpdateResizeHandleSize_flagEnabled_ignoreSecondListener() {
        setFlagsRule.enableFlags(Flags.FLAG_ENABLE_WINDOWING_EDGE_DRAG_RESIZE)
        registerSizeChangeFunctions()
        val extraConsumerMock = mock(Consumer::class.java) as Consumer<ResizeHandleSizeRepository>
        resizeHandleSizeRepository.registerSizeChangeFunction(extraConsumerMock)
        // First listener succeeds, second one that fails is ignored.
        operation.invoke(resizeHandleSizeRepository)
        // Functions notified when reset.
        verify(mockSizeChangeFunctionOne).accept(any())
        verify(mockSizeChangeFunctionTwo).accept(any())
        verify(extraConsumerMock).accept(any())
    }

    private fun registerSizeChangeFunctions() {
        resizeHandleSizeRepository.registerSizeChangeFunction(mockSizeChangeFunctionOne)
        resizeHandleSizeRepository.registerSizeChangeFunction(mockSizeChangeFunctionTwo)
    }
}
