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

import android.view.Display
import androidx.test.filters.SmallTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.junit.MockitoJUnit

import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
class DisplayPowerStateTest {

    private lateinit var displayPowerState: DisplayPowerState

    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    private val mockBlanker = mock<DisplayBlanker>()
    private val mockColorFade = mock<ColorFade>()

    @Before
    fun setUp() {
        displayPowerState = DisplayPowerState(mockBlanker, mockColorFade, 123, Display.STATE_ON)
    }

    @Test
    fun `destroys ColorFade on stop`() {
        displayPowerState.stop()

        verify(mockColorFade).destroy()
    }
}