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
 */

package com.android.server.display

import android.content.Context
import android.os.Looper
import android.view.Display
import androidx.test.filters.SmallTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.junit.MockitoJUnit

import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.Executor

@SmallTest
class DisplayPowerStateTest {

    private lateinit var displayPowerState: DisplayPowerState

    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    private val mockBlanker = mock<DisplayBlanker>()
    private val mockColorFade = mock<ColorFade>()
    private val mockExecutor = mock<Executor>()
    private val mockContext = mock<Context>()

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        displayPowerState = DisplayPowerState(mockBlanker, mockColorFade, 123, Display.STATE_ON,
                mockExecutor)
        whenever(mockColorFade.prepare(eq(mockContext), anyInt())).thenReturn(true)
    }

    @Test
    fun `destroys ColorFade on stop`() {
        displayPowerState.stop()
        val runnableCaptor = argumentCaptor<Runnable>()

        verify(mockExecutor).execute(runnableCaptor.capture())
        runnableCaptor.firstValue.run()

        verify(mockColorFade).destroy()
    }

    @Test
    fun `GIVEN not prepared WHEN draw runnable is called THEN colorFade not drawn`() {
        displayPowerState.mColorFadeDrawRunnable.run()

        verify(mockColorFade, never()).draw(anyFloat())
    }
    @Test
    fun `GIVEN prepared WHEN draw runnable is called THEN colorFade is drawn`() {
        displayPowerState.prepareColorFade(mockContext, ColorFade.MODE_FADE)
        clearInvocations(mockColorFade)

        displayPowerState.mColorFadeDrawRunnable.run()

        verify(mockColorFade).draw(anyFloat())
    }

    @Test
    fun `GIVEN prepared AND stopped WHEN draw runnable is called THEN colorFade is not drawn`() {
        displayPowerState.prepareColorFade(mockContext, ColorFade.MODE_FADE)
        clearInvocations(mockColorFade)
        displayPowerState.stop()

        displayPowerState.mColorFadeDrawRunnable.run()

        verify(mockColorFade, never()).draw(anyFloat())
    }
}