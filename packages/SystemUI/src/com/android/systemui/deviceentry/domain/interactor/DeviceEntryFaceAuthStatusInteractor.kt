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

import android.content.res.Resources
import android.hardware.biometrics.BiometricFaceConstants
import com.android.systemui.biometrics.FaceHelpMessageDebouncer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.shared.model.AcquiredFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.res.R
import java.util.Arrays
import java.util.stream.Collectors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

/**
 * Process face authentication statuses.
 * - Ignores face help messages based on R.array.config_face_acquire_device_entry_ignorelist.
 * - Uses FaceHelpMessageDebouncer to debounce flickery help messages.
 */
@SysUISingleton
class DeviceEntryFaceAuthStatusInteractor
@Inject
constructor(
    repository: DeviceEntryFaceAuthRepository,
    @Main private val resources: Resources,
    @Application private val applicationScope: CoroutineScope,
) {
    private val faceHelpMessageDebouncer = FaceHelpMessageDebouncer()
    private var faceAcquiredInfoIgnoreList: Set<Int> =
        Arrays.stream(resources.getIntArray(R.array.config_face_acquire_device_entry_ignorelist))
            .boxed()
            .collect(Collectors.toSet())

    val authenticationStatus: StateFlow<FaceAuthenticationStatus?> =
        repository.authenticationStatus
            .transform { authenticationStatus ->
                if (authenticationStatus is AcquiredFaceAuthenticationStatus) {
                    if (
                        authenticationStatus.acquiredInfo ==
                            BiometricFaceConstants.FACE_ACQUIRED_START
                    ) {
                        faceHelpMessageDebouncer.startNewFaceAuthSession(
                            authenticationStatus.createdAt
                        )
                    }
                }

                if (authenticationStatus is HelpFaceAuthenticationStatus) {
                    if (!faceAcquiredInfoIgnoreList.contains(authenticationStatus.msgId)) {
                        faceHelpMessageDebouncer.addMessage(authenticationStatus)
                    }

                    val messageToShow =
                        faceHelpMessageDebouncer.getMessageToShow(
                            atTimestamp = authenticationStatus.createdAt,
                        )
                    if (messageToShow != null) {
                        emit(messageToShow)
                    }

                    return@transform
                }

                emit(authenticationStatus)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )
}
