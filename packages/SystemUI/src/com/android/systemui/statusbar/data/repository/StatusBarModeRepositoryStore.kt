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
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.statusbar.core.StatusBarInitializer
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import java.io.PrintWriter
import javax.inject.Inject

interface StatusBarModeRepositoryStore {
    val defaultDisplay: StatusBarModePerDisplayRepository
    fun forDisplay(displayId: Int): StatusBarModePerDisplayRepository
}

@SysUISingleton
class StatusBarModeRepositoryImpl
@Inject
constructor(
    @DisplayId private val displayId: Int,
    factory: StatusBarModePerDisplayRepositoryFactory
) :
    StatusBarModeRepositoryStore,
    CoreStartable,
    StatusBarInitializer.OnStatusBarViewInitializedListener {
    override val defaultDisplay = factory.create(displayId)

    override fun forDisplay(displayId: Int) =
        if (this.displayId == displayId) {
            defaultDisplay
        } else {
            TODO("b/127878649 implement multi-display state management")
        }

    override fun start() {
        defaultDisplay.start()
    }

    override fun onStatusBarViewInitialized(component: StatusBarFragmentComponent) {
        defaultDisplay.onStatusBarViewInitialized(component)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        defaultDisplay.dump(pw, args)
    }
}

@Module
interface StatusBarModeRepositoryModule {
    @Binds fun bindImpl(impl: StatusBarModeRepositoryImpl): StatusBarModeRepositoryStore

    @Binds
    @IntoMap
    @ClassKey(StatusBarModeRepositoryStore::class)
    fun bindCoreStartable(impl: StatusBarModeRepositoryImpl): CoreStartable

    @Binds
    @IntoSet
    fun bindViewInitListener(
        impl: StatusBarModeRepositoryImpl
    ): StatusBarInitializer.OnStatusBarViewInitializedListener
}
