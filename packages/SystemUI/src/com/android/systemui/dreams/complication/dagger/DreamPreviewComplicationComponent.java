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
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.dreams.complication.ComplicationViewModel;
import com.android.systemui.dreams.complication.DreamPreviewComplication.DreamPreviewViewHolder;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.BindsInstance;
import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * {@link DreamPreviewComplicationComponent} is responsible for generating dependencies
 * surrounding the
 * Preview {@link com.android.systemui.dreams.complication.Complication}, such as the layout
 * details.
 */
@Subcomponent(modules = {
        DreamPreviewComplicationComponent.DreamPreviewComplicationModule.class,
})
@DreamPreviewComplicationComponent.DreamPreviewComplicationScope
public interface DreamPreviewComplicationComponent {
    String DREAM_LABEL = "dream_label";

    /**
     * Creates {@link DreamPreviewViewHolder}.
     */
    DreamPreviewViewHolder getViewHolder();

    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamPreviewComplicationScope {
    }

    /**
     * Generates {@link DreamPreviewComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        DreamPreviewComplicationComponent create(
                @BindsInstance ComplicationViewModel viewModel,
                @Named(DREAM_LABEL) @BindsInstance @Nullable CharSequence dreamLabel);
    }

    /**
     * Scoped values for {@link DreamPreviewComplicationComponent}.
     */
    @Module
    interface DreamPreviewComplicationModule {
        String DREAM_PREVIEW_COMPLICATION_VIEW = "preview_complication_view";
        String DREAM_PREVIEW_COMPLICATION_LAYOUT_PARAMS = "preview_complication_layout_params";
        // Order weight of insert into parent container
        int INSERT_ORDER_WEIGHT = 1000;

        /**
         * Provides the complication view.
         */
        @Provides
        @DreamPreviewComplicationScope
        @Named(DREAM_PREVIEW_COMPLICATION_VIEW)
        static TextView provideComplicationView(LayoutInflater layoutInflater) {
            return Preconditions.checkNotNull((TextView)
                            layoutInflater.inflate(R.layout.dream_overlay_complication_preview,
                                    null, false),
                    "R.layout.dream_overlay_complication_preview did not properly inflated");
        }

        /**
         * Provides the layout parameters for the complication view.
         */
        @Provides
        @DreamPreviewComplicationScope
        @Named(DREAM_PREVIEW_COMPLICATION_LAYOUT_PARAMS)
        static ComplicationLayoutParams provideLayoutParams() {
            return new ComplicationLayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_TOP
                            | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_DOWN,
                    INSERT_ORDER_WEIGHT, /* snapToGuide= */ true);
        }
    }
}
