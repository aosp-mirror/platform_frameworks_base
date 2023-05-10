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

package com.android.systemui.complication.dagger;

import android.content.res.Resources;
import android.view.LayoutInflater;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;

import dagger.Module;
import dagger.Provides;

import javax.inject.Named;

/**
 * Module for providing a scoped host view.
 */
@Module
public abstract class ComplicationHostViewModule {
    public static final String SCOPED_COMPLICATIONS_LAYOUT = "scoped_complications_layout";
    public static final String COMPLICATION_DIRECTIONAL_SPACING_DEFAULT =
            "complication_directional_spacing_default";
    public static final String COMPLICATIONS_FADE_OUT_DURATION = "complications_fade_out_duration";
    public static final String COMPLICATIONS_FADE_IN_DURATION = "complications_fade_in_duration";
    public static final String COMPLICATIONS_RESTORE_TIMEOUT = "complication_restore_timeout";
    public static final String COMPLICATIONS_FADE_OUT_DELAY = "complication_fade_out_delay";
    public static final String COMPLICATION_MARGIN_POSITION_START =
            "complication_margin_position_start";
    public static final String COMPLICATION_MARGIN_POSITION_TOP =
            "complication_margin_position_top";
    public static final String COMPLICATION_MARGIN_POSITION_END =
            "complication_margin_position_end";
    public static final String COMPLICATION_MARGIN_POSITION_BOTTOM =
            "complication_margin_position_bottom";

    /**
     * Generates a {@link ConstraintLayout}, which can host
     * {@link com.android.systemui.complication.Complication} instances.
     */
    @Provides
    @Named(SCOPED_COMPLICATIONS_LAYOUT)
    @ComplicationModule.ComplicationScope
    static ConstraintLayout providesComplicationHostView(
            LayoutInflater layoutInflater) {
        return Preconditions.checkNotNull((ConstraintLayout)
                        layoutInflater.inflate(R.layout.dream_overlay_complications_layer,
                                null),
                "R.layout.dream_overlay_complications_layer did not properly inflated");
    }

    @Provides
    @Named(COMPLICATION_DIRECTIONAL_SPACING_DEFAULT)
    static int providesComplicationPadding(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_complication_margin);
    }

    @Provides
    @Named(COMPLICATION_MARGIN_POSITION_START)
    static int providesComplicationMarginPositionStart(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_container_padding_start);
    }

    @Provides
    @Named(COMPLICATION_MARGIN_POSITION_TOP)
    static int providesComplicationMarginPositionTop(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_container_padding_top);
    }

    @Provides
    @Named(COMPLICATION_MARGIN_POSITION_END)
    static int providesComplicationMarginPositionEnd(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_container_padding_end);
    }

    @Provides
    @Named(COMPLICATION_MARGIN_POSITION_BOTTOM)
    static int providesComplicationMarginPositionBottom(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_container_padding_bottom);
    }

    /**
     * Provides the fade out duration for complications.
     */
    @Provides
    @Named(COMPLICATIONS_FADE_OUT_DURATION)
    static int providesComplicationsFadeOutDuration(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationFadeOutMs);
    }

    /**
     * Provides the delay to wait for before fading out complications.
     */
    @Provides
    @Named(COMPLICATIONS_FADE_OUT_DELAY)
    static int providesComplicationsFadeOutDelay(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationFadeOutDelayMs);
    }

    /**
     * Provides the fade in duration for complications.
     */
    @Provides
    @Named(COMPLICATIONS_FADE_IN_DURATION)
    static int providesComplicationsFadeInDuration(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationFadeInMs);
    }

    /**
     * Provides the timeout for restoring complication visibility.
     */
    @Provides
    @Named(COMPLICATIONS_RESTORE_TIMEOUT)
    static int providesComplicationsRestoreTimeout(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationRestoreMs);
    }
}
