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

package com.android.systemui.display.domain.interactor

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import android.view.Display.TYPE_INTERNAL
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class ConnectedDisplayInteractorTest : SysuiTestCase() {

    private val fakeDisplayRepository = FakeDisplayRepository()
    private val connectedDisplayStateProvider: ConnectedDisplayInteractor =
        ConnectedDisplayInteractorImpl(fakeDisplayRepository)
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun displayState_nullDisplays_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(emptySet())

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun displayState_emptyDisplays_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(emptySet())

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun displayState_internalDisplay_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(setOf(display(type = TYPE_INTERNAL)))

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun displayState_externalDisplay_connected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(setOf(display(type = TYPE_EXTERNAL)))

            assertThat(value).isEqualTo(State.CONNECTED)
        }

    @Test
    fun displayState_multipleExternalDisplays_connected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(
                setOf(display(type = TYPE_EXTERNAL), display(type = TYPE_EXTERNAL))
            )

            assertThat(value).isEqualTo(State.CONNECTED)
        }

    @Test
    fun displayState_externalSecure_connectedSecure() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(
                setOf(display(type = TYPE_EXTERNAL, flags = Display.FLAG_SECURE))
            )

            assertThat(value).isEqualTo(State.CONNECTED_SECURE)
        }

    @Test
    fun displayState_multipleExternal_onlyOneSecure_connectedSecure() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(
                setOf(
                    display(type = TYPE_EXTERNAL, flags = Display.FLAG_SECURE),
                    display(type = TYPE_EXTERNAL, flags = 0)
                )
            )

            assertThat(value).isEqualTo(State.CONNECTED_SECURE)
        }

    private fun TestScope.lastValue(): FlowValue<State?> =
        collectLastValue(connectedDisplayStateProvider.connectedDisplayState)

    private fun display(type: Int, flags: Int = 0): Display {
        return mock<Display>().also { mockDisplay ->
            whenever(mockDisplay.type).thenReturn(type)
            whenever(mockDisplay.flags).thenReturn(flags)
        }
    }

    private class FakeDisplayRepository : DisplayRepository {
        private val flow = MutableSharedFlow<Set<Display>>()
        suspend fun emit(value: Set<Display>) = flow.emit(value)
        override val displays: Flow<Set<Display>>
            get() = flow
    }
}
