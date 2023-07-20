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

package com.android.systemui.flags

import android.util.Log
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Restarts the process after all passed in [Condition]s are true. */
class ConditionalRestarter
@Inject
constructor(
    private val systemExitRestarter: SystemExitRestarter,
    private val conditions: Set<@JvmSuppressWildcards Condition>,
    @Named(RESTART_DELAY) private val restartDelaySec: Long,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : Restarter {

    private var restartJob: Job? = null
    private var pendingReason = ""
    private var androidRestartRequested = false

    override fun restartSystemUI(reason: String) {
        Log.d(FeatureFlagsDebug.TAG, "SystemUI Restart requested. Restarting when idle.")
        scheduleRestart(reason)
    }

    override fun restartAndroid(reason: String) {
        Log.d(FeatureFlagsDebug.TAG, "Android Restart requested. Restarting when idle.")
        androidRestartRequested = true
        scheduleRestart(reason)
    }

    private fun scheduleRestart(reason: String = "") {
        pendingReason = if (reason.isEmpty()) pendingReason else reason

        if (conditions.all { c -> c.canRestartNow(this::scheduleRestart) }) {
            if (restartJob == null) {
                restartJob =
                    applicationScope.launch(backgroundDispatcher) {
                        delay(TimeUnit.SECONDS.toMillis(restartDelaySec))
                        restartNow()
                    }
            }
        } else {
            restartJob?.cancel()
            restartJob = null
        }
    }

    private fun restartNow() {
        if (androidRestartRequested) {
            systemExitRestarter.restartAndroid(pendingReason)
        } else {
            systemExitRestarter.restartSystemUI(pendingReason)
        }
    }

    interface Condition {
        /**
         * Should return true if the system is ready to restart.
         *
         * A call to this function means that we want to restart and are waiting for this condition
         * to return true.
         *
         * retryFn should be cached if it is _not_ ready to restart, and later called when it _is_
         * ready to restart. At that point, this method will be called again to verify that the
         * system is ready.
         *
         * Multiple calls to an instance of this method may happen for a single restart attempt if
         * multiple [Condition]s are being checked. If any one [Condition] returns false, all the
         * [Condition]s will need to be rechecked on the next restart attempt.
         */
        fun canRestartNow(retryFn: () -> Unit): Boolean
    }

    companion object {
        const val RESTART_DELAY = "restarter_restart_delay"
    }
}
