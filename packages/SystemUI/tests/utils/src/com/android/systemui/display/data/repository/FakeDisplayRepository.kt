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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.mockito.mock
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mockito.Mockito.`when` as whenever

/** Creates a mock display. */
fun display(type: Int, flags: Int = 0, id: Int = 0, state: Int? = null): Display {
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

@SysUISingleton
/** Fake [DisplayRepository] implementation for testing. */
class FakeDisplayRepository @Inject constructor() : DisplayRepository {
    private val flow = MutableStateFlow<Set<Display>>(emptySet())
    private val pendingDisplayFlow =
        MutableSharedFlow<DisplayRepository.PendingDisplay?>(replay = 1)
    private val displayAdditionEventFlow = MutableSharedFlow<Display?>(replay = 0)
    private val displayRemovalEventFlow = MutableSharedFlow<Int>(replay = 0)

    suspend fun addDisplay(display: Display) {
        flow.value += display
        displayAdditionEventFlow.emit(display)
    }

    suspend fun removeDisplay(displayId: Int) {
        flow.value = flow.value.filter { it.displayId != displayId }.toSet()
        displayRemovalEventFlow.emit(displayId)
    }

    /** Emits [value] as [displayAdditionEvent] flow value. */
    suspend fun emit(value: Display?) = displayAdditionEventFlow.emit(value)

    /** Emits [value] as [displays] flow value. */
    suspend fun emit(value: Set<Display>) = flow.emit(value)

    /** Emits [value] as [pendingDisplay] flow value. */
    suspend fun emit(value: DisplayRepository.PendingDisplay?) = pendingDisplayFlow.emit(value)

    override val displays: StateFlow<Set<Display>>
        get() = flow

    override val pendingDisplay: Flow<DisplayRepository.PendingDisplay?>
        get() = pendingDisplayFlow

    val _defaultDisplayOff: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val defaultDisplayOff: Flow<Boolean>
        get() = _defaultDisplayOff.asStateFlow()

    override val displayAdditionEvent: Flow<Display?>
        get() = displayAdditionEventFlow

    override val displayRemovalEvent: Flow<Int> = displayRemovalEventFlow

    private val _displayChangeEvent = MutableSharedFlow<Int>(replay = 1)
    override val displayChangeEvent: Flow<Int> = _displayChangeEvent

    suspend fun emitDisplayChangeEvent(displayId: Int) = _displayChangeEvent.emit(displayId)

    fun setDefaultDisplayOff(defaultDisplayOff: Boolean) {
        _defaultDisplayOff.value = defaultDisplayOff
    }
}

@Module
interface FakeDisplayRepositoryModule {
    @Binds fun bindFake(fake: FakeDisplayRepository): DisplayRepository
}
