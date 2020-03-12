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

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
import android.service.controls.actions.ControlAction
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.Lazy
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

    private var currentUser = UserHandle.of(ActivityManager.getCurrentUser())

    override val currentUserId: Int
        get() = currentUser.identifier

    private var currentProvider: ControlsProviderLifecycleManager? = null

    /*
     * Will track any active subscriber for subscribe/unsubscribe requests coming into
     * this controller. Only one can be active at any time
     */
    private var statefulControlSubscriber: StatefulControlSubscriber? = null

    private val actionCallbackService = object : IControlsActionCallback.Stub() {
        override fun accept(
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
                actionCallbackService,
                currentUser,
                component
        )
    }

    private fun retrieveLifecycleManager(component: ComponentName):
            ControlsProviderLifecycleManager {
        if (currentProvider != null && currentProvider?.componentName != component) {
            unbind()
        }

        val provider = currentProvider ?: createProviderManager(component)
        currentProvider = provider

        return provider
    }

    override fun bindAndLoad(
        component: ComponentName,
        callback: ControlsBindingController.LoadCallback
    ): Runnable {
        val subscriber = LoadSubscriber(callback)
        retrieveLifecycleManager(component).maybeBindAndLoad(subscriber)
        return subscriber.loadCancel()
    }

    override fun subscribe(structureInfo: StructureInfo) {
        // make sure this has happened. only allow one active subscription
        unsubscribe()

        statefulControlSubscriber = null
        val provider = retrieveLifecycleManager(structureInfo.componentName)
        val scs = StatefulControlSubscriber(lazyController.get(), provider, backgroundExecutor)
        statefulControlSubscriber = scs
        provider.maybeBindAndSubscribe(structureInfo.controls.map { it.controlId }, scs)
    }

    override fun unsubscribe() {
        statefulControlSubscriber?.cancel()
    }

    override fun action(
        componentName: ComponentName,
        controlInfo: ControlInfo,
        action: ControlAction
    ) {
        if (statefulControlSubscriber == null) {
            Log.w(TAG, "No actions can occur outside of an active subscription. Ignoring.")
        } else {
            retrieveLifecycleManager(componentName)
                .maybeBindAndSendAction(controlInfo.controlId, action)
        }
    }

    override fun bindService(component: ComponentName) {
        retrieveLifecycleManager(component).bindService()
    }

    override fun changeUser(newUser: UserHandle) {
        if (newUser == currentUser) return

        unsubscribe()
        unbind()
        currentProvider = null
        currentUser = newUser
    }

    private fun unbind() {
        currentProvider?.unbindService()
        currentProvider = null
    }

    override fun onComponentRemoved(componentName: ComponentName) {
        backgroundExecutor.execute {
            currentProvider?.let {
                if (it.componentName == componentName) {
                    unbind()
                }
            }
        }
    }

    override fun toString(): String {
        return StringBuilder("  ControlsBindingController:\n").apply {
            append("    currentUser=$currentUser\n")
            append("    StatefulControlSubscriber=$statefulControlSubscriber")
            append("    Providers=$currentProvider\n")
        }.toString()
    }

    private abstract inner class CallbackRunnable(val token: IBinder) : Runnable {
        protected val provider: ControlsProviderLifecycleManager? = currentProvider

        override fun run() {
            if (provider == null) {
                Log.e(TAG, "No current provider set")
                return
            }
            if (provider.user != currentUser) {
                Log.e(TAG, "User ${provider.user} is not current user")
                return
            }
            if (token != provider.token) {
                Log.e(TAG, "Provider for token:$token does not exist anymore")
                return
            }

            doRun()
        }

        abstract fun doRun()
    }

    private inner class OnLoadRunnable(
        token: IBinder,
        val list: List<Control>,
        val callback: ControlsBindingController.LoadCallback
    ) : CallbackRunnable(token) {
        override fun doRun() {
            callback.accept(list)
        }
    }

    private inner class OnSubscribeRunnable(
        token: IBinder,
        val subscription: IControlsSubscription
    ) : CallbackRunnable(token) {
        override fun doRun() {
            provider?.startSubscription(subscription)
        }
    }

    private inner class OnActionResponseRunnable(
        token: IBinder,
        val controlId: String,
        @ControlAction.ResponseResult val response: Int
    ) : CallbackRunnable(token) {
        override fun doRun() {
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
        override fun doRun() {
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
        private var _loadCancelInternal: (() -> Unit)? = null
        fun loadCancel() = Runnable {
                Log.d(TAG, "Cancel load requested")
                _loadCancelInternal?.invoke()
            }

        override fun onSubscribe(token: IBinder, subs: IControlsSubscription) {
            _loadCancelInternal = subs::cancel
            backgroundExecutor.execute(OnSubscribeRunnable(token, subs))
        }

        override fun onNext(token: IBinder, c: Control) {
            backgroundExecutor.execute { loadedControls.add(c) }
        }
        override fun onError(token: IBinder, s: String) {
            hasError = true
            _loadCancelInternal = {}
            currentProvider?.cancelLoadTimeout()
            backgroundExecutor.execute(OnLoadErrorRunnable(token, s, callback))
        }

        override fun onComplete(token: IBinder) {
            _loadCancelInternal = {}
            if (!hasError) {
                currentProvider?.cancelLoadTimeout()
                backgroundExecutor.execute(OnLoadRunnable(token, loadedControls, callback))
            }
        }
    }
}

private data class Key(val component: ComponentName, val user: UserHandle)
