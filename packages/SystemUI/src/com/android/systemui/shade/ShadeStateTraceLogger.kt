/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade

import com.android.app.tracing.TraceStateLogger
import com.android.app.tracing.TrackGroupUtils.trackGroup
import com.android.app.tracing.coroutines.TrackTracer.Companion.instantForGroup
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class ShadeStateTraceLogger
@Inject
constructor(
    private val shadeInteractor: ShadeInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val shadeDisplaysRepository: Lazy<ShadeDisplaysRepository>,
    @Application private val scope: CoroutineScope,
) : CoreStartable {
    override fun start() {
        scope.launchTraced("ShadeStateTraceLogger") {
            launch {
                val stateLogger = createTraceStateLogger("isShadeLayoutWide")
                shadeInteractor.isShadeLayoutWide.collect { stateLogger.log(it.toString()) }
            }
            launch {
                val stateLogger = createTraceStateLogger("shadeMode")
                shadeModeInteractor.shadeMode.collect { stateLogger.log(it.toString()) }
            }
            launch {
                shadeInteractor.shadeExpansion.collect {
                    instantForGroup(TRACK_GROUP_NAME, "shadeExpansion", it)
                }
            }
            if (ShadeWindowGoesAround.isEnabled) {
                launch {
                    shadeDisplaysRepository.get().displayId.collect {
                        instantForGroup(TRACK_GROUP_NAME, "displayId", it)
                    }
                }
            }
        }
    }

    private fun createTraceStateLogger(trackName: String): TraceStateLogger {
        return TraceStateLogger(trackGroup(TRACK_GROUP_NAME, trackName))
    }

    private companion object {
        const val TRACK_GROUP_NAME = "shade"
    }
}
