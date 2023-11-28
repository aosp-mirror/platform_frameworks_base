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
package com.android.systemui.unfold

import android.content.Context
import android.os.Trace
import com.android.app.tracing.TraceStateLogger
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.unfold.system.DeviceStateRepository
import com.android.systemui.unfold.updates.FoldStateRepository
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Logs several unfold related details in a trace. Mainly used for debugging and investigate
 * droidfooders traces.
 */
@SysUISingleton
class UnfoldTraceLogger
@Inject
constructor(
    private val context: Context,
    private val foldStateRepository: FoldStateRepository,
    @Application applicationScope: CoroutineScope,
    @Background private val coroutineContext: CoroutineContext,
    private val deviceStateRepository: DeviceStateRepository
) : CoreStartable {
    private val isFoldable: Boolean
        get() =
            context.resources
                .getIntArray(com.android.internal.R.array.config_foldedDeviceStates)
                .isNotEmpty()

    private val bgScope = applicationScope.plus(coroutineContext)

    override fun start() {
        if (!isFoldable) return

        bgScope.launch {
            val foldUpdateLogger = TraceStateLogger("FoldUpdate")
            foldStateRepository.foldUpdate.collect { foldUpdateLogger.log(it.name) }
        }

        bgScope.launch {
            foldStateRepository.hingeAngle.collect {
                Trace.traceCounter(Trace.TRACE_TAG_APP, "hingeAngle", it.toInt())
            }
        }
        bgScope.launch {
            val foldedStateLogger = TraceStateLogger("FoldedState")
            deviceStateRepository.isFolded.collect { isFolded ->
                foldedStateLogger.log(
                    if (isFolded) {
                        "folded"
                    } else {
                        "unfolded"
                    }
                )
            }
        }
    }
}
