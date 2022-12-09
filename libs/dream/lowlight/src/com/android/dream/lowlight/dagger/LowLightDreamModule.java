/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.dream.lowlight.dagger;

import android.app.DreamManager;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.Nullable;

import com.android.dream.lowlight.R;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for low light dream.
 *
 * @hide
 */
@Module
public interface LowLightDreamModule {
    String LOW_LIGHT_DREAM_COMPONENT = "low_light_dream_component";

    /**
     * Provides dream manager.
     */
    @Provides
    static DreamManager providesDreamManager(Context context) {
        return context.getSystemService(DreamManager.class);
    }

    /**
     * Provides the component name of the low light dream, or null if not configured.
     */
    @Provides
    @Named(LOW_LIGHT_DREAM_COMPONENT)
    @Nullable
    static ComponentName providesLowLightDreamComponent(Context context) {
        final String lowLightDreamComponent = context.getResources().getString(
                R.string.config_lowLightDreamComponent);
        return lowLightDreamComponent.isEmpty() ? null
                : ComponentName.unflattenFromString(lowLightDreamComponent);
    }
}
