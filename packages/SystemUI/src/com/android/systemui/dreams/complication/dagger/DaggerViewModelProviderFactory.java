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

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

/**
 * {@link DaggerViewModelProviderFactory} is a wrapper around a lambda allowing for uses of Dagger
 * component.
 */
public class DaggerViewModelProviderFactory implements ViewModelProvider.Factory {
    /**
     * An interface for providing a {@link ViewModel} through
     * {@link DaggerViewModelProviderFactory}.
     */
    public interface ViewModelCreator {
        /**
         * Creates a {@link ViewModel} to be returned.
         */
        ViewModel create();
    }

    private final ViewModelCreator mCreator;

    public DaggerViewModelProviderFactory(ViewModelCreator creator) {
        mCreator = creator;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> aClass) {
        return (T) mCreator.create();
    }
}
