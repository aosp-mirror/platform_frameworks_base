/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams.dagger;

import android.content.Context;
import android.content.res.Resources;

import com.android.dream.lowlight.dagger.LowLightDreamModule;
import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayNotificationCountProvider;
import com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule;

import java.util.Optional;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing Dream-related functionality.
 */
@Module(includes = {
            RegisteredComplicationsModule.class,
            LowLightDreamModule.class,
        },
        subcomponents = {
            DreamOverlayComponent.class,
        })
public interface DreamModule {
    String DREAM_ONLY_ENABLED_FOR_SYSTEM_USER = "dream_only_enabled_for_system_user";

    String DREAM_SUPPORTED = "dream_supported";

    /**
     * Provides an instance of the dream backend.
     */
    @Provides
    static DreamBackend providesDreamBackend(Context context) {
        return DreamBackend.getInstance(context);
    }

    /**
     * Provides an instance of a {@link DreamOverlayNotificationCountProvider}.
     */
    @SysUISingleton
    @Provides
    static Optional<DreamOverlayNotificationCountProvider>
            providesDreamOverlayNotificationCountProvider() {
        // If we decide to bring this back, we should gate it on a config that can be changed in
        // an overlay.
        return Optional.empty();
    }

    /** */
    @Provides
    @Named(DREAM_ONLY_ENABLED_FOR_SYSTEM_USER)
    static boolean providesDreamOnlyEnabledForSystemUser(@Main Resources resources) {
        return resources.getBoolean(
                com.android.internal.R.bool.config_dreamsOnlyEnabledForSystemUser);
    }

    /** */
    @Provides
    @Named(DREAM_SUPPORTED)
    static boolean providesDreamSupported(@Main Resources resources) {
        return resources.getBoolean(com.android.internal.R.bool.config_dreamsSupported);
    }
}
