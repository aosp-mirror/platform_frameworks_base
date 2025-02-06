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

package com.android.systemui.lowlightclock.dagger;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.hardware.Sensor;

import com.android.dream.lowlight.dagger.LowLightDreamModule;
import com.android.systemui.CoreStartable;
import com.android.systemui.communal.DeviceInactiveCondition;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogBufferFactory;
import com.android.systemui.lowlightclock.AmbientLightModeMonitor;
import com.android.systemui.lowlightclock.DirectBootCondition;
import com.android.systemui.lowlightclock.ForceLowLightCondition;
import com.android.systemui.lowlightclock.LowLightCondition;
import com.android.systemui.lowlightclock.LowLightDisplayController;
import com.android.systemui.lowlightclock.LowLightMonitor;
import com.android.systemui.lowlightclock.ScreenSaverEnabledCondition;
import com.android.systemui.res.R;
import com.android.systemui.shared.condition.Condition;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;

import javax.inject.Named;

@Module(includes = LowLightDreamModule.class)
public abstract class LowLightModule {
    public static final String Y_TRANSLATION_ANIMATION_OFFSET =
            "y_translation_animation_offset";
    public static final String Y_TRANSLATION_ANIMATION_DURATION_MILLIS =
            "y_translation_animation_duration_millis";
    public static final String ALPHA_ANIMATION_IN_START_DELAY_MILLIS =
            "alpha_animation_in_start_delay_millis";
    public static final String ALPHA_ANIMATION_DURATION_MILLIS =
            "alpha_animation_duration_millis";
    public static final String LOW_LIGHT_PRECONDITIONS = "low_light_preconditions";
    public static final String LIGHT_SENSOR = "low_light_monitor_light_sensor";


    /**
     * Provides a {@link LogBuffer} for logs related to low-light features.
     */
    @Provides
    @SysUISingleton
    @LowLightLog
    public static LogBuffer provideLowLightLogBuffer(LogBufferFactory factory) {
        return factory.create("LowLightLog", 250);
    }

    @Binds
    @IntoSet
    @Named(LOW_LIGHT_PRECONDITIONS)
    abstract Condition bindScreenSaverEnabledCondition(ScreenSaverEnabledCondition condition);

    @Provides
    @IntoSet
    @Named(com.android.systemui.lowlightclock.dagger.LowLightModule.LOW_LIGHT_PRECONDITIONS)
    static Condition provideLowLightCondition(LowLightCondition lowLightCondition,
            DirectBootCondition directBootCondition) {
        // Start lowlight if we are either in lowlight or in direct boot. The ordering of the
        // conditions matters here since we don't want to start the lowlight condition if
        // we are in direct boot mode.
        return directBootCondition.or(lowLightCondition);
    }

    @Binds
    @IntoSet
    @Named(LOW_LIGHT_PRECONDITIONS)
    abstract Condition bindForceLowLightCondition(ForceLowLightCondition condition);

    @Binds
    @IntoSet
    @Named(LOW_LIGHT_PRECONDITIONS)
    abstract Condition bindDeviceInactiveCondition(DeviceInactiveCondition condition);

    @BindsOptionalOf
    abstract LowLightDisplayController bindsLowLightDisplayController();

    @BindsOptionalOf
    @Nullable
    @Named(LIGHT_SENSOR)
    abstract Sensor bindsLightSensor();

    @BindsOptionalOf
    abstract AmbientLightModeMonitor.DebounceAlgorithm bindsDebounceAlgorithm();

    /**
     *
     */
    @Provides
    @Named(Y_TRANSLATION_ANIMATION_OFFSET)
    static int providesAnimationInOffset(@Main Resources resources) {
        return resources.getDimensionPixelOffset(
                R.dimen.low_light_clock_translate_animation_offset);
    }

    /**
     *
     */
    @Provides
    @Named(Y_TRANSLATION_ANIMATION_DURATION_MILLIS)
    static long providesAnimationDurationMillis(@Main Resources resources) {
        return resources.getInteger(R.integer.low_light_clock_translate_animation_duration_ms);
    }

    /**
     *
     */
    @Provides
    @Named(ALPHA_ANIMATION_IN_START_DELAY_MILLIS)
    static long providesAlphaAnimationInStartDelayMillis(@Main Resources resources) {
        return resources.getInteger(R.integer.low_light_clock_alpha_animation_in_start_delay_ms);
    }

    /**
     *
     */
    @Provides
    @Named(ALPHA_ANIMATION_DURATION_MILLIS)
    static long providesAlphaAnimationDurationMillis(@Main Resources resources) {
        return resources.getInteger(R.integer.low_light_clock_alpha_animation_duration_ms);
    }
    /** Inject into LowLightMonitor. */
    @Binds
    @IntoMap
    @ClassKey(LowLightMonitor.class)
    abstract CoreStartable bindLowLightMonitor(LowLightMonitor lowLightMonitor);
}
