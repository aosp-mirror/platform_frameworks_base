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
package com.android.systemui.display.data.repository

import android.view.Display
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.mockito.Mockito.`when` as whenever

/** Creates a mock display. */
@JvmOverloads
fun display(
    type: Int,
    flags: Int = 0,
    id: Int = 0,
    state: Int? = null,
): Display {
    return mock {
        whenever(this.displayId).thenReturn(id)
        whenever(this.type).thenReturn(type)
        whenever(this.flags).thenReturn(flags)
        if (state != null) {
            whenever(this.state).thenReturn(state)
        }
    }
}

/** Creates a mock [DisplayRepository.PendingDisplay]. */
fun createPendingDisplay(id: Int = 0): DisplayRepository.PendingDisplay =
    mock<DisplayRepository.PendingDisplay> { whenever(this.id).thenReturn(id) }

/** Fake [DisplayRepository] implementation for testing. */
class FakeDisplayRepository : DisplayRepository {
    private val flow = MutableSharedFlow<Set<Display>>(replay = 1)
    private val pendingDisplayFlow =
        MutableSharedFlow<DisplayRepository.PendingDisplay?>(replay = 1)
    private val displayAdditionEventFlow = MutableSharedFlow<Display?>(replay = 1)

    /** Emits [value] as [displayAdditionEvent] flow value. */
    suspend fun emit(value: Display?) = displayAdditionEventFlow.emit(value)

    /** Emits [value] as [displays] flow value. */
    suspend fun emit(value: Set<Display>) = flow.emit(value)

    /** Emits [value] as [pendingDisplay] flow value. */
    suspend fun emit(value: DisplayRepository.PendingDisplay?) = pendingDisplayFlow.emit(value)

    override val displays: Flow<Set<Display>>
        get() = flow

    override val pendingDisplay: Flow<DisplayRepository.PendingDisplay?>
        get() = pendingDisplayFlow

    override val displayAdditionEvent: Flow<Display?>
        get() = displayAdditionEventFlow

    private val _displayChangeEvent = MutableSharedFlow<Int>(replay = 1)
    override val displayChangeEvent: Flow<Int> = _displayChangeEvent
    suspend fun emitDisplayChangeEvent(displayId: Int) = _displayChangeEvent.emit(displayId)
}
