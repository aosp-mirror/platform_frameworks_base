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
package com.android.systemui.ambient.touch.dagger

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import com.android.systemui.ambient.dagger.AmbientModule
import com.android.systemui.ambient.touch.TouchHandler
import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope

@Module
interface AmbientTouchModule {
    companion object {
        @JvmStatic
        @Provides
        fun providesLifecycle(lifecycleOwner: LifecycleOwner): Lifecycle {
            return lifecycleOwner.lifecycle
        }

        @JvmStatic
        @Provides
        fun providesLifecycleScope(lifecycle: Lifecycle): CoroutineScope {
            return lifecycle.coroutineScope
        }

        @Provides
        @ElementsIntoSet
        fun providesDreamTouchHandlers(
            @Named(AmbientModule.TOUCH_HANDLERS)
            touchHandlers: Set<@JvmSuppressWildcards TouchHandler>
        ): Set<@JvmSuppressWildcards TouchHandler> {
            return touchHandlers
        }

        const val INPUT_SESSION_NAME = "INPUT_SESSION_NAME"
        const val PILFER_ON_GESTURE_CONSUME = "PILFER_ON_GESTURE_CONSUME"
    }
}
