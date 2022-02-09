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

package com.android.systemui.media.dream.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.dreams.complication.ComplicationLayoutParams;
import com.android.systemui.media.dream.MediaViewHolder;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * {@link MediaComplicationComponent} is responsible for generating dependencies surrounding the
 * media {@link com.android.systemui.dreams.complication.Complication}, such as view controllers
 * and layout details.
 */
@Subcomponent(modules = {
        MediaComplicationComponent.MediaComplicationModule.class,
})
@MediaComplicationComponent.MediaComplicationScope
public interface MediaComplicationComponent {
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface MediaComplicationScope {}

    /**
     * Generates {@link MediaComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        MediaComplicationComponent create();
    }

    /**
     * Creates {@link MediaViewHolder}.
     */
    MediaViewHolder getViewHolder();

    /**
     * Scoped values for {@link MediaComplicationComponent}.
     */
    @Module
    interface MediaComplicationModule {
        String MEDIA_COMPLICATION_CONTAINER = "media_complication_container";
        String MEDIA_COMPLICATION_LAYOUT_PARAMS = "media_complication_layout_params";

        /**
         * Provides the complication view.
         */
        @Provides
        @MediaComplicationScope
        @Named(MEDIA_COMPLICATION_CONTAINER)
        static FrameLayout provideComplicationContainer(Context context) {
            return new FrameLayout(context);
        }

        /**
         * Provides the layout parameters for the complication view.
         */
        @Provides
        @MediaComplicationScope
        @Named(MEDIA_COMPLICATION_LAYOUT_PARAMS)
        static ComplicationLayoutParams provideLayoutParams() {
            return new ComplicationLayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ComplicationLayoutParams.POSITION_TOP
                            | ComplicationLayoutParams.POSITION_START,
                    ComplicationLayoutParams.DIRECTION_DOWN,
                    0,
                    true);
        }
    }
}
