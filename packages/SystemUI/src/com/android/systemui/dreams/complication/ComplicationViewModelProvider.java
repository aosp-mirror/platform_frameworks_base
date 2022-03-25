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
package com.android.systemui.dreams.complication;

import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;

import com.android.systemui.dreams.complication.dagger.DaggerViewModelProviderFactory;

import javax.inject.Inject;

/**
 * An intermediary to generate {@link ComplicationViewModel} tracked with a {@link ViewModelStore}.
 */
public class ComplicationViewModelProvider extends ViewModelProvider {
    @Inject
    public ComplicationViewModelProvider(ViewModelStore store, ComplicationViewModel viewModel) {
        super(store, new DaggerViewModelProviderFactory(() -> viewModel));
    }
}
