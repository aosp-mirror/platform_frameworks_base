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
import android.widget.TextClock;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dreams.complication.DreamClockTimeComplication;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Module for providing {@link DreamClockTimeComplication}.
 */
@Module
public interface DreamClockTimeComplicationModule {
    String DREAM_CLOCK_TIME_COMPLICATION_VIEW = "clock_time_complication_view";
    String TAG_WEIGHT = "'wght' ";
    int WEIGHT = 400;

    /**
     * Provides the complication view.
     */
    @Provides
    @Named(DREAM_CLOCK_TIME_COMPLICATION_VIEW)
    static View provideComplicationView(LayoutInflater layoutInflater) {
        final View view = Preconditions.checkNotNull(
                        layoutInflater.inflate(R.layout.dream_overlay_complication_clock_time,
                                null, false),
                "R.layout.dream_overlay_complication_clock_time did not properly inflated");
        ((TextClock) view.findViewById(R.id.time_view)).setFontVariationSettings(
                TAG_WEIGHT + WEIGHT);
        return view;
    }
}
