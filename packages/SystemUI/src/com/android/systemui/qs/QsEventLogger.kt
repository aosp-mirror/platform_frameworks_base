/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs

import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

interface QsEventLogger : UiEventLogger {
    fun getNewInstanceId(): InstanceId
}

@SysUISingleton
class QsEventLoggerImpl
@Inject
constructor(
    uiEventLogger: UiEventLogger,
) : QsEventLogger, UiEventLogger by uiEventLogger {

    companion object {
        private const val MAX_QS_INSTANCE_ID = 1 shl 20
    }

    val sequence = InstanceIdSequence(MAX_QS_INSTANCE_ID)
    override fun getNewInstanceId(): InstanceId {
        return sequence.newInstanceId()
    }
}
