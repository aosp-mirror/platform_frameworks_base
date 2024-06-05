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
 * limitations under the License.
 *
 */

package com.android.systemui.telephony.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.Annotation
import android.telephony.TelephonyCallback
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.telephony.TelephonyListenerManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Defines interface for classes that encapsulate _some_ telephony-related state. */
interface TelephonyRepository {
    /** The state of the current call. */
    @Annotation.CallState val callState: Flow<Int>

    /**
     * Whether there is an ongoing phone call (can be in dialing, ringing, active or holding states)
     * originating from either a manager or self-managed {@link ConnectionService}.
     */
    val isInCall: StateFlow<Boolean>

    /** Whether the device has a radio that can be used for telephony. */
    val hasTelephonyRadio: Boolean
}

/**
 * NOTE: This repository tracks only telephony-related state regarding the default mobile
 * subscription. `TelephonyListenerManager` does not create new instances of `TelephonyManager` on a
 * per-subscription basis and thus will always be tracking telephony information regarding
 * `SubscriptionManager.getDefaultSubscriptionId`. See `TelephonyManager` and `SubscriptionManager`
 * for more documentation.
 */
@SysUISingleton
class TelephonyRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val manager: TelephonyListenerManager,
    private val telecomManager: TelecomManager?,
) : TelephonyRepository {

    @Annotation.CallState
    override val callState: Flow<Int> = conflatedCallbackFlow {
        val listener = TelephonyCallback.CallStateListener(::trySend)

        manager.addCallStateListener(listener)

        awaitClose { manager.removeCallStateListener(listener) }
    }

    @SuppressLint("MissingPermission")
    override val isInCall: StateFlow<Boolean> =
        if (telecomManager == null) {
                flowOf(false)
            } else {
                callState.map { withContext(backgroundDispatcher) { telecomManager.isInCall } }
            }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    override val hasTelephonyRadio =
        applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
}
