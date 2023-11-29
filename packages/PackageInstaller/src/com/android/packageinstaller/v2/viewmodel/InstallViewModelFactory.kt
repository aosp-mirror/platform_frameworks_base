/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.android.packageinstaller.v2.model.InstallRepository;

public class InstallViewModelFactory extends ViewModelProvider.AndroidViewModelFactory {

    private final InstallRepository mRepository;
    private final Application mApplication;

    public InstallViewModelFactory(Application application, InstallRepository repository) {
        // Calling super class' ctor ensures that create method is called correctly and the right
        // ctor of InstallViewModel is used. If we fail to do that, the default ctor:
        // InstallViewModel(application) is used, and repository isn't initialized in the viewmodel
        super(application);
        mApplication = application;
        mRepository = repository;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        return (T) new InstallViewModel(mApplication, mRepository);
    }
}
