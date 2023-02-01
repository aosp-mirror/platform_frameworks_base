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
 */

package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.IBiometricContextListener
import android.util.Log
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FoldStateProvider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * Aggregates UI/device state that is not directly related to biometrics, but is often useful for
 * logging or optimization purposes (fold state, screen state, etc.)
 */
interface LogContextInteractor {

    /** If the device is dozing. */
    val isDozing: Flow<Boolean>

    /** If the device is currently awake with the screen on. */
    val isAwake: Flow<Boolean>

    /** Current device fold state, defined as [IBiometricContextListener.FoldState]. */
    val foldState: Flow<Int>

    /**
     * Add a permanent context listener.
     *
     * Use this method for registering remote context listeners. Use the properties exposed via this
     * class directly within SysUI.
     */
    fun addBiometricContextListener(listener: IBiometricContextListener): Job
}

@SysUISingleton
class LogContextInteractorImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val statusBarStateController: StatusBarStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val foldProvider: FoldStateProvider,
) : LogContextInteractor {

    init {
        foldProvider.start()
    }

    override val isDozing =
        conflatedCallbackFlow {
                val callback =
                    object : StatusBarStateController.StateListener {
                        override fun onDozingChanged(isDozing: Boolean) {
                            trySendWithFailureLogging(isDozing, TAG)
                        }
                    }

                statusBarStateController.addCallback(callback)
                trySendWithFailureLogging(statusBarStateController.isDozing, TAG)
                awaitClose { statusBarStateController.removeCallback(callback) }
            }
            .distinctUntilChanged()

    override val isAwake =
        conflatedCallbackFlow {
                val callback =
                    object : WakefulnessLifecycle.Observer {
                        override fun onFinishedWakingUp() {
                            trySendWithFailureLogging(true, TAG)
                        }

                        override fun onStartedGoingToSleep() {
                            trySendWithFailureLogging(false, TAG)
                        }
                    }

                wakefulnessLifecycle.addObserver(callback)
                trySendWithFailureLogging(wakefulnessLifecycle.isAwake, TAG)
                awaitClose { wakefulnessLifecycle.removeObserver(callback) }
            }
            .distinctUntilChanged()

    override val foldState: Flow<Int> =
        conflatedCallbackFlow {
                val callback =
                    object : FoldStateProvider.FoldUpdatesListener {
                        override fun onHingeAngleUpdate(angle: Float) {}

                        override fun onFoldUpdate(@FoldStateProvider.FoldUpdate update: Int) {
                            val loggedState =
                                when (update) {
                                    FOLD_UPDATE_FINISH_HALF_OPEN ->
                                        IBiometricContextListener.FoldState.HALF_OPENED
                                    FOLD_UPDATE_FINISH_FULL_OPEN ->
                                        IBiometricContextListener.FoldState.FULLY_OPENED
                                    FOLD_UPDATE_FINISH_CLOSED ->
                                        IBiometricContextListener.FoldState.FULLY_CLOSED
                                    else -> null
                                }
                            if (loggedState != null) {
                                trySendWithFailureLogging(loggedState, TAG)
                            }
                        }
                    }

                foldProvider.addCallback(callback)
                trySendWithFailureLogging(IBiometricContextListener.FoldState.UNKNOWN, TAG)
                awaitClose { foldProvider.removeCallback(callback) }
            }
            .shareIn(applicationScope, started = SharingStarted.Eagerly, replay = 1)

    override fun addBiometricContextListener(listener: IBiometricContextListener): Job {
        return applicationScope.launch {
            combine(isDozing, isAwake) { doze, awake -> doze to awake }
                .onEach { (doze, awake) -> listener.onDozeChanged(doze, awake) }
                .catch { t -> Log.w(TAG, "failed to notify new doze state", t) }
                .launchIn(this)

            foldState
                .onEach { state -> listener.onFoldChanged(state) }
                .catch { t -> Log.w(TAG, "failed to notify new fold state", t) }
                .launchIn(this)

            listener.asBinder().linkToDeath({ cancel() }, 0)
        }
    }

    companion object {
        private const val TAG = "ContextRepositoryImpl"
    }
}

private val WakefulnessLifecycle.isAwake: Boolean
    get() = wakefulness == WakefulnessLifecycle.WAKEFULNESS_AWAKE
