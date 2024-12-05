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

import android.content.Context
import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.display.data.repository.SingleDisplayStore
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.phone.DarkIconDispatcherImpl
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [DarkIconDispatcher]. */
interface DarkIconDispatcherStore : PerDisplayStore<DarkIconDispatcher>

/** Provides per display instances of [SysuiDarkIconDispatcher]. */
interface SysuiDarkIconDispatcherStore : PerDisplayStore<SysuiDarkIconDispatcher>

/**
 * Multi display implementation that should be used when the [StatusBarConnectedDisplays] flag is
 * enabled.
 */
@SysUISingleton
class MultiDisplayDarkIconDispatcherStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
    private val factory: DarkIconDispatcherImpl.Factory,
    private val displayWindowPropertiesRepository: DisplayWindowPropertiesRepository,
) :
    SysuiDarkIconDispatcherStore,
    PerDisplayStoreImpl<SysuiDarkIconDispatcher>(backgroundApplicationScope, displayRepository) {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun createInstanceForDisplay(displayId: Int): SysuiDarkIconDispatcher {
        val properties = displayWindowPropertiesRepository.get(displayId, TYPE_STATUS_BAR)
        return factory.create(displayId, properties.context)
    }

    override suspend fun onDisplayRemovalAction(instance: SysuiDarkIconDispatcher) {
        instance.stop()
    }

    override val instanceClass = SysuiDarkIconDispatcher::class.java
}

/**
 * Single display implementation that should be used when the [StatusBarConnectedDisplays] flag is
 * disabled.
 */
@SysUISingleton
class SingleDisplayDarkIconDispatcherStore
@Inject
constructor(factory: DarkIconDispatcherImpl.Factory, context: Context) :
    SysuiDarkIconDispatcherStore,
    PerDisplayStore<SysuiDarkIconDispatcher> by SingleDisplayStore(
        defaultInstance = factory.create(context.displayId, context)
    ) {

    init {
        StatusBarConnectedDisplays.assertInLegacyMode()
    }
}

/** Extra implementation that simply implements the [DarkIconDispatcherStore] interface. */
@SysUISingleton
class DarkIconDispatcherStoreImpl
@Inject
constructor(private val store: SysuiDarkIconDispatcherStore) : DarkIconDispatcherStore {
    override val defaultDisplay: DarkIconDispatcher
        get() = store.defaultDisplay

    override fun forDisplay(displayId: Int): DarkIconDispatcher = store.forDisplay(displayId)
}

@Module
interface DarkIconDispatcherStoreModule {

    @Binds fun store(impl: DarkIconDispatcherStoreImpl): DarkIconDispatcherStore

    companion object {
        @Provides
        @SysUISingleton
        fun sysUiStore(
            singleDisplayLazy: Lazy<SingleDisplayDarkIconDispatcherStore>,
            multiDisplayLazy: Lazy<MultiDisplayDarkIconDispatcherStore>,
        ): SysuiDarkIconDispatcherStore {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayLazy.get()
            } else {
                singleDisplayLazy.get()
            }
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(DarkIconDispatcherStore::class)
        fun storeAsCoreStartable(
            multiDisplayLazy: Lazy<MultiDisplayDarkIconDispatcherStore>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayLazy.get()
            } else {
                CoreStartable.NOP
            }
        }
    }
}
