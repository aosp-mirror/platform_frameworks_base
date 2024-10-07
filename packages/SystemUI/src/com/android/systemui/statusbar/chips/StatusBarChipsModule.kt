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

package com.android.systemui.statusbar.chips

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel.DemoRonChipViewModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class StatusBarChipsModule {
    @Binds
    @IntoMap
    @ClassKey(DemoRonChipViewModel::class)
    abstract fun binds(impl: DemoRonChipViewModel): CoreStartable

    companion object {
        @Provides
        @SysUISingleton
        @StatusBarChipsLog
        fun provideChipsLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("StatusBarChips", 200)
        }
    }
}
