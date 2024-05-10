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

package com.android.systemui.temporarydisplay

import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

private const val INSTANCE_ID_MAX = 1 shl 20

/** A helper class to log events related to the temporary view */
@SysUISingleton
class TemporaryViewUiEventLogger @Inject constructor(val logger: UiEventLogger) {

    private val instanceIdSequence = InstanceIdSequence(INSTANCE_ID_MAX)

    /** Get a new instance ID for a new media control */
    fun getNewInstanceId(): InstanceId {
        return instanceIdSequence.newInstanceId()
    }

    /** Logs that view is added */
    fun logViewAdded(instanceId: InstanceId?) {
        logger.log(TemporaryViewUiEvent.TEMPORARY_VIEW_ADDED, instanceId)
    }

    /** Logs that view is manually dismissed by user */
    fun logViewManuallyDismissed(instanceId: InstanceId?) {
        logger.log(TemporaryViewUiEvent.TEMPORARY_VIEW_MANUALLY_DISMISSED, instanceId)
    }
}

enum class TemporaryViewUiEvent(val metricId: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The temporary view was added to window manager") TEMPORARY_VIEW_ADDED(1389),
    @UiEvent(doc = "The temporary view was manually dismissed")
    TEMPORARY_VIEW_MANUALLY_DISMISSED(1390);

    override fun getId() = metricId
}
