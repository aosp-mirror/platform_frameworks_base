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

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.complication.DreamMediaEntryComplication;
import com.android.systemui.res.R;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

/**
 * Responsible for generating dependencies for the {@link DreamMediaEntryComplication}.
 */
@Subcomponent(modules = DreamMediaEntryComplicationComponent.DreamMediaEntryModule.class)
@DreamMediaEntryComplicationComponent.DreamMediaEntryComplicationScope
public interface DreamMediaEntryComplicationComponent {
    /**
     * Creates a view holder for the media entry complication.
     */
    DreamMediaEntryComplication.DreamMediaEntryViewHolder getViewHolder();

    /**
     * Scope of the media entry complication.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamMediaEntryComplicationScope {}

    /**
     * Factory that generates a {@link DreamMediaEntryComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        DreamMediaEntryComplicationComponent create();
    }

    /**
     * Scoped injected values for the {@link DreamMediaEntryComplicationComponent}.
     */
    @Module
    interface DreamMediaEntryModule {
        String DREAM_MEDIA_ENTRY_VIEW = "dream_media_entry_view";

        /**
         * Provides the dream media entry view.
         */
        @Provides
        @DreamMediaEntryComplicationScope
        @Named(DREAM_MEDIA_ENTRY_VIEW)
        static View provideMediaEntryView(LayoutInflater layoutInflater) {
            return (View) layoutInflater.inflate(R.layout.dream_overlay_media_entry_chip, null);
        }
    }
}
