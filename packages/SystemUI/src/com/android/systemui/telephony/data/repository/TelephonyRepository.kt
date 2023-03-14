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

import android.telephony.Annotation
import android.telephony.TelephonyCallback
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.telephony.TelephonyListenerManager
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Defines interface for classes that encapsulate _some_ telephony-related state. */
interface TelephonyRepository {
    /** The state of the current call. */
    @Annotation.CallState val callState: Flow<Int>
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
    private val manager: TelephonyListenerManager,
) : TelephonyRepository {
    @Annotation.CallState
    override val callState: Flow<Int> = conflatedCallbackFlow {
        val listener = TelephonyCallback.CallStateListener { state -> trySend(state) }

        manager.addCallStateListener(listener)

        awaitClose { manager.removeCallStateListener(listener) }
    }
}
