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

import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.events.SystemEventChipAnimationController
import com.android.systemui.statusbar.events.SystemEventChipAnimationControllerImpl
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [SystemEventChipAnimationController]. */
interface SystemEventChipAnimationControllerStore :
    PerDisplayStore<SystemEventChipAnimationController>

@SysUISingleton
class SystemEventChipAnimationControllerStoreImpl
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val factory: SystemEventChipAnimationControllerImpl.Factory,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
) :
    SystemEventChipAnimationControllerStore,
    PerDisplayStoreImpl<SystemEventChipAnimationController>(
        backgroundApplicationScope,
        displayRepository,
    ) {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun createInstanceForDisplay(displayId: Int): SystemEventChipAnimationController {
        return factory.create(
            displayWindowPropertiesRepository.get(displayId, TYPE_STATUS_BAR).context,
            statusBarWindowControllerStore.forDisplay(displayId),
            statusBarContentInsetsProviderStore.forDisplay(displayId),
        )
    }

    override suspend fun onDisplayRemovalAction(instance: SystemEventChipAnimationController) {
        instance.stop()
    }

    override val instanceClass = SystemEventChipAnimationController::class.java
}

@Module
interface SystemEventChipAnimationControllerStoreModule {

    @Binds
    @SysUISingleton
    fun store(
        impl: SystemEventChipAnimationControllerStoreImpl
    ): SystemEventChipAnimationControllerStore

    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(SystemEventChipAnimationControllerStore::class)
        fun storeAsCoreStartable(
            implLazy: Lazy<SystemEventChipAnimationControllerStoreImpl>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                implLazy.get()
            } else {
                CoreStartable.NOP
            }
        }
    }
}
