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
package com.android.dream.lowlight.dagger

import android.app.DreamManager
import android.content.ComponentName
import android.content.Context
import com.android.dream.lowlight.R
import com.android.dream.lowlight.dagger.qualifiers.Application
import com.android.dream.lowlight.dagger.qualifiers.Main
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Named

/**
 * Dagger module for low light dream.
 *
 * @hide
 */
@Module
object LowLightDreamModule {
    /**
     * Provides dream manager.
     */
    @Provides
    fun providesDreamManager(context: Context): DreamManager {
        return requireNotNull(context.getSystemService(DreamManager::class.java))
    }

    /**
     * Provides the component name of the low light dream, or null if not configured.
     */
    @Provides
    @Named(LOW_LIGHT_DREAM_COMPONENT)
    fun providesLowLightDreamComponent(context: Context): ComponentName? {
        val lowLightDreamComponent = context.resources.getString(
            R.string.config_lowLightDreamComponent
        )
        return if (lowLightDreamComponent.isEmpty()) {
            null
        } else {
            ComponentName.unflattenFromString(lowLightDreamComponent)
        }
    }

    @Provides
    @Named(LOW_LIGHT_TRANSITION_TIMEOUT_MS)
    fun providesLowLightTransitionTimeout(context: Context): Long {
        return context.resources.getInteger(R.integer.config_lowLightTransitionTimeoutMs).toLong()
    }

    @Provides
    @Main
    fun providesMainDispatcher(): CoroutineDispatcher {
        return Dispatchers.Main.immediate
    }

    @Provides
    @Application
    fun providesApplicationScope(@Main dispatcher: CoroutineDispatcher): CoroutineScope {
        return CoroutineScope(dispatcher)
    }

    const val LOW_LIGHT_DREAM_COMPONENT = "low_light_dream_component"
    const val LOW_LIGHT_TRANSITION_TIMEOUT_MS = "low_light_transition_timeout"
}
