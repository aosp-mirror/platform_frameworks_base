/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.mediaprojection.appselector.view

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Insets
import android.graphics.Rect
import android.util.DisplayMetrics.DENSITY_DEFAULT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.appselector.view.TaskPreviewSizeProvider.TaskPreviewSizeListener
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlin.math.min
import org.junit.Before
import org.junit.Test

@SmallTest
class TaskPreviewSizeProviderTest : SysuiTestCase() {

    private val lifecycleOwner = mock<LifecycleOwner>()
    private val mockContext = mock<Context>()
    private val resources = mock<Resources>()
    private val windowManager = mock<WindowManager>()
    private val windowMetricsProvider = mock<WindowMetricsProvider>()
    private val sizeUpdates = arrayListOf<Rect>()
    private val testConfigurationController = FakeConfigurationController()

    @Before
    fun setup() {
        whenever(mockContext.getSystemService(eq(WindowManager::class.java)))
            .thenReturn(windowManager)
        whenever(mockContext.resources).thenReturn(resources)
    }

    @Test
    fun size_phoneDisplay_thumbnailSizeIsSmallerAndProportionalToTheScreenSize() {
        givenDisplay(width = 400, height = 600, isTablet = false)

        val size = createSizeProvider().size

        assertThat(size).isEqualTo(Rect(0, 0, 100, 150))
    }

    @Test
    fun size_tabletDisplay_thumbnailSizeProportionalToTheScreenSizeExcludingTaskbar() {
        givenDisplay(width = 400, height = 600, isTablet = true)
        givenTaskbarSize(20)

        val size = createSizeProvider().size

        assertThat(size).isEqualTo(Rect(0, 0, 97, 140))
    }

    @Test
    fun size_phoneDisplayAndRotate_emitsSizeUpdate() {
        givenDisplay(width = 400, height = 600, isTablet = false)
        createSizeProvider().also { it.onCreate(lifecycleOwner) }

        givenDisplay(width = 600, height = 400, isTablet = false)
        testConfigurationController.onConfigurationChanged(Configuration())

        assertThat(sizeUpdates).containsExactly(Rect(0, 0, 150, 100))
    }

    @Test
    fun size_phoneDisplayAndRotateConfigurationChange_returnsUpdatedSize() {
        givenDisplay(width = 400, height = 600, isTablet = false)
        val sizeProvider = createSizeProvider().also { it.onCreate(lifecycleOwner) }

        givenDisplay(width = 600, height = 400, isTablet = false)
        testConfigurationController.onConfigurationChanged(Configuration())

        assertThat(sizeProvider.size).isEqualTo(Rect(0, 0, 150, 100))
    }

    @Test
    fun size_phoneDisplayAndRotateConfigurationChange_afterChooserDestroyed_doesNotUpdate() {
        givenDisplay(width = 400, height = 600, isTablet = false)
        val sizeProvider = createSizeProvider()
        val previousSize = Rect(sizeProvider.size)

        sizeProvider.onCreate(lifecycleOwner)
        sizeProvider.onDestroy(lifecycleOwner)
        givenDisplay(width = 600, height = 400, isTablet = false)
        testConfigurationController.onConfigurationChanged(Configuration())

        assertThat(sizeProvider.size).isEqualTo(previousSize)
    }

    private fun givenTaskbarSize(size: Int) {
        val insets = Insets.of(Rect(0, 0, 0, size))
        val windowInsets = WindowInsets.Builder().setInsets(Type.tappableElement(), insets).build()
        val windowMetrics = WindowMetrics(windowManager.maximumWindowMetrics.bounds, windowInsets)
        whenever(windowManager.maximumWindowMetrics).thenReturn(windowMetrics)
        whenever(windowManager.currentWindowMetrics).thenReturn(windowMetrics)
        whenever(windowMetricsProvider.currentWindowInsets).thenReturn(insets)
    }

    private fun givenDisplay(width: Int, height: Int, isTablet: Boolean = false) {
        val bounds = Rect(0, 0, width, height)
        val windowMetrics = WindowMetrics(bounds, { null }, 1.0f)
        whenever(windowManager.maximumWindowMetrics).thenReturn(windowMetrics)
        whenever(windowManager.currentWindowMetrics).thenReturn(windowMetrics)
        whenever(windowMetricsProvider.maximumWindowBounds).thenReturn(bounds)

        val minDimension = min(width, height)

        // Calculate DPI so the smallest width is either considered as tablet or as phone
        val targetSmallestWidthDpi =
            if (isTablet) SMALLEST_WIDTH_DPI_TABLET else SMALLEST_WIDTH_DPI_PHONE
        val densityDpi = minDimension * DENSITY_DEFAULT / targetSmallestWidthDpi

        val configuration = Configuration(context.resources.configuration)
        configuration.densityDpi = densityDpi
        whenever(resources.configuration).thenReturn(configuration)
    }

    private fun createSizeProvider(): TaskPreviewSizeProvider {
        val listener =
            object : TaskPreviewSizeListener {
                override fun onTaskSizeChanged(size: Rect) {
                    sizeUpdates.add(size)
                }
            }

        return TaskPreviewSizeProvider(
                mockContext,
                windowMetricsProvider,
                testConfigurationController
            )
            .also { it.addCallback(listener) }
    }

    private companion object {
        private const val SMALLEST_WIDTH_DPI_TABLET = 800
        private const val SMALLEST_WIDTH_DPI_PHONE = 400
    }
}
