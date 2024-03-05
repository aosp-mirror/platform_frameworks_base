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
import com.android.systemui.log.core.LogLevel.VERBOSE
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
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
    private val powerInteractor: PowerInteractor,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val shadeInteractor: ShadeInteractor,
) {

    fun start() {
        scope.launch {
            powerInteractor.detailedWakefulness.collect {
                logger.log(TAG, VERBOSE, "WakefulnessModel", it)
            }
        }

        scope.launch {
            sharedNotificationContainerViewModel
                .getMaxNotifications { height, useExtraShelfSpace -> height.toInt() }
                .collect { logger.log(TAG, VERBOSE, "Notif: max height in px", it) }
        }

        scope.launch {
            sharedNotificationContainerViewModel.isOnLockscreen.collect {
                logger.log(TAG, VERBOSE, "Notif: isOnLockscreen", it)
            }
        }

        scope.launch {
            shadeInteractor.isUserInteracting.collect {
                logger.log(TAG, VERBOSE, "Shade: isUserInteracting", it)
            }
        }

        scope.launch {
            sharedNotificationContainerViewModel.isOnLockscreenWithoutShade.collect {
                logger.log(TAG, VERBOSE, "Notif: isOnLockscreenWithoutShade", it)
            }
        }

        scope.launch {
            keyguardInteractor.primaryBouncerShowing.collect {
                logger.log(TAG, VERBOSE, "Primary bouncer showing", it)
            }
        }

        scope.launch {
            keyguardInteractor.alternateBouncerShowing.collect {
                logger.log(TAG, VERBOSE, "Alternate bouncer showing", it)
            }
        }

        scope.launch {
            keyguardInteractor.isDozing.collect { logger.log(TAG, VERBOSE, "isDozing", it) }
        }

        scope.launch {
            keyguardInteractor.isAbleToDream.collect {
                logger.log(TAG, VERBOSE, "isAbleToDream", it)
            }
        }

        scope.launch {
            keyguardInteractor.isKeyguardOccluded.collect {
                logger.log(TAG, VERBOSE, "isOccluded", it)
            }
        }

        scope.launch {
            keyguardInteractor.isKeyguardDismissible.collect {
                logger.log(TAG, VERBOSE, "isDismissible", it)
            }
        }

        scope.launch {
            keyguardInteractor.isKeyguardShowing.collect {
                logger.log(TAG, VERBOSE, "isShowing", it)
            }
        }

        scope.launch {
            keyguardInteractor.dozeTransitionModel.collect {
                logger.log(TAG, VERBOSE, "Doze transition", it)
            }
        }

        scope.launch {
            keyguardInteractor.onCameraLaunchDetected.collect {
                logger.log(TAG, VERBOSE, "onCameraLaunchDetected", it)
            }
        }
    }
}
