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

package com.android.wm.shell.windowdecor.tiling

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.RoundedCorner
import android.view.SurfaceControl
import androidx.test.annotation.UiThreadTest
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.SyncTransactionQueue
import java.util.function.Supplier
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTilingDividerWindowManagerTest : ShellTestCase() {
    private lateinit var config: Configuration

    private var windowName: String = "Tiling"

    private val leashMock = mock<SurfaceControl>()

    private val syncQueueMock = mock<SyncTransactionQueue>()

    private val transitionHandlerMock = mock<DesktopTilingWindowDecoration>()

    private val transactionSupplierMock = mock<Supplier<SurfaceControl.Transaction>>()

    private val surfaceControl = mock<SurfaceControl>()

    private val transaction = mock<SurfaceControl.Transaction>()

    private lateinit var desktopTilingWindowManager: DesktopTilingDividerWindowManager

    private val context = mock<Context>()
    private val display = mock<Display>()
    private val roundedCorner = mock<RoundedCorner>()

    @Before
    fun setup() {
        config = Configuration()
        config.setToDefaults()
        whenever(context.display).thenReturn(display)
        whenever(display.getRoundedCorner(any())).thenReturn(roundedCorner)
        whenever(roundedCorner.radius).thenReturn(CORNER_RADIUS)
        desktopTilingWindowManager =
            DesktopTilingDividerWindowManager(
                config,
                windowName,
                mContext,
                leashMock,
                syncQueueMock,
                transitionHandlerMock,
                transactionSupplierMock,
                BOUNDS,
                context,
            )
    }

    @Test
    @UiThreadTest
    fun testWindowManager_isInitialisedAndReleased() {
        whenever(transactionSupplierMock.get()).thenReturn(transaction)
        whenever(transaction.hide(any())).thenReturn(transaction)
        whenever(transaction.setRelativeLayer(any(), any(), any())).thenReturn(transaction)
        whenever(transaction.setPosition(any(), any(), any())).thenReturn(transaction)
        whenever(transaction.remove(any())).thenReturn(transaction)

        desktopTilingWindowManager.generateViewHost(surfaceControl)

        // Ensure a surfaceControl transaction runs to show the divider.
        verify(transactionSupplierMock, times(1)).get()

        desktopTilingWindowManager.release()
        verify(transaction, times(1)).hide(any())
        verify(transaction, times(1)).remove(any())
        verify(transaction, times(1)).apply()
    }

    @Test
    @UiThreadTest
    fun testWindowManager_accountsForRoundedCornerDimensions() {
        whenever(transactionSupplierMock.get()).thenReturn(transaction)
        whenever(transaction.setRelativeLayer(any(), any(), any())).thenReturn(transaction)
        whenever(transaction.setRelativeLayer(any(), any(), any())).thenReturn(transaction)
        whenever(transaction.setPosition(any(), any(), any())).thenReturn(transaction)
        whenever(transaction.show(any())).thenReturn(transaction)

        desktopTilingWindowManager.generateViewHost(surfaceControl)

        // Ensure a surfaceControl transaction runs to show the divider.
        verify(transaction, times(1))
            .setPosition(any(), eq(BOUNDS.left.toFloat() - CORNER_RADIUS), any())
    }

    companion object {
        private val BOUNDS = Rect(1, 2, 3, 4)
        private const val CORNER_RADIUS = 28
    }
}
