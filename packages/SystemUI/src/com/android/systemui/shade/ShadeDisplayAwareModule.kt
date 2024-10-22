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

package com.android.systemui.shade

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import dagger.Module
import dagger.Provides

/**
 * Module responsible for managing display-specific components and resources for the notification
 * shade window.
 *
 * This isolation is crucial because when the window transitions between displays, its associated
 * context, resources, and display characteristics (like density and size) also change. If the shade
 * window shared the same context as the rest of the system UI, it could lead to inconsistencies and
 * errors due to incorrect display information.
 *
 * By using this dedicated module, we ensure the notification shade window always utilizes the
 * correct display context and resources, regardless of the display it's on.
 */
@Module
object ShadeDisplayAwareModule {

    /** Creates a new context for the shade window. */
    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareContext(context: Context): Context {
        return if (Flags.shadeWindowGoesAround()) {
            context
                .createWindowContext(context.display, TYPE_APPLICATION_OVERLAY, /* options= */ null)
                .apply { setTheme(R.style.Theme_SystemUI) }
        } else {
            context
        }
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareResources(@ShadeDisplayAware context: Context): Resources {
        return context.resources
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun providesDisplayAwareLayoutInflater(@ShadeDisplayAware context: Context): LayoutInflater {
        return LayoutInflater.from(context)
    }
}
