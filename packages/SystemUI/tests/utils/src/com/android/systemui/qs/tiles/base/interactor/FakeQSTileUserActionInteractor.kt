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

package com.android.systemui.qs.tiles.base.interactor

import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeQSTileUserActionInteractor<T> : QSTileUserActionInteractor<T> {

    private val mutex: Mutex = Mutex()
    private val mutableInputs: MutableList<FakeInput<T>> = mutableListOf()

    val inputs: List<FakeInput<T>> = mutableInputs

    fun lastInput(): FakeInput<T>? = inputs.lastOrNull()

    override suspend fun handleInput(userAction: QSTileUserAction, currentData: T) {
        mutex.withLock { mutableInputs.add(FakeInput(userAction, currentData)) }
    }

    data class FakeInput<T>(val userAction: QSTileUserAction, val data: T)
}
