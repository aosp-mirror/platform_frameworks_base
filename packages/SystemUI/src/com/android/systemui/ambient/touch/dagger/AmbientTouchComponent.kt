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

import androidx.lifecycle.LifecycleOwner
import com.android.systemui.ambient.dagger.AmbientModule.Companion.TOUCH_HANDLERS
import com.android.systemui.ambient.touch.TouchHandler
import com.android.systemui.ambient.touch.TouchMonitor
import dagger.BindsInstance
import dagger.Subcomponent
import javax.inject.Named

/**
 * {@link AmbientTouchComponent} can be used for setting up a touch environment over the entire
 * display surface. This allows for implementing behaviors such as swiping up to bring up the
 * bouncer.
 */
@Subcomponent(modules = [AmbientTouchModule::class, ShadeModule::class, BouncerSwipeModule::class])
interface AmbientTouchComponent {
    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance lifecycleOwner: LifecycleOwner,
            @BindsInstance
            @Named(TOUCH_HANDLERS)
            touchHandlers: Set<@JvmSuppressWildcards TouchHandler>
        ): AmbientTouchComponent
    }

    /** Builds a [TouchMonitor] */
    fun getTouchMonitor(): TouchMonitor
}
