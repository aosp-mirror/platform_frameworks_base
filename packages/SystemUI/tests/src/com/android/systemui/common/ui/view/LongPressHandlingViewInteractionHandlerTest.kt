/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.common.ui.view

import android.view.ViewConfiguration
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.view.LongPressHandlingViewInteractionHandler.MotionEventModel
import com.android.systemui.common.ui.view.LongPressHandlingViewInteractionHandler.MotionEventModel.Down
import com.android.systemui.common.ui.view.LongPressHandlingViewInteractionHandler.MotionEventModel.Move
import com.android.systemui.common.ui.view.LongPressHandlingViewInteractionHandler.MotionEventModel.Up
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class LongPressHandlingViewInteractionHandlerTest : SysuiTestCase() {

    @Mock private lateinit var postDelayed: (Runnable, Long) -> DisposableHandle
    @Mock private lateinit var onLongPressDetected: (Int, Int) -> Unit
    @Mock private lateinit var onSingleTapDetected: () -> Unit

    private lateinit var underTest: LongPressHandlingViewInteractionHandler

    private var isAttachedToWindow: Boolean = true
    private var delayedRunnable: Runnable? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(postDelayed.invoke(any(), any())).thenAnswer { invocation ->
            delayedRunnable = invocation.arguments[0] as Runnable
            DisposableHandle { delayedRunnable = null }
        }

        underTest =
            LongPressHandlingViewInteractionHandler(
                postDelayed = postDelayed,
                isAttachedToWindow = { isAttachedToWindow },
                onLongPressDetected = onLongPressDetected,
                onSingleTapDetected = onSingleTapDetected,
            )
        underTest.isLongPressHandlingEnabled = true
    }

    @Test
    fun `long-press`() = runTest {
        val downX = 123
        val downY = 456
        dispatchTouchEvents(
            Down(
                x = downX,
                y = downY,
            ),
            Move(
                distanceMoved = ViewConfiguration.getTouchSlop() - 0.1f,
            ),
        )
        delayedRunnable?.run()

        verify(onLongPressDetected).invoke(downX, downY)
        verify(onSingleTapDetected, never()).invoke()
    }

    @Test
    fun `long-press but feature not enabled`() = runTest {
        underTest.isLongPressHandlingEnabled = false
        dispatchTouchEvents(
            Down(
                x = 123,
                y = 456,
            ),
        )

        assertThat(delayedRunnable).isNull()
        verify(onLongPressDetected, never()).invoke(any(), any())
        verify(onSingleTapDetected, never()).invoke()
    }

    @Test
    fun `long-press but view not attached`() = runTest {
        isAttachedToWindow = false
        dispatchTouchEvents(
            Down(
                x = 123,
                y = 456,
            ),
        )
        delayedRunnable?.run()

        verify(onLongPressDetected, never()).invoke(any(), any())
        verify(onSingleTapDetected, never()).invoke()
    }

    @Test
    fun `dragged too far to be considered a long-press`() = runTest {
        dispatchTouchEvents(
            Down(
                x = 123,
                y = 456,
            ),
            Move(
                distanceMoved = ViewConfiguration.getTouchSlop() + 0.1f,
            ),
        )

        assertThat(delayedRunnable).isNull()
        verify(onLongPressDetected, never()).invoke(any(), any())
        verify(onSingleTapDetected, never()).invoke()
    }

    @Test
    fun `held down too briefly to be considered a long-press`() = runTest {
        dispatchTouchEvents(
            Down(
                x = 123,
                y = 456,
            ),
            Up(
                distanceMoved = ViewConfiguration.getTouchSlop().toFloat(),
                gestureDuration = ViewConfiguration.getLongPressTimeout() - 1L,
            ),
        )

        assertThat(delayedRunnable).isNull()
        verify(onLongPressDetected, never()).invoke(any(), any())
        verify(onSingleTapDetected).invoke()
    }

    private fun dispatchTouchEvents(
        vararg models: MotionEventModel,
    ) {
        models.forEach { model -> underTest.onTouchEvent(model) }
    }
}
