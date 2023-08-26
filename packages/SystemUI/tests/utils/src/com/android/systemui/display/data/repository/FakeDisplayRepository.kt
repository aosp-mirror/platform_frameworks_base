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
fun display(type: Int, flags: Int = 0, id: Int = 0): Display {
    return mock {
        whenever(this.displayId).thenReturn(id)
        whenever(this.type).thenReturn(type)
        whenever(this.flags).thenReturn(flags)
    }
}

/** Fake [DisplayRepository] implementation for testing. */
class FakeDisplayRepository : DisplayRepository {
    private val flow = MutableSharedFlow<Set<Display>>()

    /** Emits [value] as [displays] flow value. */
    suspend fun emit(value: Set<Display>) = flow.emit(value)

    override val displays: Flow<Set<Display>>
        get() = flow
}
