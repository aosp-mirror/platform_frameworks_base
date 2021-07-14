/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.controls

import android.service.controls.DeviceTypes.DeviceType

import com.android.internal.logging.InstanceIdSequence
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.SysUiStatsLog

import javax.inject.Inject

/**
 * Implementation for logging UI events related to controls
 */
@SysUISingleton
class ControlsMetricsLoggerImpl @Inject constructor() : ControlsMetricsLogger {

    companion object {
        private const val INSTANCE_ID_MAX = 1 shl 13
    }

    private val instanceIdSequence = InstanceIdSequence(INSTANCE_ID_MAX)
    private var instanceId = 0

    override fun assignInstanceId() {
        instanceId = instanceIdSequence.newInstanceId().id
    }

    /**
     * {@see ControlsMetricsLogger#log}
     */
    override fun log(
        eventId: Int,
        @DeviceType deviceType: Int,
        uid: Int,
        isLocked: Boolean
    ) {
        SysUiStatsLog.write(
            SysUiStatsLog.DEVICE_CONTROL_CHANGED,
            eventId,
            instanceId,
            deviceType,
            uid,
            isLocked
        )
    }
}
