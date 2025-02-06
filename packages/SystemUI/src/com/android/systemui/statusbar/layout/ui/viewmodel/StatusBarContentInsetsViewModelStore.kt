/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.layout.ui.viewmodel

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.display.data.repository.SingleDisplayStore
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per-display instances of [StatusBarContentInsetsViewModel]. */
interface StatusBarContentInsetsViewModelStore : PerDisplayStore<StatusBarContentInsetsViewModel>

@SysUISingleton
class MultiDisplayStatusBarContentInsetsViewModelStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
) :
    StatusBarContentInsetsViewModelStore,
    PerDisplayStoreImpl<StatusBarContentInsetsViewModel>(
        backgroundApplicationScope,
        displayRepository,
    ) {

    override fun createInstanceForDisplay(displayId: Int): StatusBarContentInsetsViewModel? {
        val insetsProvider =
            statusBarContentInsetsProviderStore.forDisplay(displayId) ?: return null
        return StatusBarContentInsetsViewModel(insetsProvider)
    }

    override val instanceClass = StatusBarContentInsetsViewModel::class.java
}

@SysUISingleton
class SingleDisplayStatusBarContentInsetsViewModelStore
@Inject
constructor(statusBarContentInsetsViewModel: StatusBarContentInsetsViewModel) :
    StatusBarContentInsetsViewModelStore,
    PerDisplayStore<StatusBarContentInsetsViewModel> by SingleDisplayStore(
        defaultInstance = statusBarContentInsetsViewModel
    )

@Module
object StatusBarContentInsetsViewModelStoreModule {
    @Provides
    @SysUISingleton
    @IntoMap
    @ClassKey(StatusBarContentInsetsViewModelStore::class)
    fun storeAsCoreStartable(
        multiDisplayLazy: Lazy<MultiDisplayStatusBarContentInsetsViewModelStore>
    ): CoreStartable {
        return if (StatusBarConnectedDisplays.isEnabled) {
            return multiDisplayLazy.get()
        } else {
            CoreStartable.NOP
        }
    }

    @Provides
    @SysUISingleton
    fun store(
        singleDisplayLazy: Lazy<SingleDisplayStatusBarContentInsetsViewModelStore>,
        multiDisplayLazy: Lazy<MultiDisplayStatusBarContentInsetsViewModelStore>,
    ): StatusBarContentInsetsViewModelStore {
        return if (StatusBarConnectedDisplays.isEnabled) {
            multiDisplayLazy.get()
        } else {
            singleDisplayLazy.get()
        }
    }
}
