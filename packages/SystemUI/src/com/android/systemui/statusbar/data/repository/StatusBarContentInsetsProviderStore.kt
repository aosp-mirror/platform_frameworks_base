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
import com.android.systemui.CameraProtectionLoaderImpl
import com.android.systemui.CoreStartable
import com.android.systemui.SysUICutoutProviderImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.display.data.repository.SingleDisplayStore
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProviderImpl
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [StatusBarContentInsetsProvider]. */
interface StatusBarContentInsetsProviderStore : PerDisplayStore<StatusBarContentInsetsProvider>

@SysUISingleton
class MultiDisplayStatusBarContentInsetsProviderStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val factory: StatusBarContentInsetsProviderImpl.Factory,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
    private val statusBarConfigurationControllerStore: StatusBarConfigurationControllerStore,
    private val sysUICutoutProviderFactory: SysUICutoutProviderImpl.Factory,
    private val cameraProtectionLoaderFactory: CameraProtectionLoaderImpl.Factory,
) :
    StatusBarContentInsetsProviderStore,
    PerDisplayStoreImpl<StatusBarContentInsetsProvider>(
        backgroundApplicationScope,
        displayRepository,
    ) {

    override fun createInstanceForDisplay(displayId: Int): StatusBarContentInsetsProvider {
        val context = displayWindowPropertiesRepository.get(displayId, TYPE_STATUS_BAR).context
        val cameraProtectionLoader = cameraProtectionLoaderFactory.create(context)
        return factory
            .create(
                context,
                statusBarConfigurationControllerStore.forDisplay(displayId),
                sysUICutoutProviderFactory.create(context, cameraProtectionLoader),
            )
            .also { it.start() }
    }

    override suspend fun onDisplayRemovalAction(instance: StatusBarContentInsetsProvider) {
        instance.stop()
    }

    override val instanceClass = StatusBarContentInsetsProvider::class.java
}

@SysUISingleton
class SingleDisplayStatusBarContentInsetsProviderStore
@Inject
constructor(statusBarContentInsetsProvider: StatusBarContentInsetsProvider) :
    StatusBarContentInsetsProviderStore,
    PerDisplayStore<StatusBarContentInsetsProvider> by SingleDisplayStore(
        defaultInstance = statusBarContentInsetsProvider
    )

@Module
object StatusBarContentInsetsProviderStoreModule {

    @Provides
    @SysUISingleton
    @IntoMap
    @ClassKey(StatusBarContentInsetsProviderStore::class)
    fun storeAsCoreStartable(
        multiDisplayLazy: Lazy<MultiDisplayStatusBarContentInsetsProviderStore>
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
        singleDisplayLazy: Lazy<SingleDisplayStatusBarContentInsetsProviderStore>,
        multiDisplayLazy: Lazy<MultiDisplayStatusBarContentInsetsProviderStore>,
    ): StatusBarContentInsetsProviderStore {
        return if (StatusBarConnectedDisplays.isEnabled) {
            multiDisplayLazy.get()
        } else {
            singleDisplayLazy.get()
        }
    }
}
