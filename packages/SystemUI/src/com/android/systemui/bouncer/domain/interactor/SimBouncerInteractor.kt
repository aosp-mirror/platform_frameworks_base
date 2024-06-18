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
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.UserHandle
import android.telephony.PinResult
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import android.text.TextUtils
import android.util.Log
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.bouncer.data.repository.SimBouncerRepository
import com.android.systemui.bouncer.data.repository.SimBouncerRepositoryImpl
import com.android.systemui.bouncer.data.repository.SimBouncerRepositoryImpl.Companion.ACTION_DISABLE_ESIM
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.util.icuMessageFormat
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles domain layer logic for locked sim cards. */
@SuppressLint("WrongConstant")
@SysUISingleton
class SimBouncerInteractor
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val repository: SimBouncerRepository,
    private val telephonyManager: TelephonyManager,
    @Main private val resources: Resources,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val euiccManager: EuiccManager?,
    // TODO(b/307977401): Replace this with `MobileConnectionsInteractor` when available.
    mobileConnectionsRepository: MobileConnectionsRepository,
) {
    val subId: StateFlow<Int> = repository.subscriptionId
    val isAnySimSecure: Flow<Boolean> = mobileConnectionsRepository.isAnySimSecure
    val isLockedEsim: StateFlow<Boolean?> = repository.isLockedEsim
    val errorDialogMessage: StateFlow<String?> = repository.errorDialogMessage

    /** Returns the default message for the sim pin screen. */
    fun getDefaultMessage(): String {
        val isEsimLocked = repository.isLockedEsim.value ?: false
        val isPuk: Boolean = repository.isSimPukLocked.value
        val subscriptionId = repository.subscriptionId.value

        if (subscriptionId == INVALID_SUBSCRIPTION_ID) {
            Log.e(TAG, "Trying to get default message from unknown sub id")
            return ""
        }

        var count = telephonyManager.activeModemCount
        val info: SubscriptionInfo? = repository.activeSubscriptionInfo.value
        val displayName = info?.displayName
        var msg: String =
            when {
                count < 2 && isPuk -> resources.getString(R.string.kg_puk_enter_puk_hint)
                count < 2 -> resources.getString(R.string.kg_sim_pin_instructions)
                else -> {
                    when {
                        !TextUtils.isEmpty(displayName) && isPuk ->
                            resources.getString(R.string.kg_puk_enter_puk_hint_multi, displayName)
                        !TextUtils.isEmpty(displayName) ->
                            resources.getString(R.string.kg_sim_pin_instructions_multi, displayName)
                        isPuk -> resources.getString(R.string.kg_puk_enter_puk_hint)
                        else -> resources.getString(R.string.kg_sim_pin_instructions)
                    }
                }
            }

        if (isEsimLocked) {
            msg = resources.getString(R.string.kg_sim_lock_esim_instructions, msg)
        }

        return msg
    }

    /** Resets the user flow when the sim screen is puk locked. */
    fun resetSimPukUserInput() {
        repository.setSimPukUserInput()
        // Force a garbage collection in an attempt to erase any sim pin or sim puk codes left in
        // memory. Do it asynchronously with a 5-sec delay to avoid making the keyguard
        // dismiss animation janky.

        applicationScope.launch(backgroundDispatcher) {
            delay(5000)
            System.gc()
            System.runFinalization()
            System.gc()
        }
    }

    /** Disables the locked esim card so user can bypass the sim pin screen. */
    fun disableEsim() {
        val activeSubscription = repository.activeSubscriptionInfo.value
        if (activeSubscription == null) {
            val subId = repository.subscriptionId.value
            Log.e(TAG, "No active subscription with subscriptionId: $subId")
            return
        }
        val intent = Intent(ACTION_DISABLE_ESIM)
        intent.setPackage(applicationContext.packageName)
        val callbackIntent =
            PendingIntent.getBroadcastAsUser(
                applicationContext,
                0 /* requestCode */,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE_UNAUDITED,
                UserHandle.SYSTEM
            )
        applicationScope.launch(backgroundDispatcher) {
            if (euiccManager != null) {
                euiccManager.switchToSubscription(
                    INVALID_SUBSCRIPTION_ID,
                    activeSubscription.portIndex,
                    callbackIntent,
                )
            }
        }
    }

    /** Update state when error dialog is dismissed by the user. */
    fun onErrorDialogDismissed() {
        repository.setSimVerificationErrorMessage(null)
    }

    /**
     * Based on sim state, unlock the locked sim with the given credentials.
     *
     * @return Any message that should show associated with the provided input. Null means that no
     *   message needs to be shown.
     */
    suspend fun verifySim(input: List<Any>): String? {
        if (repository.isSimPukLocked.value) {
            return verifySimPuk(input.joinToString(separator = ""))
        }

        return verifySimPin(input.joinToString(separator = ""))
    }

    /**
     * Verifies the input and unlocks the locked sim with a 4-8 digit pin code.
     *
     * @return Any message that should show associated with the provided input. Null means that no
     *   message needs to be shown.
     */
    private suspend fun verifySimPin(input: String): String? {
        val subscriptionId = repository.subscriptionId.value
        // A SIM PIN is 4 to 8 decimal digits according to
        // GSM 02.17 version 5.0.1, Section 5.6 PIN Management
        if (input.length < MIN_SIM_PIN_LENGTH || input.length > MAX_SIM_PIN_LENGTH) {
            return resources.getString(R.string.kg_invalid_sim_pin_hint)
        }
        val result =
            withContext(backgroundDispatcher) {
                val telephonyManager: TelephonyManager =
                    telephonyManager.createForSubscriptionId(subscriptionId)
                telephonyManager.supplyIccLockPin(input)
            }
        when (result.result) {
            PinResult.PIN_RESULT_TYPE_SUCCESS ->
                keyguardUpdateMonitor.reportSimUnlocked(subscriptionId)
            PinResult.PIN_RESULT_TYPE_INCORRECT -> {
                if (result.attemptsRemaining <= CRITICAL_NUM_OF_ATTEMPTS) {
                    // Show a dialog to display the remaining number of attempts to verify the sim
                    // pin to the user.
                    repository.setSimVerificationErrorMessage(
                        getPinPasswordErrorMessage(result.attemptsRemaining)
                    )
                } else {
                    return getPinPasswordErrorMessage(result.attemptsRemaining)
                }
            }
        }

        return null
    }

    /**
     * Verifies the input and unlocks the locked sim with a puk code instead of pin.
     *
     * This occurs after incorrectly verifying the sim pin multiple times.
     *
     * @return Any message that should show associated with the provided input. Null means that no
     *   message needs to be shown.
     */
    private suspend fun verifySimPuk(entry: String): String? {
        val (enteredSimPuk, enteredSimPin) = repository.simPukInputModel
        val subscriptionId: Int = repository.subscriptionId.value

        // Stage 1: Enter the sim puk code of the sim card.
        if (enteredSimPuk == null) {
            if (entry.length >= MIN_SIM_PUK_LENGTH) {
                repository.setSimPukUserInput(enteredSimPuk = entry)
                return resources.getString(R.string.kg_puk_enter_pin_hint)
            } else {
                return resources.getString(R.string.kg_invalid_sim_puk_hint)
            }
        }

        // Stage 2: Set a new sim pin to lock the sim card.
        if (enteredSimPin == null) {
            if (entry.length in MIN_SIM_PIN_LENGTH..MAX_SIM_PIN_LENGTH) {
                repository.setSimPukUserInput(
                    enteredSimPuk = enteredSimPuk,
                    enteredSimPin = entry,
                )
                return resources.getString(R.string.kg_enter_confirm_pin_hint)
            } else {
                return resources.getString(R.string.kg_invalid_sim_pin_hint)
            }
        }

        // Stage 3: Confirm the newly set sim pin.
        if (repository.simPukInputModel.enteredSimPin != entry) {
            // The entered sim pins do not match. Enter desired sim pin again to confirm.
            repository.setSimVerificationErrorMessage(
                resources.getString(R.string.kg_invalid_confirm_pin_hint)
            )
            repository.setSimPukUserInput(enteredSimPuk = enteredSimPuk)
            return resources.getString(R.string.kg_puk_enter_pin_hint)
        }

        val result =
            withContext(backgroundDispatcher) {
                val telephonyManager = telephonyManager.createForSubscriptionId(subscriptionId)
                telephonyManager.supplyIccLockPuk(enteredSimPuk, enteredSimPin)
            }
        resetSimPukUserInput()

        when (result.result) {
            PinResult.PIN_RESULT_TYPE_SUCCESS ->
                keyguardUpdateMonitor.reportSimUnlocked(subscriptionId)
            PinResult.PIN_RESULT_TYPE_INCORRECT ->
                if (result.attemptsRemaining <= CRITICAL_NUM_OF_ATTEMPTS) {
                    // Show a dialog to display the remaining number of attempts to verify the sim
                    // puk to the user.
                    repository.setSimVerificationErrorMessage(
                        getPukPasswordErrorMessage(
                            result.attemptsRemaining,
                            isDefault = false,
                            isEsimLocked = repository.isLockedEsim.value == true
                        )
                    )
                } else {
                    return getPukPasswordErrorMessage(
                        result.attemptsRemaining,
                        isDefault = false,
                        isEsimLocked = repository.isLockedEsim.value == true
                    )
                }
            else -> return resources.getString(R.string.kg_password_puk_failed)
        }

        return null
    }

    private fun getPinPasswordErrorMessage(attemptsRemaining: Int): String {
        var displayMessage: String =
            if (attemptsRemaining == 0) {
                resources.getString(R.string.kg_password_wrong_pin_code_pukked)
            } else if (attemptsRemaining > 0) {
                val msgId = R.string.kg_password_default_pin_message
                icuMessageFormat(resources, msgId, attemptsRemaining)
            } else {
                val msgId = R.string.kg_sim_pin_instructions
                resources.getString(msgId)
            }
        if (repository.isLockedEsim.value == true) {
            displayMessage =
                resources.getString(R.string.kg_sim_lock_esim_instructions, displayMessage)
        }
        return displayMessage
    }

    private fun getPukPasswordErrorMessage(
        attemptsRemaining: Int,
        isDefault: Boolean,
        isEsimLocked: Boolean,
    ): String {
        var displayMessage: String =
            if (attemptsRemaining == 0) {
                resources.getString(R.string.kg_password_wrong_puk_code_dead)
            } else if (attemptsRemaining > 0) {
                val msgId =
                    if (isDefault) R.string.kg_password_default_puk_message
                    else R.string.kg_password_wrong_puk_code
                icuMessageFormat(resources, msgId, attemptsRemaining)
            } else {
                val msgId =
                    if (isDefault) R.string.kg_puk_enter_puk_hint
                    else R.string.kg_password_puk_failed
                resources.getString(msgId)
            }
        if (isEsimLocked) {
            displayMessage =
                resources.getString(R.string.kg_sim_lock_esim_instructions, displayMessage)
        }
        return displayMessage
    }

    companion object {
        private const val TAG = "BouncerSimInteractor"
        const val INVALID_SUBSCRIPTION_ID = SimBouncerRepositoryImpl.INVALID_SUBSCRIPTION_ID
        const val MIN_SIM_PIN_LENGTH = 4
        const val MAX_SIM_PIN_LENGTH = 8
        const val MIN_SIM_PUK_LENGTH = 8
        const val CRITICAL_NUM_OF_ATTEMPTS = 2
    }
}
