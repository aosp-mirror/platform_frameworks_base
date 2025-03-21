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

package com.android.systemui.statusbar.events

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import dagger.Binds
import dagger.Module
import dagger.Provides

@Module(includes = [SystemEventChipAnimationControllerModule::class])
interface StatusBarEventsModule {

    companion object {

        @Provides
        @SysUISingleton
        @SystemStatusAnimationSchedulerLog
        fun provideSystemStatusAnimationSchedulerLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("SystemStatusAnimationSchedulerLog", 60)
        }
    }

    @Binds
    @SysUISingleton
    fun bindSystemStatusAnimationScheduler(
        systemStatusAnimationSchedulerImpl: SystemStatusAnimationSchedulerImpl
    ): SystemStatusAnimationScheduler
}
