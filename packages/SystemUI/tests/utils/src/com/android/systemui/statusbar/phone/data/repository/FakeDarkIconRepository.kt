/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.phone.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeDarkIconRepository @Inject constructor() : DarkIconRepository {
    private val perDisplayStates = mutableMapOf<Int, MutableStateFlow<DarkChange>>()

    override fun darkState(displayId: Int) =
        perDisplayStates.computeIfAbsent(displayId) { MutableStateFlow(DarkChange.EMPTY) }
}

@Module
interface FakeDarkIconRepositoryModule {
    @Binds fun bindFake(fake: FakeDarkIconRepository): DarkIconRepository
}
