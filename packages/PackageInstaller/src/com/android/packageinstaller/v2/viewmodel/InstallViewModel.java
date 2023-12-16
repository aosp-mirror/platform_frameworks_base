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
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import com.android.packageinstaller.v2.model.InstallRepository;
import com.android.packageinstaller.v2.model.InstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.model.installstagedata.InstallStaging;


public class InstallViewModel extends AndroidViewModel {

    private static final String TAG = InstallViewModel.class.getSimpleName();
    private final InstallRepository mRepository;
    private final MediatorLiveData<InstallStage> mCurrentInstallStage = new MediatorLiveData<>(
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
        if (stage.getStageCode() == InstallStage.STAGE_ABORTED) {
            mCurrentInstallStage.setValue(stage);
        } else {
            // Since staging is an async operation, we will get the staging result later in time.
            // Result of the file staging will be set in InstallRepository#mStagingResult.
            // As such, mCurrentInstallStage will need to add another MutableLiveData
            // as a data source
            mRepository.stageForInstall();
            mCurrentInstallStage.addSource(mRepository.getStagingResult(), installStage -> {
                if (installStage.getStageCode() != InstallStage.STAGE_READY) {
                    mCurrentInstallStage.setValue(installStage);
                } else {
                    checkIfAllowedAndInitiateInstall();
                }
            });
        }
    }

    public MutableLiveData<Integer> getStagingProgress() {
        return mRepository.getStagingProgress();
    }

    private void checkIfAllowedAndInitiateInstall() {
        InstallStage stage = mRepository.requestUserConfirmation();
        mCurrentInstallStage.setValue(stage);
    }

    public void forcedSkipSourceCheck() {
        InstallStage stage = mRepository.forcedSkipSourceCheck();
        mCurrentInstallStage.setValue(stage);
    }

    public void cleanupInstall() {
        mRepository.cleanupInstall();
    }

    public void reattemptInstall() {
        InstallStage stage = mRepository.reattemptInstall();
        mCurrentInstallStage.setValue(stage);
    }

    public void initiateInstall() {
        // Since installing is an async operation, we will get the install result later in time.
        // Result of the installation will be set in InstallRepository#mInstallResult.
        // As such, mCurrentInstallStage will need to add another MutableLiveData as a data source
        mRepository.initiateInstall();
        mCurrentInstallStage.addSource(mRepository.getInstallResult(), installStage -> {
            if (installStage != null) {
                mCurrentInstallStage.setValue(installStage);
            }
        });
    }

    public int getStagedSessionId() {
        return mRepository.getStagedSessionId();
    }
}
