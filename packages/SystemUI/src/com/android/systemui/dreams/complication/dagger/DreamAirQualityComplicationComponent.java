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

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorInt;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.dreams.complication.DreamAirQualityComplication;
import com.android.systemui.dreams.complication.DreamAirQualityComplication.DreamAirQualityViewHolder;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * Component responsible for generating dependencies for the {@link DreamAirQualityComplication},
 * such as the layout details.
 */
@Subcomponent(modules = {
        DreamAirQualityComplicationComponent.DreamAirQualityComplicationModule.class,
})
@DreamAirQualityComplicationComponent.DreamAirQualityComplicationScope
public interface DreamAirQualityComplicationComponent {

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Scope
    @interface DreamAirQualityComplicationScope {
    }

    /**
     * Generates {@link DreamAirQualityComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        DreamAirQualityComplicationComponent create();
    }

    /**
     * Creates {@link DreamAirQualityViewHolder}.
     */
    DreamAirQualityViewHolder getViewHolder();

    /**
     * Scoped values for {@link DreamAirQualityComplicationComponent}.
     */
    @Module
    interface DreamAirQualityComplicationModule {
        String DREAM_AQI_COMPLICATION_VIEW = "aqi_complication_view";
        String DREAM_AQI_COMPLICATION_LAYOUT_PARAMS = "aqi_complication_layout_params";
        String DREAM_AQI_COLOR_THRESHOLDS = "aqi_color_thresholds";
        String DREAM_AQI_COLOR_VALUES = "aqi_color_values";
        String DREAM_AQI_COLOR_DEFAULT = "aqi_color_default";
        // Order weight of insert into parent container
        int INSERT_ORDER_WEIGHT = 1;

        /**
         * Provides the complication view.
         */
        @Provides
        @DreamAirQualityComplicationScope
        @Named(DREAM_AQI_COMPLICATION_VIEW)
        static TextView provideComplicationView(LayoutInflater layoutInflater) {
            return Objects.requireNonNull((TextView)
                            layoutInflater.inflate(R.layout.dream_overlay_complication_aqi,
                                    null, false),
                    "R.layout.dream_overlay_complication_aqi did not properly inflated");
        }

        /**
         * Provides the layout parameters for the complication view.
         */
        @Provides
        @DreamAirQualityComplicationScope
        @Named(DREAM_AQI_COMPLICATION_LAYOUT_PARAMS)
        static ComplicationLayoutParams provideLayoutParams() {
            return new ComplicationLayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_BOTTOM
                            | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_END,
                    INSERT_ORDER_WEIGHT, /* snapToGuide= */ true);
        }

        @Provides
        @DreamAirQualityComplicationScope
        @Named(DREAM_AQI_COLOR_THRESHOLDS)
        static int[] provideAqiColorThresholds(@Main Resources resources) {
            return resources.getIntArray(R.array.config_dreamAqiThresholds);
        }

        @Provides
        @DreamAirQualityComplicationScope
        @Named(DREAM_AQI_COLOR_VALUES)
        static int[] provideAqiColorValues(@Main Resources resources) {
            return resources.getIntArray(R.array.config_dreamAqiColorValues);
        }

        @Provides
        @DreamAirQualityComplicationScope
        @Named(DREAM_AQI_COLOR_DEFAULT)
        @ColorInt
        static int provideDefaultAqiColor(Context context) {
            return context.getColor(R.color.dream_overlay_aqi_unknown);
        }
    }
}
