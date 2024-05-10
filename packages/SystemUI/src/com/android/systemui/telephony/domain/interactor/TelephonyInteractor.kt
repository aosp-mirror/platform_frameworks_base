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

package com.android.systemui.telephony.domain.interactor

import android.telephony.Annotation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.telephony.data.repository.TelephonyRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Hosts business logic related to telephony. */
@SysUISingleton
class TelephonyInteractor
@Inject
constructor(
    repository: TelephonyRepository,
) {
    @Annotation.CallState val callState: Flow<Int> = repository.callState

    val isInCall: StateFlow<Boolean> = repository.isInCall

    val hasTelephonyRadio: Boolean = repository.hasTelephonyRadio
}
