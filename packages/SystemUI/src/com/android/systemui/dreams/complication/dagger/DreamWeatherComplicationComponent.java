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


import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.dreams.complication.DreamWeatherComplication.DreamWeatherViewHolder;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * {@link DreamWeatherComplicationComponent} is responsible for generating dependencies surrounding
 * the
 * Clock Date {@link com.android.systemui.dreams.complication.Complication}, such as the layout
 * details.
 */
@Subcomponent(modules = {
        DreamWeatherComplicationComponent.DreamWeatherComplicationModule.class,
})
@DreamWeatherComplicationComponent.DreamWeatherComplicationScope
public interface DreamWeatherComplicationComponent {
    /**
     * Creates {@link DreamWeatherViewHolder}.
     */
    DreamWeatherViewHolder getViewHolder();

    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamWeatherComplicationScope {
    }

    /**
     * Generates {@link DreamWeatherComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        DreamWeatherComplicationComponent create();
    }

    /**
     * Scoped values for {@link DreamWeatherComplicationComponent}.
     */
    @Module
    interface DreamWeatherComplicationModule {
        String DREAM_WEATHER_COMPLICATION_VIEW = "weather_complication_view";
        String DREAM_WEATHER_COMPLICATION_LAYOUT_PARAMS =
                "weather_complication_layout_params";
        String SMARTSPACE_TRAMPOLINE_ACTIVITY_COMPONENT = "smartspace_trampoline_activity";
        // Order weight of insert into parent container
        int INSERT_ORDER_WEIGHT = 1;

        /**
         * Provides the complication view.
         */
        @Provides
        @DreamWeatherComplicationScope
        @Named(DREAM_WEATHER_COMPLICATION_VIEW)
        static TextView provideComplicationView(LayoutInflater layoutInflater) {
            return Preconditions.checkNotNull((TextView)
                            layoutInflater.inflate(R.layout.dream_overlay_complication_weather,
                                    null, false),
                    "R.layout.dream_overlay_complication_weather did not properly inflated");
        }

        /**
         * Provides the layout parameters for the complication view.
         */
        @Provides
        @DreamWeatherComplicationScope
        @Named(DREAM_WEATHER_COMPLICATION_LAYOUT_PARAMS)
        static ComplicationLayoutParams provideLayoutParams() {
            return new ComplicationLayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_BOTTOM
                            | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_END,
                    INSERT_ORDER_WEIGHT);
        }

        /**
         * Provides the smartspace trampoline activity component.
         */
        @Provides
        @DreamWeatherComplicationScope
        @Named(SMARTSPACE_TRAMPOLINE_ACTIVITY_COMPONENT)
        static String provideSmartspaceTrampolineActivityComponent(Context context) {
            return context.getString(R.string.config_smartspaceTrampolineActivityComponent);
        }
    }
}
