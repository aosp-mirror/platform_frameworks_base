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

import android.content.ContentResolver;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayContainerView;
import com.android.systemui.dreams.DreamOverlayStatusBarView;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/** Dagger module for {@link DreamOverlayComponent}. */
@Module
public abstract class DreamOverlayModule {
    private static final String DREAM_OVERLAY_BATTERY_VIEW = "dream_overlay_battery_view";
    public static final String DREAM_OVERLAY_BATTERY_CONTROLLER =
            "dream_overlay_battery_controller";
    public static final String DREAM_OVERLAY_CONTENT_VIEW = "dream_overlay_content_view";

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    public static DreamOverlayContainerView providesDreamOverlayContainerView(
            LayoutInflater layoutInflater) {
        return Preconditions.checkNotNull((DreamOverlayContainerView)
                layoutInflater.inflate(R.layout.dream_overlay_container, null),
                "R.layout.dream_layout_container could not be properly inflated");
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    @Named(DREAM_OVERLAY_CONTENT_VIEW)
    public static ViewGroup providesDreamOverlayContentView(DreamOverlayContainerView view) {
        return Preconditions.checkNotNull(view.findViewById(R.id.dream_overlay_content),
                "R.id.dream_overlay_content must not be null");
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    public static DreamOverlayStatusBarView providesDreamOverlayStatusBarView(
            DreamOverlayContainerView view) {
        return Preconditions.checkNotNull(view.findViewById(R.id.dream_overlay_status_bar),
                "R.id.status_bar must not be null");
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    @Named(DREAM_OVERLAY_BATTERY_VIEW)
    static BatteryMeterView providesBatteryMeterView(DreamOverlayContainerView view) {
        return Preconditions.checkNotNull(view.findViewById(R.id.dream_overlay_battery),
                "R.id.battery must not be null");
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    @Named(DREAM_OVERLAY_BATTERY_CONTROLLER)
    static BatteryMeterViewController providesBatteryMeterViewController(
            @Named(DREAM_OVERLAY_BATTERY_VIEW) BatteryMeterView batteryMeterView,
            ConfigurationController configurationController,
            TunerService tunerService,
            BroadcastDispatcher broadcastDispatcher,
            @Main Handler mainHandler,
            ContentResolver contentResolver,
            BatteryController batteryController) {
        return new BatteryMeterViewController(
                batteryMeterView,
                configurationController,
                tunerService,
                broadcastDispatcher,
                mainHandler,
                contentResolver,
                batteryController);
    }
}
