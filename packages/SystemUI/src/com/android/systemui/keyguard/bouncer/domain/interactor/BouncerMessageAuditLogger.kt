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

package com.android.systemui.keyguard.bouncer.domain.interactor

import android.os.Build
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.keyguard.bouncer.shared.model.BouncerMessageModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val TAG = BouncerMessageAuditLogger::class.simpleName!!

/** Logger that echoes bouncer messages state to logcat in debuggable builds. */
@SysUISingleton
class BouncerMessageAuditLogger
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val repository: BouncerMessageRepository,
    private val interactor: BouncerMessageInteractor,
) : CoreStartable {
    override fun start() {
        if (Build.isDebuggable()) {
            collectAndLog(repository.biometricAuthMessage, "biometricMessage: ")
            collectAndLog(repository.primaryAuthMessage, "primaryAuthMessage: ")
            collectAndLog(repository.customMessage, "customMessage: ")
            collectAndLog(repository.faceAcquisitionMessage, "faceAcquisitionMessage: ")
            collectAndLog(
                repository.fingerprintAcquisitionMessage,
                "fingerprintAcquisitionMessage: "
            )
            collectAndLog(repository.authFlagsMessage, "authFlagsMessage: ")
            collectAndLog(interactor.bouncerMessage, "interactor.bouncerMessage: ")
        }
    }

    private fun collectAndLog(flow: Flow<BouncerMessageModel?>, context: String) {
        scope.launch { flow.collect { Log.d(TAG, context + it) } }
    }
}
