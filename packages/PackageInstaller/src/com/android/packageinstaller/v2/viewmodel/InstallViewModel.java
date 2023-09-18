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
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.android.packageinstaller.v2.model.InstallRepository;
import com.android.packageinstaller.v2.model.InstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.model.installstagedata.InstallStaging;


public class InstallViewModel extends AndroidViewModel {

    private static final String TAG = InstallViewModel.class.getSimpleName();
    private final InstallRepository mRepository;
    private final MutableLiveData<InstallStage> mCurrentInstallStage = new MutableLiveData<>(
            new InstallStaging());

    public InstallViewModel(@NonNull Application application, InstallRepository repository) {
        super(application);
        mRepository = repository;
    }

    public MutableLiveData<InstallStage> getCurrentInstallStage() {
        return mCurrentInstallStage;
    }

    public void preprocessIntent(Intent intent, CallerInfo callerInfo) {
        InstallStage stage = mRepository.performPreInstallChecks(intent, callerInfo);
        mCurrentInstallStage.setValue(stage);
    }
}
