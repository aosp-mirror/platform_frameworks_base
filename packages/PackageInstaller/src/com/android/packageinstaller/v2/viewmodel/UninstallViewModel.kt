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

package com.android.packageinstaller.v2.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.android.packageinstaller.v2.model.UninstallRepository
import com.android.packageinstaller.v2.model.UninstallStage

class UninstallViewModel(application: Application, val repository: UninstallRepository) :
    AndroidViewModel(application) {

    companion object {
        private val LOG_TAG = UninstallViewModel::class.java.simpleName
    }

    private val _currentUninstallStage = MediatorLiveData<UninstallStage>()
    val currentUninstallStage: MutableLiveData<UninstallStage>
        get() = _currentUninstallStage

    fun preprocessIntent(intent: Intent, callerInfo: UninstallRepository.CallerInfo) {
        var stage = repository.performPreUninstallChecks(intent, callerInfo)
        if (stage.stageCode != UninstallStage.STAGE_ABORTED) {
            stage = repository.generateUninstallDetails()
        }
        _currentUninstallStage.value = stage
    }

    fun initiateUninstall(keepData: Boolean) {
        repository.initiateUninstall(keepData)
        // Since uninstall is an async operation, we will get the uninstall result later in time.
        // Result of the uninstall will be set in UninstallRepository#mUninstallResult.
        // As such, _currentUninstallStage will need to add another MutableLiveData
        // as a data source
        _currentUninstallStage.addSource(repository.uninstallResult) { uninstallStage: UninstallStage? ->
            if (uninstallStage != null) {
                _currentUninstallStage.value = uninstallStage
            }
        }
    }

    fun cancelInstall() {
        repository.cancelInstall()
    }
}
