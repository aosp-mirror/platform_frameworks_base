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


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.dreams.complication.DreamClockDateComplication;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Module for providing {@link DreamClockDateComplication}.
 */
@Module
public interface DreamClockDateComplicationModule {
    String DREAM_CLOCK_DATE_COMPLICATION_VIEW = "clock_date_complication_view";
    String DREAM_CLOCK_DATE_COMPLICATION_LAYOUT_PARAMS =
            "clock_date_complication_layout_params";
    // Order weight of insert into parent container
    //TODO(b/217199227): move to a single location.
    int INSERT_ORDER_WEIGHT = 2;

    /**
     * Provides the complication view.
     */
    @Provides
    @Named(DREAM_CLOCK_DATE_COMPLICATION_VIEW)
    static View provideComplicationView(LayoutInflater layoutInflater) {
        return Preconditions.checkNotNull(
                layoutInflater.inflate(R.layout.dream_overlay_complication_clock_date,
                        null, false),
                "R.layout.dream_overlay_complication_clock_date did not properly inflated");
    }

    /**
     * Provides the layout parameters for the complication view.
     */
    @Provides
    @Named(DREAM_CLOCK_DATE_COMPLICATION_LAYOUT_PARAMS)
    static ComplicationLayoutParams provideLayoutParams() {
        return new ComplicationLayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ComplicationLayoutParams.POSITION_BOTTOM
                        | ComplicationLayoutParams.POSITION_START,
                ComplicationLayoutParams.DIRECTION_END,
                INSERT_ORDER_WEIGHT);
    }
}
