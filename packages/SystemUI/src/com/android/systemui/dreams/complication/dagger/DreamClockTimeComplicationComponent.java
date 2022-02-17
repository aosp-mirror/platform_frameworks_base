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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.dreams.complication.DreamClockTimeComplication.DreamClockTimeViewHolder;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * {@link DreamClockTimeComplicationComponent} is responsible for generating dependencies
 * surrounding the
 * Clock Time {@link com.android.systemui.dreams.complication.Complication}, such as the layout
 * details.
 */
@Subcomponent(modules = {
        DreamClockTimeComplicationComponent.DreamClockTimeComplicationModule.class,
})
@DreamClockTimeComplicationComponent.DreamClockTimeComplicationScope
public interface DreamClockTimeComplicationComponent {
    /**
     * Creates {@link DreamClockTimeViewHolder}.
     */
    DreamClockTimeViewHolder getViewHolder();

    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamClockTimeComplicationScope {
    }

    /**
     * Generates {@link DreamClockTimeComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        DreamClockTimeComplicationComponent create();
    }

    /**
     * Scoped values for {@link DreamClockTimeComplicationComponent}.
     */
    @Module
    interface DreamClockTimeComplicationModule {
        String DREAM_CLOCK_TIME_COMPLICATION_VIEW = "clock_time_complication_view";
        String DREAM_CLOCK_TIME_COMPLICATION_LAYOUT_PARAMS =
                "clock_time_complication_layout_params";
        // Order weight of insert into parent container
        int INSERT_ORDER_WEIGHT = 0;
        String TAG_WEIGHT = "'wght' ";
        int WEIGHT = 200;

        /**
         * Provides the complication view.
         */
        @Provides
        @DreamClockTimeComplicationScope
        @Named(DREAM_CLOCK_TIME_COMPLICATION_VIEW)
        static View provideComplicationView(LayoutInflater layoutInflater) {
            final TextClock view = Preconditions.checkNotNull((TextClock)
                            layoutInflater.inflate(R.layout.dream_overlay_complication_clock_time,
                                    null, false),
                    "R.layout.dream_overlay_complication_clock_time did not properly inflated");
            view.setFontVariationSettings(TAG_WEIGHT + WEIGHT);
            return view;
        }

        /**
         * Provides the layout parameters for the complication view.
         */
        @Provides
        @DreamClockTimeComplicationScope
        @Named(DREAM_CLOCK_TIME_COMPLICATION_LAYOUT_PARAMS)
        static ComplicationLayoutParams provideLayoutParams() {
            return new ComplicationLayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_BOTTOM
                            | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_UP,
                    INSERT_ORDER_WEIGHT);
        }
    }
}
