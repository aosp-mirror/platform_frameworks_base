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

package com.android.systemui.bouncer.domain.interactor

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.telecom.TelecomManager
import com.android.internal.R
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.util.EmergencyAffordanceManager
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.bouncer.data.repository.EmergencyServicesRepository
import com.android.systemui.bouncer.shared.model.BouncerActionButtonModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.doze.DozeLogger
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.EmergencyDialerConstants
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/**
 * Encapsulates business logic and application state for the bouncer action button. The action
 * button can support multiple different actions, depending on device state.
 */
@SysUISingleton
class BouncerActionButtonInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val repository: EmergencyServicesRepository,
    // TODO(b/307977401): Replace with `MobileConnectionsInteractor` when available.
    private val mobileConnectionsRepository: MobileConnectionsRepository,
    private val telephonyInteractor: TelephonyInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val activityTaskManager: ActivityTaskManager,
    private val telecomManager: TelecomManager?,
    private val emergencyAffordanceManager: EmergencyAffordanceManager,
    private val emergencyDialerIntentFactory: EmergencyDialerIntentFactory,
    private val metricsLogger: MetricsLogger,
    private val dozeLogger: DozeLogger,
) {
    /** The bouncer action button. If `null`, the button should not be shown. */
    val actionButton: Flow<BouncerActionButtonModel?> =
        if (telecomManager == null || !telephonyInteractor.hasTelephonyRadio) {
            flowOf(null)
        } else {
            merge(
                    telephonyInteractor.isInCall.asUnitFlow,
                    mobileConnectionsRepository.isAnySimSecure.asUnitFlow,
                    authenticationInteractor.authenticationMethod.asUnitFlow,
                    repository.enableEmergencyCallWhileSimLocked.asUnitFlow,
                )
                .map {
                    when {
                        isReturnToCallButton() -> returnToCallButtonModel
                        isEmergencyCallButton() -> emergencyCallButtonModel
                        else -> null // Do not show the button.
                    }
                }
                .distinctUntilChanged()
        }

    private val returnToCallButtonModel: BouncerActionButtonModel by lazy {
        BouncerActionButtonModel(
            label = applicationContext.getString(R.string.lockscreen_return_to_call),
            onClick = {
                prepareToPerformAction()
                returnToCall()
            },
            onLongClick = null
        )
    }

    private val emergencyCallButtonModel: BouncerActionButtonModel by lazy {
        BouncerActionButtonModel(
            label = applicationContext.getString(R.string.lockscreen_emergency_call),
            onClick = {
                prepareToPerformAction()
                dozeLogger.logEmergencyCall()
                startEmergencyDialerActivity()
            },
            // TODO(b/308001302): The long click detector doesn't work properly, investigate.
            onLongClick = {
                if (emergencyAffordanceManager.needsEmergencyAffordance()) {
                    prepareToPerformAction()

                    // TODO(b/298026988): Check that !longPressWasDragged before invoking.
                    emergencyAffordanceManager.performEmergencyCall()
                }
            }
        )
    }

    private fun startEmergencyDialerActivity() {
        emergencyDialerIntentFactory()?.apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP

            putExtra(
                EmergencyDialerConstants.EXTRA_ENTRY_TYPE,
                EmergencyDialerConstants.ENTRY_TYPE_LOCKSCREEN_BUTTON,
            )

            // TODO(b/25189994): Use the ActivityStarter interface instead.
            applicationContext.startActivityAsUser(
                this,
                ActivityOptions.makeCustomAnimation(applicationContext, 0, 0).toBundle(),
                UserHandle(selectedUserInteractor.getSelectedUserId())
            )
        }
    }

    private fun isReturnToCallButton() = telephonyInteractor.isInCall.value

    private suspend fun isEmergencyCallButton(): Boolean {
        return if (mobileConnectionsRepository.getIsAnySimSecure()) {
            // Some countries can't handle emergency calls while SIM is locked.
            repository.enableEmergencyCallWhileSimLocked.value
        } else {
            // Only show if there is a secure screen (password/pin/pattern/SIM pin/SIM puk).
            withContext(backgroundDispatcher) {
                authenticationInteractor.getAuthenticationMethod().isSecure
            }
        }
    }

    private fun prepareToPerformAction() {
        // TODO(b/308001302): Trigger occlusion and resetting bouncer state.
        metricsLogger.action(MetricsEvent.ACTION_EMERGENCY_CALL)
        activityTaskManager.stopSystemLockTaskMode()
    }

    @SuppressLint("MissingPermission")
    private fun returnToCall() {
        telecomManager?.showInCallScreen(/* showDialpad = */ false)
    }

    private val <T> Flow<T>.asUnitFlow: Flow<Unit>
        get() = map {}
}

/**
 * Creates an intent to launch the Emergency Services dialer. If no [TelecomManager] is present,
 * returns `null`.
 */
interface EmergencyDialerIntentFactory {
    operator fun invoke(): Intent?
}
