/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import com.android.keyguard.logging.KeyguardLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.log.LogLevel.VERBOSE
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private val TAG = KeyguardTransitionAuditLogger::class.simpleName!!

/** Collect flows of interest for auditing keyguard transitions. */
@SysUISingleton
class KeyguardTransitionAuditLogger
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val interactor: KeyguardTransitionInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val logger: KeyguardLogger,
) {

    fun start() {
        scope.launch {
            keyguardInteractor.wakefulnessModel.collect {
                logger.log(TAG, VERBOSE, "WakefulnessModel", it)
            }
        }

        scope.launch {
            keyguardInteractor.isBouncerShowing.collect {
                logger.log(TAG, VERBOSE, "Bouncer showing", it)
            }
        }

        scope.launch {
            keyguardInteractor.isDozing.collect { logger.log(TAG, VERBOSE, "isDozing", it) }
        }

        scope.launch {
            keyguardInteractor.isDreaming.collect { logger.log(TAG, VERBOSE, "isDreaming", it) }
        }

        scope.launch {
            interactor.finishedKeyguardTransitionStep.collect {
                logger.log(TAG, VERBOSE, "Finished transition", it)
            }
        }

        scope.launch {
            interactor.canceledKeyguardTransitionStep.collect {
                logger.log(TAG, VERBOSE, "Canceled transition", it)
            }
        }

        scope.launch {
            interactor.startedKeyguardTransitionStep.collect {
                logger.log(TAG, VERBOSE, "Started transition", it)
            }
        }

        scope.launch {
            keyguardInteractor.dozeTransitionModel.collect {
                logger.log(TAG, VERBOSE, "Doze transition", it)
            }
        }
    }
}
