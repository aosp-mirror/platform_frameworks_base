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

package com.android.systemui.keyguard.data.repository

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyevent.data.repository.KeyEventRepositoryImpl
import com.android.systemui.statusbar.CommandQueue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyEventRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: KeyEventRepositoryImpl
    @Mock private lateinit var commandQueue: CommandQueue
    @Captor private lateinit var commandQueueCallbacks: ArgumentCaptor<CommandQueue.Callbacks>
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        underTest = KeyEventRepositoryImpl(commandQueue)
    }

    @Test
    fun isPowerButtonDown_initialValueFalse() =
        testScope.runTest {
            val isPowerButtonDown by collectLastValue(underTest.isPowerButtonDown)
            runCurrent()
            assertThat(isPowerButtonDown).isFalse()
        }

    @Test
    fun isPowerButtonDown_onChange() =
        testScope.runTest {
            val isPowerButtonDown by collectLastValue(underTest.isPowerButtonDown)
            runCurrent()
            verify(commandQueue).addCallback(commandQueueCallbacks.capture())
            commandQueueCallbacks.value.handleSystemKey(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER)
            )
            assertThat(isPowerButtonDown).isTrue()

            commandQueueCallbacks.value.handleSystemKey(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER)
            )
            assertThat(isPowerButtonDown).isFalse()
        }
}
