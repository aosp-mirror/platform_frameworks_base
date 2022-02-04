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
import android.view.LayoutInflater;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Module for providing a scoped host view.
 */
@Module
public abstract class ComplicationHostViewModule {
    public static final String SCOPED_COMPLICATIONS_LAYOUT = "scoped_complications_layout";
    public static final String COMPLICATION_MARGIN = "complication_margin";
    public static final String COMPLICATIONS_FADE_OUT_DURATION = "complications_fade_out_duration";
    public static final String COMPLICATIONS_FADE_IN_DURATION = "complications_fade_in_duration";
    public static final String COMPLICATIONS_RESTORE_TIMEOUT = "complication_restore_timeout";

    /**
     * Generates a {@link ConstraintLayout}, which can host
     * {@link com.android.systemui.dreams.complication.Complication} instances.
     */
    @Provides
    @Named(SCOPED_COMPLICATIONS_LAYOUT)
    @DreamOverlayComponent.DreamOverlayScope
    static ConstraintLayout providesComplicationHostView(
            LayoutInflater layoutInflater) {
        return Preconditions.checkNotNull((ConstraintLayout)
                        layoutInflater.inflate(R.layout.dream_overlay_complications_layer,
                                null),
                "R.layout.dream_overlay_complications_layer did not properly inflated");
    }

    @Provides
    @Named(COMPLICATION_MARGIN)
    @DreamOverlayComponent.DreamOverlayScope
    static int providesComplicationPadding(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_complication_margin);
    }

    /**
     * Provides the fade out duration for complications.
     */
    @Provides
    @Named(COMPLICATIONS_FADE_OUT_DURATION)
    @DreamOverlayComponent.DreamOverlayScope
    static int providesComplicationsFadeOutDuration(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationFadeOutMs);
    }

    /**
     * Provides the fade in duration for complications.
     */
    @Provides
    @Named(COMPLICATIONS_FADE_IN_DURATION)
    @DreamOverlayComponent.DreamOverlayScope
    static int providesComplicationsFadeInDuration(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationFadeInMs);
    }

    /**
     * Provides the timeout for restoring complication visibility.
     */
    @Provides
    @Named(COMPLICATIONS_RESTORE_TIMEOUT)
    @DreamOverlayComponent.DreamOverlayScope
    static int providesComplicationsRestoreTimeout(@Main Resources resources) {
        return resources.getInteger(R.integer.complicationRestoreMs);
    }
}
