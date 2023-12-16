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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.android.dream.lowlight.dagger.LowLightDreamModule;
import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.complication.dagger.RegisteredComplicationsModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayNotificationCountProvider;
import com.android.systemui.dreams.DreamOverlayService;
import com.android.systemui.dreams.complication.dagger.ComplicationComponent;
import com.android.systemui.dreams.touch.scrim.dagger.ScrimModule;
import com.android.systemui.res.R;
import com.android.systemui.touch.TouchInsetManager;

import dagger.Module;
import dagger.Provides;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;

/**
 * Dagger Module providing Dream-related functionality.
 */
@Module(includes = {
            RegisteredComplicationsModule.class,
            LowLightDreamModule.class,
            ScrimModule.class
        },
        subcomponents = {
            ComplicationComponent.class,
            DreamOverlayComponent.class,
        })
public interface DreamModule {
    String DREAM_ONLY_ENABLED_FOR_DOCK_USER = "dream_only_enabled_for_dock_user";
    String DREAM_OVERLAY_SERVICE_COMPONENT = "dream_overlay_service_component";
    String DREAM_OVERLAY_ENABLED = "dream_overlay_enabled";
    String DREAM_TOUCH_INSET_MANAGER = "dream_touch_inset_manager";
    String DREAM_SUPPORTED = "dream_supported";
    String DREAM_OVERLAY_WINDOW_TITLE = "dream_overlay_window_title";
    String HOME_CONTROL_PANEL_DREAM_COMPONENT = "home_control_panel_dream_component";

    /**
     * Provides the dream component
     */
    @Provides
    @Named(DREAM_OVERLAY_SERVICE_COMPONENT)
    static ComponentName providesDreamOverlayService(Context context) {
        return new ComponentName(context, DreamOverlayService.class);
    }

    /**
     * Provides the home control panel component
     */
    @Provides
    @Nullable
    @Named(HOME_CONTROL_PANEL_DREAM_COMPONENT)
    static ComponentName providesHomeControlPanelComponent(Context context) {
        final String homeControlPanelComponent = context.getResources()
                .getString(R.string.config_homePanelDreamComponent);
        if (homeControlPanelComponent.isEmpty()) {
            return null;
        }
        return ComponentName.unflattenFromString(homeControlPanelComponent);
    }

    /**
     * Provides a touch inset manager for dreams.
     */
    @Provides
    @Named(DREAM_TOUCH_INSET_MANAGER)
    static TouchInsetManager providesTouchInsetManager(@Main Executor executor) {
        return new TouchInsetManager(executor);
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
    @Provides
    @Named(DREAM_OVERLAY_WINDOW_TITLE)
    static String providesDreamOverlayWindowTitle(@Main Resources resources) {
        return resources.getString(R.string.app_label);
    }
}
