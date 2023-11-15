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

package com.android.systemui.bouncer.data.repository

import android.annotation.SuppressLint
import android.content.IntentFilter
import android.content.res.Resources
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.bouncer.data.model.SimBouncerModel
import com.android.systemui.bouncer.data.model.SimPukInputModel
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Handles data layer logic for locked sim cards. */
interface SimBouncerRepository {
    /** The subscription id of the current locked sim card. */
    val subscriptionId: StateFlow<Int>
    /** The active subscription of the current subscription id. */
    val activeSubscriptionInfo: StateFlow<SubscriptionInfo?>
    /**
     * Determines if current sim card is an esim and is locked.
     *
     * A null value indicates that we do not know if we are esim locked or not.
     */
    val isLockedEsim: StateFlow<Boolean?>
    /**
     * Determines whether the current sim is locked requiring a PUK (Personal Unlocking Key) code.
     */
    val isSimPukLocked: StateFlow<Boolean>
    /**
     * The error message that should be displayed in an alert dialog.
     *
     * A null value indicates that the error dialog is not showing.
     */
    val errorDialogMessage: StateFlow<String?>
    /** The state of the user flow on the SimPuk screen. */
    val simPukInputModel: SimPukInputModel
    /** Sets the state of the user flow on the SimPuk screen. */
    fun setSimPukUserInput(enteredSimPuk: String? = null, enteredSimPin: String? = null)
    /**
     * Sets the error message when failing sim verification.
     *
     * A null value indicates that there is no error message to show.
     */
    fun setSimVerificationErrorMessage(msg: String?)
}

@SysUISingleton
class SimBouncerRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main resources: Resources,
    keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val subscriptionManager: SubscriptionManagerProxy,
    broadcastDispatcher: BroadcastDispatcher,
    euiccManager: EuiccManager,
) : SimBouncerRepository {
    private val isPukScreenAvailable: Boolean =
        resources.getBoolean(com.android.internal.R.bool.config_enable_puk_unlock_screen)

    private val simBouncerModel: Flow<SimBouncerModel?> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onSimStateChanged(subId: Int, slotId: Int, simState: Int) {
                            trySend(Unit)
                        }
                    }
                keyguardUpdateMonitor.registerCallback(callback)
                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .map {
                // Check to see if there is a locked sim puk card.
                val pukLockedSubId =
                    withContext(backgroundDispatcher) {
                        keyguardUpdateMonitor.getNextSubIdForState(
                            TelephonyManager.SIM_STATE_PUK_REQUIRED
                        )
                    }
                if (
                    isPukScreenAvailable &&
                        subscriptionManager.isValidSubscriptionId(pukLockedSubId)
                ) {
                    return@map (SimBouncerModel(isSimPukLocked = true, pukLockedSubId))
                }

                // If there is no locked sim puk card, check to see if there is a locked sim card.
                val pinLockedSubId =
                    withContext(backgroundDispatcher) {
                        keyguardUpdateMonitor.getNextSubIdForState(
                            TelephonyManager.SIM_STATE_PIN_REQUIRED
                        )
                    }
                if (subscriptionManager.isValidSubscriptionId(pinLockedSubId)) {
                    return@map SimBouncerModel(isSimPukLocked = false, pinLockedSubId)
                }

                return@map null // There is no locked sim.
            }

    override val subscriptionId: StateFlow<Int> =
        simBouncerModel
            .map { state -> state?.subscriptionId ?: INVALID_SUBSCRIPTION_ID }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = INVALID_SUBSCRIPTION_ID,
            )

    @SuppressLint("MissingPermission")
    override val activeSubscriptionInfo: StateFlow<SubscriptionInfo?> =
        subscriptionId
            .map {
                withContext(backgroundDispatcher) {
                    subscriptionManager.getActiveSubscriptionInfo(it)
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    @SuppressLint("MissingPermission")
    override val isLockedEsim: StateFlow<Boolean?> =
        activeSubscriptionInfo
            .map { info -> info?.let { euiccManager.isEnabled && info.isEmbedded } }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    override val isSimPukLocked: StateFlow<Boolean> =
        simBouncerModel
            .map { it?.isSimPukLocked == true }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private val disableEsimErrorMessage: Flow<String?> =
        broadcastDispatcher.broadcastFlow(filter = IntentFilter(ACTION_DISABLE_ESIM)) { _, receiver
            ->
            if (receiver.resultCode != EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                resources.getString(R.string.error_disable_esim_msg)
            } else {
                null
            }
        }

    private val simVerificationErrorMessage: MutableStateFlow<String?> = MutableStateFlow(null)

    override val errorDialogMessage: StateFlow<String?> =
        merge(disableEsimErrorMessage, simVerificationErrorMessage)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    private var _simPukInputModel: SimPukInputModel = SimPukInputModel()
    override val simPukInputModel: SimPukInputModel
        get() = _simPukInputModel

    override fun setSimPukUserInput(enteredSimPuk: String?, enteredSimPin: String?) {
        _simPukInputModel = SimPukInputModel(enteredSimPuk, enteredSimPin)
    }

    override fun setSimVerificationErrorMessage(msg: String?) {
        simVerificationErrorMessage.value = msg
    }

    companion object {
        const val ACTION_DISABLE_ESIM = "com.android.keyguard.disable_esim"
        const val INVALID_SUBSCRIPTION_ID = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }
}
