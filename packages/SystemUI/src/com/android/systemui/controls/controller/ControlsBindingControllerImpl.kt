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
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.Lazy
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@SysUISingleton
@VisibleForTesting
open class ControlsBindingControllerImpl @Inject constructor(
    private val context: Context,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val lazyController: Lazy<ControlsController>,
    private val packageUpdateMonitorFactory: PackageUpdateMonitor.Factory,
    userTracker: UserTracker,
) : ControlsBindingController {

    companion object {
        private const val TAG = "ControlsBindingControllerImpl"
        private const val MAX_CONTROLS_REQUEST = 100000L
        private const val SUGGESTED_STRUCTURES = 6L
        private const val SUGGESTED_CONTROLS_REQUEST =
            ControlsControllerImpl.SUGGESTED_CONTROLS_PER_STRUCTURE * SUGGESTED_STRUCTURES

        private val emptyCallback = object : ControlsBindingController.LoadCallback {
            override fun accept(controls: List<Control>) {}
            override fun error(message: String) {}
        }
    }

    private var currentUser = userTracker.userHandle

    override val currentUserId: Int
        get() = currentUser.identifier

    private var currentProvider: ControlsProviderLifecycleManager? = null

    /*
     * Will track any active subscriber for subscribe/unsubscribe requests coming into
     * this controller. Only one can be active at any time
     */
    private var statefulControlSubscriber: StatefulControlSubscriber? = null

    /*
     * Will track any active load subscriber. Only one can be active at any time.
     */
    private var loadSubscriber: LoadSubscriber? = null

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
                component,
                packageUpdateMonitorFactory
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
        loadSubscriber?.loadCancel()

        val ls = LoadSubscriber(callback, MAX_CONTROLS_REQUEST)
        loadSubscriber = ls

        retrieveLifecycleManager(component).maybeBindAndLoad(ls)
        return ls.loadCancel()
    }

    override fun bindAndLoadSuggested(
        component: ComponentName,
        callback: ControlsBindingController.LoadCallback
    ) {
        loadSubscriber?.loadCancel()
        val ls = LoadSubscriber(callback, SUGGESTED_CONTROLS_REQUEST)
        loadSubscriber = ls

        retrieveLifecycleManager(component).maybeBindAndLoadSuggested(ls)
    }

    override fun subscribe(structureInfo: StructureInfo) {
        // make sure this has happened. only allow one active subscription
        unsubscribe()

        val provider = retrieveLifecycleManager(structureInfo.componentName)
        val scs = StatefulControlSubscriber(
            lazyController.get(),
            provider,
            backgroundExecutor,
            MAX_CONTROLS_REQUEST
        )
        statefulControlSubscriber = scs
        provider.maybeBindAndSubscribe(structureInfo.controls.map { it.controlId }, scs)
    }

    override fun unsubscribe() {
        statefulControlSubscriber?.cancel()
        statefulControlSubscriber = null
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

    override fun bindServiceForPanel(component: ComponentName) {
        retrieveLifecycleManager(component).bindServiceForPanel()
    }

    override fun changeUser(newUser: UserHandle) {
        if (newUser == currentUser) return

        unbind()
        currentUser = newUser
    }

    private fun unbind() {
        unsubscribe()

        loadSubscriber?.loadCancel()
        loadSubscriber = null

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
            Log.d(TAG, "LoadSubscription: Complete and loading controls")
            callback.accept(list)
        }
    }

    private inner class OnCancelAndLoadRunnable(
        token: IBinder,
        val list: List<Control>,
        val subscription: IControlsSubscription,
        val callback: ControlsBindingController.LoadCallback
    ) : CallbackRunnable(token) {
        override fun doRun() {
            Log.d(TAG, "LoadSubscription: Canceling and loading controls")
            provider?.cancelSubscription(subscription)
            callback.accept(list)
        }
    }

    private inner class OnSubscribeRunnable(
        token: IBinder,
        val subscription: IControlsSubscription,
        val requestLimit: Long
    ) : CallbackRunnable(token) {
        override fun doRun() {
            Log.d(TAG, "LoadSubscription: Starting subscription")
            provider?.startSubscription(subscription, requestLimit)
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
        var callback: ControlsBindingController.LoadCallback,
        val requestLimit: Long
    ) : IControlsSubscriber.Stub() {
        val loadedControls = ArrayList<Control>()
        private var isTerminated = AtomicBoolean(false)
        private var _loadCancelInternal: (() -> Unit)? = null
        private lateinit var subscription: IControlsSubscription

        /**
         * Potentially cancel a subscriber. The subscriber may also have terminated, in which case
         * the request is ignored.
         */
        fun loadCancel() = Runnable {
            _loadCancelInternal?.let {
                Log.d(TAG, "Canceling loadSubscribtion")
                it.invoke()
            }
            callback.error("Load cancelled")
        }

        override fun onSubscribe(token: IBinder, subs: IControlsSubscription) {
            subscription = subs
            _loadCancelInternal = { currentProvider?.cancelSubscription(subscription) }
            backgroundExecutor.execute(OnSubscribeRunnable(token, subs, requestLimit))
        }

        override fun onNext(token: IBinder, c: Control) {
            backgroundExecutor.execute {
                if (isTerminated.get()) return@execute

                loadedControls.add(c)

                // Once we have reached our requestLimit, send a request to cancel, and immediately
                // load the results. Calls to onError() and onComplete() are not required after
                // cancel.
                if (loadedControls.size >= requestLimit) {
                    maybeTerminateAndRun(
                        OnCancelAndLoadRunnable(token, loadedControls, subscription, callback)
                    )
                }
            }
        }

        override fun onError(token: IBinder, s: String) {
            maybeTerminateAndRun(OnLoadErrorRunnable(token, s, callback))
        }

        override fun onComplete(token: IBinder) {
            maybeTerminateAndRun(OnLoadRunnable(token, loadedControls, callback))
        }

        private fun maybeTerminateAndRun(postTerminateFn: Runnable) {
            if (isTerminated.get()) return

            _loadCancelInternal = {}

            // Reassign the callback to clear references to other areas of code. Binders such as
            // this may not be GC'd right away, so do not hold onto these references.
            callback = emptyCallback
            currentProvider?.cancelLoadTimeout()

            backgroundExecutor.execute {
                isTerminated.compareAndSet(false, true)
                postTerminateFn.run()
            }
        }
    }
}

private data class Key(val component: ComponentName, val user: UserHandle)
