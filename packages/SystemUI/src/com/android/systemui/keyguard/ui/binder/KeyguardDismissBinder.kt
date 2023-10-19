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
 * limitations under the License
 */
package com.android.systemui.keyguard.ui.binder

import com.android.keyguard.ViewMediatorCallback
import com.android.keyguard.logging.KeyguardLogger
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissInteractor
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.log.core.LogLevel
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Handles keyguard dismissal requests. */
@SysUISingleton
class KeyguardDismissBinder
@Inject
constructor(
    private val interactor: KeyguardDismissInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val viewMediatorCallback: ViewMediatorCallback,
    @Application private val scope: CoroutineScope,
    private val keyguardLogger: KeyguardLogger,
    private val featureFlags: FeatureFlagsClassic,
) : CoreStartable {

    override fun start() {
        if (!featureFlags.isEnabled(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT)) {
            return
        }

        scope.launch {
            interactor.keyguardDone.collect { keyguardDoneTiming ->
                when (keyguardDoneTiming) {
                    KeyguardDone.LATER -> {
                        log("keyguardDonePending")
                        viewMediatorCallback.keyguardDonePending(
                            selectedUserInteractor.getSelectedUserId()
                        )
                    }
                    else -> {
                        log("keyguardDone")
                        viewMediatorCallback.keyguardDone(
                            selectedUserInteractor.getSelectedUserId()
                        )
                    }
                }
            }
        }

        scope.launch {
            interactor.dismissKeyguardRequestWithoutImmediateDismissAction.collect {
                log("dismissKeyguardRequestWithoutImmediateDismissAction-keyguardDone")
                interactor.setKeyguardDone(KeyguardDone.IMMEDIATE)
            }
        }
    }

    private fun log(message: String) {
        keyguardLogger.log(TAG, LogLevel.DEBUG, message)
    }

    companion object {
        private const val TAG = "KeyguardDismiss"
    }
}
