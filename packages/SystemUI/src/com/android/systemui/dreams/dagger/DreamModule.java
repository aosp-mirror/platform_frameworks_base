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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.android.dream.lowlight.dagger.LowLightDreamModule;
import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayNotificationCountProvider;
import com.android.systemui.dreams.DreamOverlayService;
import com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule;
import com.android.systemui.dreams.touch.scrim.dagger.ScrimModule;
import com.android.systemui.process.condition.SystemProcessCondition;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

/**
 * Dagger Module providing Dream-related functionality.
 */
@Module(includes = {
            RegisteredComplicationsModule.class,
            LowLightDreamModule.class,
            ScrimModule.class
        },
        subcomponents = {
            DreamOverlayComponent.class,
        })
public interface DreamModule {
    String DREAM_ONLY_ENABLED_FOR_DOCK_USER = "dream_only_enabled_for_dock_user";
    String DREAM_OVERLAY_SERVICE_COMPONENT = "dream_overlay_service_component";
    String DREAM_OVERLAY_ENABLED = "dream_overlay_enabled";

    String DREAM_SUPPORTED = "dream_supported";
    String DREAM_PRETEXT_CONDITIONS = "dream_pretext_conditions";
    String DREAM_PRETEXT_MONITOR = "dream_prtext_monitor";
    String DREAM_OVERLAY_WINDOW_TITLE = "dream_overlay_window_title";


    /**
     * Provides the dream component
     */
    @Provides
    @Named(DREAM_OVERLAY_SERVICE_COMPONENT)
    static ComponentName providesDreamOverlayService(Context context) {
        return new ComponentName(context, DreamOverlayService.class);
    }

    /**
     * Provides whether dream overlay is enabled.
     */
    @Provides
    @Named(DREAM_OVERLAY_ENABLED)
    static Boolean providesDreamOverlayEnabled(PackageManager packageManager,
            @Named(DREAM_OVERLAY_SERVICE_COMPONENT) ComponentName component) {
        try {
            return packageManager.getServiceInfo(component, PackageManager.GET_META_DATA).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

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
    @Named(DREAM_ONLY_ENABLED_FOR_DOCK_USER)
    static boolean providesDreamOnlyEnabledForDockUser(@Main Resources resources) {
        return resources.getBoolean(
                com.android.internal.R.bool.config_dreamsOnlyEnabledForDockUser);
    }

    /** */
    @Provides
    @Named(DREAM_SUPPORTED)
    static boolean providesDreamSupported(@Main Resources resources) {
        return resources.getBoolean(com.android.internal.R.bool.config_dreamsSupported);
    }

    /** */
    @Binds
    @IntoSet
    @Named(DREAM_PRETEXT_CONDITIONS)
    Condition bindSystemProcessCondition(SystemProcessCondition condition);

    /** */
    @Provides
    @Named(DREAM_PRETEXT_MONITOR)
    static Monitor providesDockerPretextMonitor(
            @Main Executor executor,
            @Named(DREAM_PRETEXT_CONDITIONS) Set<Condition> pretextConditions) {
        return new Monitor(executor, pretextConditions);
    }

    /** */
    @Provides
    @Named(DREAM_OVERLAY_WINDOW_TITLE)
    static String providesDreamOverlayWindowTitle(@Main Resources resources) {
        return resources.getString(R.string.app_label);
    }
}
