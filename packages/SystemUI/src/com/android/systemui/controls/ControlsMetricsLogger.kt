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

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.controls.ui.ControlViewHolder

/**
 * Interface for logging UI events related to controls
 */
interface ControlsMetricsLogger {

    /**
     * Assign a new instance id for this controls session, defined as when the controls area is
     * made visible to when it is closed.
     */
    fun assignInstanceId()

    fun touch(cvh: ControlViewHolder, isLocked: Boolean) {
        log(ControlsEvents.CONTROL_TOUCH.id, cvh.deviceType, cvh.uid, isLocked)
    }

    fun drag(cvh: ControlViewHolder, isLocked: Boolean) {
        log(ControlsEvents.CONTROL_DRAG.id, cvh.deviceType, cvh.uid, isLocked)
    }

    fun longPress(cvh: ControlViewHolder, isLocked: Boolean) {
        log(ControlsEvents.CONTROL_LONG_PRESS.id, cvh.deviceType, cvh.uid, isLocked)
    }

    fun refreshBegin(uid: Int, isLocked: Boolean) {
        assignInstanceId()
        log(ControlsEvents.CONTROL_REFRESH_BEGIN.id, 0, uid, isLocked)
    }

    fun refreshEnd(cvh: ControlViewHolder, isLocked: Boolean) {
        log(ControlsEvents.CONTROL_REFRESH_END.id, cvh.deviceType, cvh.uid, isLocked)
    }

    /**
     * Logs a controls-related event
     *
     * @param eventId Main UIEvent to capture
     * @param deviceType One of {@link android.service.controls.DeviceTypes}
     * @param packageName Package name of the service that receives the request
     * @param isLocked Is the device locked at the start of the action?
     */
    fun log(
        eventId: Int,
        @DeviceType deviceType: Int,
        uid: Int,
        isLocked: Boolean
    )

    private enum class ControlsEvents(val metricId: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "User touched a control")
        CONTROL_TOUCH(714),

        @UiEvent(doc = "User dragged a control")
        CONTROL_DRAG(713),

        @UiEvent(doc = "User long-pressed a control")
        CONTROL_LONG_PRESS(715),

        @UiEvent(doc = "User has opened controls, and a state refresh has begun")
        CONTROL_REFRESH_BEGIN(716),

        @UiEvent(doc = "User has opened controls, and a state refresh has ended")
        CONTROL_REFRESH_END(717);

        override fun getId() = metricId
    }
}
