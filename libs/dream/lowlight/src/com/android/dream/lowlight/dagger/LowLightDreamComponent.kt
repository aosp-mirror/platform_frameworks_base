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

package com.android.dream.lowlight.dagger

import android.app.DreamManager
import android.content.ComponentName
import dagger.BindsInstance
import dagger.Subcomponent
import javax.inject.Named

@Subcomponent(modules = [LowLightDreamModule::class])
interface LowLightDreamComponent {
    @Subcomponent.Factory
    interface Factory {
        fun create(@BindsInstance dreamManager: DreamManager,
                   @Named(LowLightDreamModule.LOW_LIGHT_DREAM_COMPONENT)
                   @BindsInstance lowLightDreamComponent: ComponentName?
        ): LowLightDreamComponent
    }
}