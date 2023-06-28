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

package com.android.systemui.dreams.complication.dagger;

import android.content.res.Resources;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.dagger.SystemUIBinder;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Module for all components with corresponding dream layer complications registered in
 * {@link SystemUIBinder}.
 */
@Module(includes = {
                DreamClockTimeComplicationModule.class,
        },
        subcomponents = {
                DreamHomeControlsComplicationComponent.class,
                DreamMediaEntryComplicationComponent.class
        })
public interface RegisteredComplicationsModule {
    String DREAM_CLOCK_TIME_COMPLICATION_LAYOUT_PARAMS = "time_complication_layout_params";
    String DREAM_SMARTSPACE_LAYOUT_PARAMS = "smartspace_layout_params";
    String DREAM_HOME_CONTROLS_CHIP_LAYOUT_PARAMS = "home_controls_chip_layout_params";
    String DREAM_MEDIA_ENTRY_LAYOUT_PARAMS = "media_entry_layout_params";

    int DREAM_CLOCK_TIME_COMPLICATION_WEIGHT = 1;
    int DREAM_CLOCK_TIME_COMPLICATION_WEIGHT_NO_SMARTSPACE = 2;
    int DREAM_SMARTSPACE_COMPLICATION_WEIGHT = 2;
    int DREAM_MEDIA_COMPLICATION_WEIGHT = 0;
    int DREAM_HOME_CONTROLS_CHIP_COMPLICATION_WEIGHT = 4;
    int DREAM_MEDIA_ENTRY_COMPLICATION_WEIGHT = 3;
    int DREAM_WEATHER_COMPLICATION_WEIGHT = 0;

    /**
     * Provides layout parameters for the clock time complication.
     */
    @Provides
    @Named(DREAM_CLOCK_TIME_COMPLICATION_LAYOUT_PARAMS)
    static ComplicationLayoutParams provideClockTimeLayoutParams(FeatureFlags featureFlags) {
        if (featureFlags.isEnabled(Flags.HIDE_SMARTSPACE_ON_DREAM_OVERLAY)) {
            return new ComplicationLayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_BOTTOM
                            | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_END,
                    DREAM_CLOCK_TIME_COMPLICATION_WEIGHT_NO_SMARTSPACE);
        }
        return new ComplicationLayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ComplicationLayoutParams.POSITION_BOTTOM
                        | ComplicationLayoutParams.POSITION_START,
                ComplicationLayoutParams.DIRECTION_UP,
                DREAM_CLOCK_TIME_COMPLICATION_WEIGHT,
                0 /*margin*/);
    }

    /**
     * Provides layout parameters for the home controls complication.
     */
    @Provides
    @Named(DREAM_HOME_CONTROLS_CHIP_LAYOUT_PARAMS)
    static ComplicationLayoutParams provideHomeControlsChipLayoutParams() {
        return new ComplicationLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ComplicationLayoutParams.POSITION_BOTTOM
                        | ComplicationLayoutParams.POSITION_START,
                ComplicationLayoutParams.DIRECTION_END,
                DREAM_HOME_CONTROLS_CHIP_COMPLICATION_WEIGHT);
    }

    /**
     * Provides layout parameters for the media entry complication.
     */
    @Provides
    @Named(DREAM_MEDIA_ENTRY_LAYOUT_PARAMS)
    static ComplicationLayoutParams provideMediaEntryLayoutParams(@Main Resources res) {
        return new ComplicationLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ComplicationLayoutParams.POSITION_BOTTOM
                        | ComplicationLayoutParams.POSITION_START,
                ComplicationLayoutParams.DIRECTION_END,
                DREAM_MEDIA_ENTRY_COMPLICATION_WEIGHT);
    }

    /**
     * Provides layout parameters for the smartspace complication.
     */
    @Provides
    @Named(DREAM_SMARTSPACE_LAYOUT_PARAMS)
    static ComplicationLayoutParams provideSmartspaceLayoutParams(@Main Resources res) {
        return new ComplicationLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ComplicationLayoutParams.POSITION_BOTTOM
                        | ComplicationLayoutParams.POSITION_START,
                ComplicationLayoutParams.DIRECTION_END,
                DREAM_SMARTSPACE_COMPLICATION_WEIGHT,
                res.getDimensionPixelSize(R.dimen.dream_overlay_complication_smartspace_padding),
                res.getDimensionPixelSize(R.dimen.dream_overlay_complication_smartspace_max_width));
    }
}
