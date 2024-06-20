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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.telephony.ServiceState

/**
 * Simplified representation of a [ServiceState] for use in SystemUI. Add any fields that we need to
 * extract from service state here for consumption downstream
 */
data class ServiceStateModel(val isEmergencyOnly: Boolean) {
    companion object {
        fun fromServiceState(serviceState: ServiceState): ServiceStateModel {
            return ServiceStateModel(isEmergencyOnly = serviceState.isEmergencyOnly)
        }
    }
}
