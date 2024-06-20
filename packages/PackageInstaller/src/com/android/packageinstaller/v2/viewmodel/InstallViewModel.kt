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

package com.android.packageinstaller.v2.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import com.android.packageinstaller.v2.model.InstallRepository
import com.android.packageinstaller.v2.model.InstallStage
import com.android.packageinstaller.v2.model.InstallStaging

class InstallViewModel(application: Application, val repository: InstallRepository) :
    AndroidViewModel(application) {

    companion object {
        private val LOG_TAG = InstallViewModel::class.java.simpleName
    }

    private val _currentInstallStage = MediatorLiveData<InstallStage>(InstallStaging())
    val currentInstallStage: MutableLiveData<InstallStage>
        get() = _currentInstallStage

    init {
        // Since installing is an async operation, we may get the install result later in time.
        // Result of the installation will be set in InstallRepository#installResult.
        // As such, currentInstallStage will need to add another MutableLiveData as a data source
        _currentInstallStage.addSource(
            repository.installResult.distinctUntilChanged()
        ) { installStage: InstallStage? ->
            if (installStage != null) {
                _currentInstallStage.value = installStage
            }
        }
    }

    fun preprocessIntent(intent: Intent, callerInfo: InstallRepository.CallerInfo) {
        val stage = repository.performPreInstallChecks(intent, callerInfo)
        if (stage.stageCode == InstallStage.STAGE_ABORTED) {
            _currentInstallStage.value = stage
        } else {
            // Since staging is an async operation, we will get the staging result later in time.
            // Result of the file staging will be set in InstallRepository#mStagingResult.
            // As such, mCurrentInstallStage will need to add another MutableLiveData
            // as a data source
            repository.stageForInstall()
            _currentInstallStage.addSource(repository.stagingResult) { installStage: InstallStage ->
                if (installStage.stageCode != InstallStage.STAGE_READY) {
                    _currentInstallStage.value = installStage
                } else {
                    checkIfAllowedAndInitiateInstall()
                }
            }
        }
    }

    val stagingProgress: LiveData<Int>
        get() = repository.stagingProgress

    private fun checkIfAllowedAndInitiateInstall() {
        val stage = repository.requestUserConfirmation()
        if (stage != null) {
            _currentInstallStage.value = stage
        }
    }

    fun forcedSkipSourceCheck() {
        val stage = repository.forcedSkipSourceCheck()
        if (stage != null) {
            _currentInstallStage.value = stage
        }
    }

    fun cleanupInstall() {
        repository.cleanupInstall()
    }

    fun reattemptInstall() {
        val stage = repository.reattemptInstall()
        _currentInstallStage.value = stage
    }

    fun initiateInstall() {
        repository.initiateInstall()
    }

    val stagedSessionId: Int
        get() = repository.stagedSessionId
}
