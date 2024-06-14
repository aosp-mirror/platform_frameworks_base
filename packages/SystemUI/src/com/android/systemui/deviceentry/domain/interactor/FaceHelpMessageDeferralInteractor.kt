/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.deviceentry.domain.interactor

import android.hardware.face.FaceManager
import com.android.systemui.biometrics.FaceHelpMessageDeferralFactory
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.deviceentry.shared.model.AcquiredFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * FaceHelpMessageDeferral business logic. Processes face acquired and face help authentication
 * events to determine whether a face auth event should be displayed to the user immediately or when
 * a [FaceManager.FACE_ERROR_TIMEOUT] is received.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class FaceHelpMessageDeferralInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    faceHelpMessageDeferralFactory: FaceHelpMessageDeferralFactory,
) {
    private val faceHelpMessageDeferral = faceHelpMessageDeferralFactory.create()
    private val faceAcquired: Flow<AcquiredFaceAuthenticationStatus> =
        faceAuthInteractor.authenticationStatus.filterIsInstance<AcquiredFaceAuthenticationStatus>()
    private val faceHelp: Flow<HelpFaceAuthenticationStatus> =
        faceAuthInteractor.authenticationStatus.filterIsInstance<HelpFaceAuthenticationStatus>()

    init {
        if (DeviceEntryUdfpsRefactor.isEnabled) {
            startUpdatingFaceHelpMessageDeferral()
        }
    }

    /**
     * If the given [HelpFaceAuthenticationStatus] msgId should be deferred to
     * [FaceManager.FACE_ERROR_TIMEOUT].
     */
    fun shouldDefer(msgId: Int): Boolean {
        return faceHelpMessageDeferral.shouldDefer(msgId)
    }

    /**
     * Message that was deferred to show at [FaceManager.FACE_ERROR_TIMEOUT], if any. Returns null
     * if there are currently no valid deferred messages.
     */
    fun getDeferredMessage(): CharSequence? {
        return faceHelpMessageDeferral.getDeferredMessage()
    }

    private fun startUpdatingFaceHelpMessageDeferral() {
        scope.launch {
            biometricSettingsInteractor.faceAuthEnrolledAndEnabled
                .flatMapLatest { faceEnrolledAndEnabled ->
                    if (faceEnrolledAndEnabled) {
                        faceAcquired
                    } else {
                        emptyFlow()
                    }
                }
                .collect {
                    if (it.acquiredInfo == FaceManager.FACE_ACQUIRED_START) {
                        faceHelpMessageDeferral.reset()
                    }
                    faceHelpMessageDeferral.processFrame(it.acquiredInfo)
                }
        }

        scope.launch {
            biometricSettingsInteractor.faceAuthEnrolledAndEnabled
                .flatMapLatest { faceEnrolledAndEnabled ->
                    if (faceEnrolledAndEnabled) {
                        faceHelp
                    } else {
                        emptyFlow()
                    }
                }
                .collect { helpAuthenticationStatus ->
                    helpAuthenticationStatus.msg?.let { msg ->
                        faceHelpMessageDeferral.updateMessage(helpAuthenticationStatus.msgId, msg)
                    }
                }
        }
    }
}
