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

package com.android.systemui.statusbar.data.repository

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.PerDisplayStore
import com.android.systemui.display.data.repository.PerDisplayStoreImpl
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.core.StatusBarInitializer
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

interface StatusBarModeRepositoryStore : PerDisplayStore<StatusBarModePerDisplayRepository>

@SysUISingleton
class MultiDisplayStatusBarModeRepositoryStore
@Inject
constructor(
    @Background backgroundApplicationScope: CoroutineScope,
    private val factory: StatusBarModePerDisplayRepositoryFactory,
    displayRepository: DisplayRepository,
) :
    StatusBarModeRepositoryStore,
    PerDisplayStoreImpl<StatusBarModePerDisplayRepository>(
        backgroundApplicationScope,
        displayRepository,
    ) {

    init {
        StatusBarConnectedDisplays.assertInNewMode()
    }

    override fun createInstanceForDisplay(displayId: Int): StatusBarModePerDisplayRepository {
        return factory.create(displayId).also { it.start() }
    }

    override suspend fun onDisplayRemovalAction(instance: StatusBarModePerDisplayRepository) {
        instance.stop()
    }

    override val instanceClass = StatusBarModePerDisplayRepository::class.java
}

@SysUISingleton
class StatusBarModeRepositoryImpl
@Inject
constructor(
    @DisplayId private val displayId: Int,
    factory: StatusBarModePerDisplayRepositoryFactory,
) :
    StatusBarModeRepositoryStore,
    CoreStartable,
    StatusBarInitializer.OnStatusBarViewInitializedListener {
    override val defaultDisplay = factory.create(displayId)

    override fun forDisplay(displayId: Int) = defaultDisplay

    override fun start() {
        defaultDisplay.start()
    }

    override fun onStatusBarViewInitialized(component: HomeStatusBarComponent) {
        defaultDisplay.onStatusBarViewInitialized(component)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        defaultDisplay.dump(pw, args)
    }
}

@Module
abstract class StatusBarModeRepositoryModule {
    @Binds
    @IntoSet
    abstract fun bindViewInitListener(
        impl: StatusBarModeRepositoryImpl
    ): StatusBarInitializer.OnStatusBarViewInitializedListener

    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(StatusBarModeRepositoryStore::class)
        fun storeAsCoreStartable(
            singleDisplayLazy: Lazy<StatusBarModeRepositoryImpl>,
            multiDisplayLazy: Lazy<MultiDisplayStatusBarModeRepositoryStore>,
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayLazy.get()
            } else {
                singleDisplayLazy.get()
            }
        }

        @Provides
        @SysUISingleton
        fun store(
            singleDisplayLazy: Lazy<StatusBarModeRepositoryImpl>,
            multiDisplayLazy: Lazy<MultiDisplayStatusBarModeRepositoryStore>,
        ): StatusBarModeRepositoryStore {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayLazy.get()
            } else {
                singleDisplayLazy.get()
            }
        }
    }
}
