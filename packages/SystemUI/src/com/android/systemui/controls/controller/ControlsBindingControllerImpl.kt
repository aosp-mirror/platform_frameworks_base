/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.controls.controller

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.service.controls.Control
import android.service.controls.IControlsProviderCallback
import android.service.controls.actions.ControlAction
import android.util.ArrayMap
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.Lazy
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@VisibleForTesting
open class ControlsBindingControllerImpl @Inject constructor(
    private val context: Context,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val lazyController: Lazy<ControlsController>
) : ControlsBindingController {

    companion object {
        private const val TAG = "ControlsBindingControllerImpl"
    }

    private val refreshing = AtomicBoolean(false)

    @GuardedBy("componentMap")
    private val tokenMap: MutableMap<IBinder, ControlsProviderLifecycleManager> =
            ArrayMap<IBinder, ControlsProviderLifecycleManager>()
    @GuardedBy("componentMap")
    private val componentMap: MutableMap<ComponentName, ControlsProviderLifecycleManager> =
            ArrayMap<ComponentName, ControlsProviderLifecycleManager>()

    private val serviceCallback = object : IControlsProviderCallback.Stub() {
        override fun onLoad(token: IBinder, controls: MutableList<Control>) {
            backgroundExecutor.execute(OnLoadRunnable(token, controls))
        }

        override fun onRefreshState(token: IBinder, controlStates: List<Control>) {
            if (!refreshing.get()) {
                Log.d(TAG, "Refresh outside of window for token:$token")
            } else {
                backgroundExecutor.execute(OnRefreshStateRunnable(token, controlStates))
            }
        }

        override fun onControlActionResponse(
            token: IBinder,
            controlId: String,
            @ControlAction.ResponseResult response: Int
        ) {
            backgroundExecutor.execute(OnActionResponseRunnable(token, controlId, response))
        }
    }

    @VisibleForTesting
    internal open fun createProviderManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        return ControlsProviderLifecycleManager(
                context,
                backgroundExecutor,
                serviceCallback,
                component
        )
    }

    private fun retrieveLifecycleManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        synchronized(componentMap) {
            val provider = componentMap.getOrPut(component) {
                createProviderManager(component)
            }
            tokenMap.putIfAbsent(provider.token, provider)
            return provider
        }
    }

    override fun bindAndLoad(component: ComponentName, callback: (List<Control>) -> Unit) {
        val provider = retrieveLifecycleManager(component)
        provider.maybeBindAndLoad(callback)
    }

    override fun subscribe(controls: List<ControlInfo>) {
        val controlsByComponentName = controls.groupBy { it.component }
        if (refreshing.compareAndSet(false, true)) {
            controlsByComponentName.forEach {
                val provider = retrieveLifecycleManager(it.key)
                backgroundExecutor.execute {
                    provider.maybeBindAndSubscribe(it.value.map { it.controlId })
                }
            }
        }
        // Unbind unneeded providers
        val providersWithFavorites = controlsByComponentName.keys
        synchronized(componentMap) {
            componentMap.forEach {
                if (it.key !in providersWithFavorites) {
                    backgroundExecutor.execute { it.value.unbindService() }
                }
            }
        }
    }

    override fun unsubscribe() {
        if (refreshing.compareAndSet(true, false)) {
            val providers = synchronized(componentMap) {
                componentMap.values.toList()
            }
            providers.forEach {
                backgroundExecutor.execute { it.unsubscribe() }
            }
        }
    }

    override fun action(controlInfo: ControlInfo, action: ControlAction) {
        val provider = retrieveLifecycleManager(controlInfo.component)
        provider.maybeBindAndSendAction(controlInfo.controlId, action)
    }

    override fun bindServices(components: List<ComponentName>) {
        components.forEach {
            val provider = retrieveLifecycleManager(it)
            backgroundExecutor.execute { provider.bindPermanently() }
        }
    }

    private abstract inner class CallbackRunnable(val token: IBinder) : Runnable {
        protected val provider: ControlsProviderLifecycleManager? =
                synchronized(componentMap) {
                    tokenMap.get(token)
                }
    }

    private inner class OnLoadRunnable(
        token: IBinder,
        val list: List<Control>
    ) : CallbackRunnable(token) {
        override fun run() {
            if (provider == null) {
                Log.e(TAG, "No provider found for token:$token")
                return
            }
            synchronized(componentMap) {
                if (token !in tokenMap.keys) {
                    Log.e(TAG, "Provider for token:$token does not exist anymore")
                    return
                }
            }
            provider.lastLoadCallback?.invoke(list) ?: run {
                Log.w(TAG, "Null callback")
            }
            provider.maybeUnbindAndRemoveCallback()
        }
    }

    private inner class OnRefreshStateRunnable(
        token: IBinder,
        val list: List<Control>
    ) : CallbackRunnable(token) {
        override fun run() {
            if (!refreshing.get()) {
                Log.d(TAG, "onRefresh outside of window from:${provider?.componentName}")
            }
            provider?.let {
                lazyController.get().refreshStatus(it.componentName, list)
            }
        }
    }

    private inner class OnActionResponseRunnable(
        token: IBinder,
        val controlId: String,
        @ControlAction.ResponseResult val response: Int
    ) : CallbackRunnable(token) {
        override fun run() {
            provider?.let {
                lazyController.get().onActionResponse(it.componentName, controlId, response)
            }
        }
    }
}