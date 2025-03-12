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

package com.android.systemui.display.data.repository

import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
/** Fake [FocusedDisplayRepository] for testing. */
class FakeFocusedDisplayRepository @Inject constructor() : FocusedDisplayRepository {
    private val flow = MutableStateFlow<Int>(Display.DEFAULT_DISPLAY)

    override val focusedDisplayId: StateFlow<Int>
        get() = flow.asStateFlow()

    suspend fun emit(focusedDisplay: Int) = flow.emit(focusedDisplay)
}

@Module
interface FakeFocusedDisplayRepositoryModule {
    @Binds fun bindFake(fake: FakeFocusedDisplayRepository): FocusedDisplayRepository
}
