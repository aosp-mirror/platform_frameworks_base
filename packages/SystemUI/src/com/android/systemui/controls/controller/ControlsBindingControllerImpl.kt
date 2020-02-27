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
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
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

    private var currentUser = context.user

    override val currentUserId: Int
        get() = currentUser.identifier

    @GuardedBy("componentMap")
    private val tokenMap: MutableMap<IBinder, ControlsProviderLifecycleManager> =
            ArrayMap<IBinder, ControlsProviderLifecycleManager>()
    @GuardedBy("componentMap")
    private val componentMap: MutableMap<Key, ControlsProviderLifecycleManager> =
            ArrayMap<Key, ControlsProviderLifecycleManager>()

    private val actionCallbackService = object : IControlsActionCallback.Stub() {
        override fun accept(
            token: IBinder,
            controlId: String,
            @ControlAction.ResponseResult response: Int
        ) {
            backgroundExecutor.execute(OnActionResponseRunnable(token, controlId, response))
        }
    }

    private val subscriberService = object : IControlsSubscriber.Stub() {
        override fun onSubscribe(token: IBinder, subs: IControlsSubscription) {
            backgroundExecutor.execute(OnSubscribeRunnable(token, subs))
        }

        override fun onNext(token: IBinder, c: Control) {
            if (!refreshing.get()) {
                Log.d(TAG, "Refresh outside of window for token:$token")
            } else {
                backgroundExecutor.execute(OnNextRunnable(token, c))
            }
        }
        override fun onError(token: IBinder, s: String) {
            backgroundExecutor.execute(OnErrorRunnable(token, s))
        }

        override fun onComplete(token: IBinder) {
            backgroundExecutor.execute(OnCompleteRunnable(token))
        }
    }

    @VisibleForTesting
    internal open fun createProviderManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        return ControlsProviderLifecycleManager(
                context,
                backgroundExecutor,
                actionCallbackService,
                subscriberService,
                currentUser,
                component
        )
    }

    private fun retrieveLifecycleManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        synchronized(componentMap) {
            val provider = componentMap.getOrPut(Key(component, currentUser)) {
                createProviderManager(component)
            }
            tokenMap.putIfAbsent(provider.token, provider)
            return provider
        }
    }

    override fun bindAndLoad(
        component: ComponentName,
        callback: ControlsBindingController.LoadCallback
    ) {
        val provider = retrieveLifecycleManager(component)
        provider.maybeBindAndLoad(LoadSubscriber(callback))
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
                if (it.key.component !in providersWithFavorites) {
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
            backgroundExecutor.execute { provider.bindService() }
        }
    }

    override fun changeUser(newUser: UserHandle) {
        if (newUser == currentUser) return
        synchronized(componentMap) {
            unbindAllProvidersLocked() // unbind all providers from the old user
        }
        refreshing.set(false)
        currentUser = newUser
    }

    private fun unbindAllProvidersLocked() {
        componentMap.values.forEach {
            if (it.user == currentUser) {
                it.unbindService()
            }
        }
    }

    override fun onComponentRemoved(componentName: ComponentName) {
        backgroundExecutor.execute {
            synchronized(componentMap) {
                val removed = componentMap.remove(Key(componentName, currentUser))
                removed?.let {
                    it.unbindService()
                    tokenMap.remove(it.token)
                }
            }
        }
    }

    override fun toString(): String {
        return StringBuilder("  ControlsBindingController:\n").apply {
            append("    refreshing=${refreshing.get()}\n")
            append("    currentUser=$currentUser\n")
            append("    Providers:\n")
            synchronized(componentMap) {
                componentMap.values.forEach {
                    append("      $it\n")
                }
            }
        }.toString()
    }

    private abstract inner class CallbackRunnable(val token: IBinder) : Runnable {
        protected val provider: ControlsProviderLifecycleManager? =
                synchronized(componentMap) {
                    tokenMap.get(token)
                }
    }

    private inner class OnLoadRunnable(
        token: IBinder,
        val list: List<Control>,
        val callback: ControlsBindingController.LoadCallback
    ) : CallbackRunnable(token) {
        override fun run() {
            if (provider == null) {
                Log.e(TAG, "No provider found for token:$token")
                return
            }
            if (provider.user != currentUser) {
                Log.e(TAG, "User ${provider.user} is not current user")
                return
            }
            synchronized(componentMap) {
                if (token !in tokenMap.keys) {
                    Log.e(TAG, "Provider for token:$token does not exist anymore")
                    return
                }
            }
            callback.accept(list)
            provider.unbindService()
        }
    }

    private inner class OnNextRunnable(
        token: IBinder,
        val control: Control
    ) : CallbackRunnable(token) {
        override fun run() {
            if (!refreshing.get()) {
                Log.d(TAG, "onRefresh outside of window from:${provider?.componentName}")
            }
            if (provider?.user != currentUser) {
                Log.e(TAG, "User ${provider?.user} is not current user")
                return
            }
            provider?.let {
                lazyController.get().refreshStatus(it.componentName, control)
            }
        }
    }

    private inner class OnSubscribeRunnable(
        token: IBinder,
        val subscription: IControlsSubscription
    ) : CallbackRunnable(token) {
        override fun run() {
            if (!refreshing.get()) {
                Log.d(TAG, "onRefresh outside of window from '${provider?.componentName}'")
            }
            provider?.let {
                it.startSubscription(subscription)
            }
        }
    }

    private inner class OnCompleteRunnable(
        token: IBinder
    ) : CallbackRunnable(token) {
        override fun run() {
            provider?.let {
                Log.i(TAG, "onComplete receive from '${it.componentName}'")
            }
        }
    }

    private inner class OnErrorRunnable(
        token: IBinder,
        val error: String
    ) : CallbackRunnable(token) {
        override fun run() {
            provider?.let {
                Log.e(TAG, "onError receive from '${it.componentName}': $error")
            }
        }
    }

    private inner class OnActionResponseRunnable(
        token: IBinder,
        val controlId: String,
        @ControlAction.ResponseResult val response: Int
    ) : CallbackRunnable(token) {
        override fun run() {
            if (provider?.user != currentUser) {
                Log.e(TAG, "User ${provider?.user} is not current user")
                return
            }
            provider?.let {
                lazyController.get().onActionResponse(it.componentName, controlId, response)
            }
        }
    }

    private inner class OnLoadErrorRunnable(
        token: IBinder,
        val error: String,
        val callback: ControlsBindingController.LoadCallback
    ) : CallbackRunnable(token) {
        override fun run() {
            callback.error(error)
            provider?.let {
                Log.e(TAG, "onError receive from '${it.componentName}': $error")
            }
        }
    }

    private inner class LoadSubscriber(
        val callback: ControlsBindingController.LoadCallback
    ) : IControlsSubscriber.Stub() {
        val loadedControls = ArrayList<Control>()
        var hasError = false

        override fun onSubscribe(token: IBinder, subs: IControlsSubscription) {
            backgroundExecutor.execute(OnSubscribeRunnable(token, subs))
        }

        override fun onNext(token: IBinder, c: Control) {
            backgroundExecutor.execute { loadedControls.add(c) }
        }
        override fun onError(token: IBinder, s: String) {
            hasError = true
            backgroundExecutor.execute(OnLoadErrorRunnable(token, s, callback))
        }

        override fun onComplete(token: IBinder) {
            if (!hasError) {
                backgroundExecutor.execute(OnLoadRunnable(token, loadedControls, callback))
            }
        }
    }
}

private data class Key(val component: ComponentName, val user: UserHandle)
