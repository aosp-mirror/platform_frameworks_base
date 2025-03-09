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

package com.android.systemui.statusbar.data.repository

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayScopeRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.statusbar.phone.LightBarController
import com.android.systemui.statusbar.phone.LightBarControllerImpl
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [LightBarController]. */
interface LightBarControllerStore : PerDisplayStore<LightBarController>

@SysUISingleton
class LightBarControllerStoreImpl
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val factory: LightBarControllerImpl.Factory,
    private val displayScopeRepository: DisplayScopeRepository,
    private val statusBarModeRepositoryStore: StatusBarModeRepositoryStore,
    private val darkIconDispatcherStore: DarkIconDispatcherStore,
) :
    LightBarControllerStore,
    PerDisplayStoreImpl<LightBarController>(backgroundApplicationScope, displayRepository) {

    override fun createInstanceForDisplay(displayId: Int): LightBarController {
        return factory
            .create(
                displayId,
                displayScopeRepository.scopeForDisplay(displayId),
                darkIconDispatcherStore.forDisplay(displayId),
                statusBarModeRepositoryStore.forDisplay(displayId),
            )
            .also { it.start() }
    }

    override suspend fun onDisplayRemovalAction(instance: LightBarController) {
        instance.stop()
    }

    override val instanceClass = LightBarController::class.java
}

@Module
interface LightBarControllerStoreModule {

    @Binds fun store(impl: LightBarControllerStoreImpl): LightBarControllerStore

    @Binds
    @IntoMap
    @ClassKey(LightBarControllerStore::class)
    fun storeAsCoreStartable(impl: LightBarControllerStoreImpl): CoreStartable
}
