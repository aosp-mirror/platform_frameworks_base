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
import com.android.systemui.display.data.repository.SingleDisplayStore
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.events.PrivacyDotViewController
import com.android.systemui.statusbar.events.PrivacyDotViewControllerImpl
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [PrivacyDotViewController]. */
interface PrivacyDotViewControllerStore : PerDisplayStore<PrivacyDotViewController>

@SysUISingleton
class MultiDisplayPrivacyDotViewControllerStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val factory: PrivacyDotViewControllerImpl.Factory,
    private val displayScopeRepository: DisplayScopeRepository,
    private val statusBarConfigurationControllerStore: StatusBarConfigurationControllerStore,
    private val contentInsetsProviderStore: StatusBarContentInsetsProviderStore,
) :
    PrivacyDotViewControllerStore,
    PerDisplayStoreImpl<PrivacyDotViewController>(backgroundApplicationScope, displayRepository) {

    override fun createInstanceForDisplay(displayId: Int): PrivacyDotViewController {
        return factory.create(
            displayScopeRepository.scopeForDisplay(displayId),
            statusBarConfigurationControllerStore.forDisplay(displayId),
            contentInsetsProviderStore.forDisplay(displayId),
        )
    }

    override suspend fun onDisplayRemovalAction(instance: PrivacyDotViewController) {
        instance.stop()
    }

    override val instanceClass = PrivacyDotViewController::class.java
}

@SysUISingleton
class SingleDisplayPrivacyDotViewControllerStore
@Inject
constructor(defaultController: PrivacyDotViewController) :
    PrivacyDotViewControllerStore,
    PerDisplayStore<PrivacyDotViewController> by SingleDisplayStore(
        defaultInstance = defaultController
    )

@Module
object PrivacyDotViewControllerStoreModule {

    @Provides
    @SysUISingleton
    fun store(
        singleDisplayLazy: Lazy<SingleDisplayPrivacyDotViewControllerStore>,
        multiDisplayLazy: Lazy<MultiDisplayPrivacyDotViewControllerStore>,
    ): PrivacyDotViewControllerStore {
        return if (StatusBarConnectedDisplays.isEnabled) {
            multiDisplayLazy.get()
        } else {
            singleDisplayLazy.get()
        }
    }

    @Provides
    @SysUISingleton
    @IntoMap
    @ClassKey(PrivacyDotViewControllerStore::class)
    fun storeAsCoreStartable(
        multiDisplayLazy: Lazy<MultiDisplayPrivacyDotViewControllerStore>
    ): CoreStartable {
        return if (StatusBarConnectedDisplays.isEnabled) {
            multiDisplayLazy.get()
        } else {
            CoreStartable.NOP
        }
    }
}
