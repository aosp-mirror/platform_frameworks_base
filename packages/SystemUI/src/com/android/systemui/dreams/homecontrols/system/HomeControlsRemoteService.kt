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

package com.android.systemui.dreams.homecontrols.system

import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.systemui.controls.settings.ControlsSettingsRepository
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dreams.DreamLogger
import com.android.systemui.dreams.homecontrols.shared.IHomeControlsRemoteProxy
import com.android.systemui.dreams.homecontrols.shared.IOnControlsSettingsChangeListener
import com.android.systemui.dreams.homecontrols.system.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.DreamLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

/**
 * Service which exports the current home controls component name, for use in SystemUI processes
 * running in other users. This service should only run in the system user.
 */
class HomeControlsRemoteService
@Inject
constructor(binderFactory: HomeControlsRemoteServiceBinder.Factory) : LifecycleService() {
    val binder by lazy { binderFactory.create(this) }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        binder.onDestroy()
    }
}

class HomeControlsRemoteServiceBinder
@AssistedInject
constructor(
    private val homeControlsComponentInteractor: HomeControlsComponentInteractor,
    private val controlsSettingsRepository: ControlsSettingsRepository,
    @Background private val bgContext: CoroutineContext,
    @DreamLog logBuffer: LogBuffer,
    @Assisted lifecycleOwner: LifecycleOwner,
) : IHomeControlsRemoteProxy.Stub(), LifecycleOwner by lifecycleOwner {
    private val logger = DreamLogger(logBuffer, TAG)
    private val callbacks =
        object : RemoteCallbackList<IOnControlsSettingsChangeListener>() {
            override fun onCallbackDied(listener: IOnControlsSettingsChangeListener?) {
                if (callbackCount.decrementAndGet() == 0) {
                    logger.d("Cancelling collection due to callback death")
                    collectionJob?.cancel()
                    collectionJob = null
                }
            }
        }
    private val callbackCount = AtomicInteger(0)
    private var collectionJob: Job? = null

    override fun registerListenerForCurrentUser(listener: IOnControlsSettingsChangeListener?) {
        if (listener == null) return
        logger.d("Register listener")
        val registered = callbacks.register(listener)
        if (registered && callbackCount.getAndIncrement() == 0) {
            // If the first listener, start the collection job. This will also take
            // care of notifying the listener of the initial state.
            logger.d("Starting collection")
            collectionJob =
                lifecycleScope.launch(bgContext) {
                    combine(
                            homeControlsComponentInteractor.panelComponent,
                            controlsSettingsRepository.allowActionOnTrivialControlsInLockscreen,
                        ) { panelComponent, allowTrivialControls ->
                            callbacks.notifyAllCallbacks(panelComponent, allowTrivialControls)
                        }
                        .launchIn(this)
                }
        } else if (registered) {
            // If not the first listener, notify the listener of the current value immediately.
            listener.notify(
                homeControlsComponentInteractor.panelComponent.value,
                controlsSettingsRepository.allowActionOnTrivialControlsInLockscreen.value,
            )
        }
    }

    override fun unregisterListenerForCurrentUser(listener: IOnControlsSettingsChangeListener?) {
        if (listener == null) return
        logger.d("Unregister listener")
        if (callbacks.unregister(listener) && callbackCount.decrementAndGet() == 0) {
            logger.d("Cancelling collection due to unregister")
            collectionJob?.cancel()
            collectionJob = null
        }
    }

    private companion object {
        const val TAG = "HomeControlsRemoteServiceBinder"
    }

    private fun IOnControlsSettingsChangeListener.notify(
        panelComponent: ComponentName?,
        allowTrivialControlsOnLockscreen: Boolean,
    ) {
        try {
            onControlsSettingsChanged(panelComponent, allowTrivialControlsOnLockscreen)
        } catch (e: RemoteException) {
            Log.e(TAG, "Error notifying callback", e)
        }
    }

    private fun RemoteCallbackList<IOnControlsSettingsChangeListener>.notifyAllCallbacks(
        panelComponent: ComponentName?,
        allowTrivialControlsOnLockscreen: Boolean,
    ) {
        val itemCount = beginBroadcast()
        try {
            for (i in 0 until itemCount) {
                getBroadcastItem(i).notify(panelComponent, allowTrivialControlsOnLockscreen)
            }
        } finally {
            finishBroadcast()
        }
    }

    fun onDestroy() {
        logger.d("Service destroyed")
        callbacks.kill()
        callbackCount.set(0)
        collectionJob?.cancel()
        collectionJob = null
    }

    @AssistedFactory
    interface Factory {
        fun create(lifecycleOwner: LifecycleOwner): HomeControlsRemoteServiceBinder
    }
}
