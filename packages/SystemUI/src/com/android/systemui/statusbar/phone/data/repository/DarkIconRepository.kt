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
package com.android.systemui.statusbar.phone.data.repository

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.data.repository.SysuiDarkIconDispatcherStore
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dark-mode state for tinting icons. */
interface DarkIconRepository {
    fun darkState(displayId: Int): StateFlow<DarkChange>
}

@SysUISingleton
class DarkIconRepositoryImpl
@Inject
constructor(private val darkIconDispatcherStore: SysuiDarkIconDispatcherStore) :
    DarkIconRepository {
    override fun darkState(displayId: Int): StateFlow<DarkChange> {
        val perDisplayDakIconDispatcher = darkIconDispatcherStore.forDisplay(displayId)
        if (perDisplayDakIconDispatcher == null) {
            Log.e(
                TAG,
                "DarkIconDispatcher for display $displayId is null. Returning flow of " +
                    "DarkChange.EMPTY",
            )
            return MutableStateFlow(DarkChange.EMPTY)
        }
        return perDisplayDakIconDispatcher.darkChangeFlow()
    }

    private companion object {
        const val TAG = "DarkIconRepositoryImpl"
    }
}

@Module
interface DarkIconRepositoryModule {
    @Binds fun bindImpl(impl: DarkIconRepositoryImpl): DarkIconRepository
}
