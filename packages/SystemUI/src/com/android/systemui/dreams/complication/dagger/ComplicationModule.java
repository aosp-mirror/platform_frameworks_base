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

import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.complication.ComplicationCollectionViewModel;
import com.android.systemui.dreams.complication.ComplicationLayoutEngine;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.Module;
import dagger.Provides;

/**
 * Module for housing components related to rendering complications.
 */
@Module(includes = {
        ComplicationHostViewModule.class,
        }, subcomponents = {
        ComplicationViewModelComponent.class,
})
public interface ComplicationModule {
    String SCOPED_COMPLICATIONS_MODEL = "scoped_complications_model";

    /** Scope annotation for singleton items within the {@link ComplicationModule}. */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface ComplicationScope {}

    /**
     * The complication collection is provided through this way to ensure that the instances are
     * tied to the {@link ViewModelStore}.
     */
    @Provides
    @Named(SCOPED_COMPLICATIONS_MODEL)
    static ComplicationCollectionViewModel providesComplicationCollectionViewModel(
            ViewModelStore store, ComplicationCollectionViewModel viewModel) {
        final ViewModelProvider provider = new ViewModelProvider(store,
                new DaggerViewModelProviderFactory(() -> viewModel));

        return provider.get(ComplicationCollectionViewModel.class);
    }

    /**
     * Provides the visibility controller for display complications.
     */
    @Provides
    static Complication.VisibilityController providesVisibilityController(
            ComplicationLayoutEngine engine) {
        return engine;
    }
}
