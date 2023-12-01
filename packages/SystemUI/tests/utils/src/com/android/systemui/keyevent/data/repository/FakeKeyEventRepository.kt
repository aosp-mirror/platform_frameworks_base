/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.keyevent.data.repository

import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeKeyEventRepository @Inject constructor() : KeyEventRepository {
    private val _isPowerButtonDown = MutableStateFlow(false)
    override val isPowerButtonDown: Flow<Boolean> = _isPowerButtonDown.asStateFlow()

    fun setPowerButtonDown(isDown: Boolean) {
        _isPowerButtonDown.value = isDown
    }
}

@Module
interface FakeKeyEventRepositoryModule {
    @Binds fun bindFake(fake: FakeKeyEventRepository): KeyEventRepository
}
