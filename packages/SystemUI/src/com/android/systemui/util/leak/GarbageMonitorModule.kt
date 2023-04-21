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

package com.android.systemui.util.leak

import com.android.systemui.CoreStartable
import com.android.systemui.qs.tileimpl.QSTileImpl
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface GarbageMonitorModule {
    /** Inject into GarbageMonitor.Service. */
    @Binds
    @IntoMap
    @ClassKey(GarbageMonitor::class)
    fun bindGarbageMonitorService(sysui: GarbageMonitor.Service): CoreStartable

    @Binds
    @IntoMap
    @StringKey(GarbageMonitor.MemoryTile.TILE_SPEC)
    fun bindMemoryTile(memoryTile: GarbageMonitor.MemoryTile): QSTileImpl<*>
}
