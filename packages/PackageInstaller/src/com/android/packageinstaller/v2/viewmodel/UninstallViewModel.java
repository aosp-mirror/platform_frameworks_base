/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import com.android.packageinstaller.v2.model.UninstallRepository;
import com.android.packageinstaller.v2.model.UninstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallStage;

public class UninstallViewModel extends AndroidViewModel {

    private static final String TAG = UninstallViewModel.class.getSimpleName();
    private final UninstallRepository mRepository;
    private final MutableLiveData<UninstallStage> mCurrentUninstallStage = new MutableLiveData<>();

    public UninstallViewModel(@NonNull Application application, UninstallRepository repository) {
        super(application);
        mRepository = repository;
    }

    public MutableLiveData<UninstallStage> getCurrentUninstallStage() {
        return mCurrentUninstallStage;
    }

    public void preprocessIntent(Intent intent, CallerInfo callerInfo) {
        UninstallStage stage = mRepository.performPreUninstallChecks(intent, callerInfo);
        if (stage.getStageCode() != UninstallStage.STAGE_ABORTED) {
            stage = mRepository.generateUninstallDetails();
        }
        mCurrentUninstallStage.setValue(stage);
    }
}
