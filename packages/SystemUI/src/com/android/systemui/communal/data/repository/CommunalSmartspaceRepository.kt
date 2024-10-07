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

package com.android.systemui.communal.data.repository

import android.app.smartspace.SmartspaceTarget
import android.os.Parcelable
import androidx.annotation.VisibleForTesting
import com.android.systemui.Flags.communalTimerFlickerFix
import com.android.systemui.communal.data.model.CommunalSmartspaceTimer
import com.android.systemui.communal.smartspace.CommunalSmartspaceController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface CommunalSmartspaceRepository {
    /** Smartspace timer targets for the communal surface. */
    val timers: Flow<List<CommunalSmartspaceTimer>>

    /** Start listening for smartspace updates. */
    fun startListening()

    /** Stop listening for smartspace updates. */
    fun stopListening()
}

@SysUISingleton
class CommunalSmartspaceRepositoryImpl
@Inject
constructor(
    private val communalSmartspaceController: CommunalSmartspaceController,
    @Main private val uiExecutor: Executor,
    private val systemClock: SystemClock,
    @CommunalLog logBuffer: LogBuffer,
) : CommunalSmartspaceRepository, BcSmartspaceDataPlugin.SmartspaceTargetListener {

    private val logger = Logger(logBuffer, "CommunalSmartspaceRepository")

    private val _timers: MutableStateFlow<List<CommunalSmartspaceTimer>> =
        MutableStateFlow(emptyList())
    override val timers: Flow<List<CommunalSmartspaceTimer>> = _timers

    private var targetCreationTimes = emptyMap<String, Long>()

    override fun onSmartspaceTargetsUpdated(targetsNullable: MutableList<out Parcelable>?) {
        val targets = targetsNullable?.filterIsInstance<SmartspaceTarget>() ?: emptyList()
        val timerTargets =
            targets
                .filter { target ->
                    target.featureType == SmartspaceTarget.FEATURE_TIMER &&
                        target.remoteViews != null
                }
                .associateBy { stableId(it.smartspaceTargetId) }

        // The creation times from smartspace targets are unreliable (b/318535930). Therefore,
        // SystemUI uses the timestamp of which a timer first appears, and caches these values to
        // prevent timers from swapping positions in the hub.
        targetCreationTimes =
            timerTargets.mapValues { (stableId, _) ->
                targetCreationTimes[stableId] ?: systemClock.currentTimeMillis()
            }

        _timers.value =
            timerTargets
                .map { (stableId, target) ->
                    CommunalSmartspaceTimer(
                        // The view layer should have the instance based smartspaceTargetId instead
                        // of stable id, so that when a new instance of the timer is created, for
                        // example, when it is paused, the view should re-render its remote views.
                        smartspaceTargetId =
                            if (communalTimerFlickerFix()) stableId else target.smartspaceTargetId,
                        createdTimestampMillis = targetCreationTimes[stableId]!!,
                        remoteViews = target.remoteViews!!,
                    )
                }
                .also { newVal ->
                    // Only log when value changes to avoid filling up the buffer.
                    if (newVal != _timers.value) {
                        logger.d({ "Smartspace timers updated: $str1" }) {
                            str1 = newVal.toString()
                        }
                    }
                }
    }

    override fun startListening() {
        if (android.app.smartspace.flags.Flags.remoteViews()) {
            uiExecutor.execute {
                communalSmartspaceController.addListener(
                    listener = this@CommunalSmartspaceRepositoryImpl
                )
            }
        }
    }

    override fun stopListening() {
        uiExecutor.execute {
            communalSmartspaceController.removeListener(
                listener = this@CommunalSmartspaceRepositoryImpl
            )
        }
    }

    companion object {
        /**
         * The smartspace target id is instance-based, meaning a single timer (from the user's
         * perspective) can have multiple instances. For example, when a timer is paused, a new
         * instance is created. To address this, SystemUI manually removes the instance id to
         * maintain a consistent id across sessions.
         *
         * It is assumed that timer target ids follow this format: timer-${stableId}-${instanceId}.
         * This function returns timer-${stableId}, stripping out the instance id.
         */
        @VisibleForTesting
        fun stableId(targetId: String): String {
            return targetId.split("-").take(2).joinToString("-")
        }
    }
}
