/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.WorkerThread
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import android.service.controls.ControlsProviderService
import android.service.controls.ControlsProviderService.CALLBACK_BUNDLE
import android.service.controls.ControlsProviderService.CALLBACK_TOKEN
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsProvider
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
import android.service.controls.actions.ControlAction
import android.util.ArraySet
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager for the lifecycle of the connection to a given [ControlsProviderService].
 *
 * This class handles binding and unbinding and requests to the service. The class will queue
 * requests until the service is connected and dispatch them then.
 *
 * If the provider app is updated, and we are currently bound to it, it will try to rebind after
 * update is completed.
 *
 * @property context A SystemUI context for binding to the services
 * @property executor A delayable executor for posting timeouts
 * @property actionCallbackService a callback interface to hand the remote service for sending
 *                                 action responses
 * @property subscriberService an "subscriber" interface for requesting and accepting updates for
 *                             controls from the service.
 * @property user the user for whose this service should be bound.
 * @property componentName the name of the component for the service.
 */
class ControlsProviderLifecycleManager(
    private val context: Context,
    private val executor: DelayableExecutor,
    private val actionCallbackService: IControlsActionCallback.Stub,
    val user: UserHandle,
    val componentName: ComponentName,
    packageUpdateMonitorFactory: PackageUpdateMonitor.Factory,
) {

    val token: IBinder = Binder()
    private var requiresBound = false
    @GuardedBy("queuedServiceMethods")
    private val queuedServiceMethods: MutableSet<ServiceMethod> = ArraySet()
    private var wrapper: ServiceWrapper? = null
    private val TAG = javaClass.simpleName
    private var onLoadCanceller: Runnable? = null

    private var lastForPanel = false

    companion object {
        private const val LOAD_TIMEOUT_SECONDS = 20L // seconds
        private const val DEBUG = true
        private val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_FOREGROUND_SERVICE or
            Context.BIND_NOT_PERCEPTIBLE
        // Use BIND_NOT_PERCEPTIBLE so it will be at lower priority from SystemUI.
        // However, don't use WAIVE_PRIORITY, as by itself, it will kill the app
        // once the Task is finished in the device controls panel.
        private val BIND_FLAGS_PANEL = Context.BIND_AUTO_CREATE or Context.BIND_NOT_PERCEPTIBLE
    }

    private val intent = Intent(ControlsProviderService.SERVICE_CONTROLS).apply {
        component = componentName
        putExtra(CALLBACK_BUNDLE, Bundle().apply {
            putBinder(CALLBACK_TOKEN, token)
        })
    }

    private val packageUpdateMonitor = packageUpdateMonitorFactory.create(
        user,
        componentName.packageName,
    ) {
        if (requiresBound) {
            // Let's unbind just in case. onBindingDied should have been called and unbound before.
            executor.execute {
                unbindAndCleanup("package updated")
                bindService(true, lastForPanel)
            }
        }
    }

    private fun bindService(bind: Boolean, forPanel: Boolean = false) {
        executor.execute {
            bindServiceBackground(bind, forPanel)
        }
    }

    private val serviceConnection = object : ServiceConnection {

        val connected = AtomicBoolean(false)

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (DEBUG) Log.d(TAG, "onServiceConnected $name")
            wrapper = ServiceWrapper(IControlsProvider.Stub.asInterface(service))
            packageUpdateMonitor.startMonitoring()
            handlePendingServiceMethods()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected $name")
            wrapper = null
            // No need to call unbind. We may get a new `onServiceConnected`
        }

        override fun onNullBinding(name: ComponentName?) {
            if (DEBUG) Log.d(TAG, "onNullBinding $name")
            wrapper = null
            executor.execute {
                unbindAndCleanup("null binding")
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            if (DEBUG) Log.d(TAG, "onBindingDied $name")
            executor.execute {
                unbindAndCleanup("binder died")
            }
        }
    }

    private fun handlePendingServiceMethods() {
        val queue = synchronized(queuedServiceMethods) {
            ArraySet(queuedServiceMethods).also {
                queuedServiceMethods.clear()
            }
        }
        queue.forEach {
            it.run()
        }
    }

    @WorkerThread
    private fun bindServiceBackground(bind: Boolean, forPanel: Boolean = true) {
        requiresBound = bind
        if (bind) {
            if (wrapper == null) {
                if (DEBUG) {
                    Log.d(TAG, "Binding service $intent")
                }
                try {
                    lastForPanel = forPanel
                    val flags = if (forPanel) BIND_FLAGS_PANEL else BIND_FLAGS
                    var bound = false
                    if (serviceConnection.connected.compareAndSet(false, true)) {
                        bound = context
                            .bindServiceAsUser(intent, serviceConnection, flags, user)
                    }
                    if (!bound) {
                        Log.d(TAG, "Couldn't bind to $intent")
                        doUnbind()
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to bind to service", e)
                    // Couldn't even bind. Let's reset the connected value
                    serviceConnection.connected.set(false)
                }
            }
        } else {
            unbindAndCleanup("unbind requested")
            packageUpdateMonitor.stopMonitoring()
        }
    }

    @WorkerThread
    private fun unbindAndCleanup(reason: String) {
        if (DEBUG) {
            Log.d(TAG, "Unbinding service $intent. Reason: $reason")
        }
        wrapper = null
        try {
            doUnbind()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to unbind service", e)
        }
    }

    @WorkerThread
    private fun doUnbind() {
        if (serviceConnection.connected.compareAndSet(true, false)) {
            context.unbindService(serviceConnection)
        }
    }

    private fun queueServiceMethod(sm: ServiceMethod) {
        synchronized(queuedServiceMethods) {
            queuedServiceMethods.add(sm)
        }
    }

    private fun invokeOrQueue(sm: ServiceMethod) {
        wrapper?.run {
            sm.run()
        } ?: run {
            queueServiceMethod(sm)
            bindService(true)
        }
    }

    /**
     * Request a call to [IControlsProvider.load].
     *
     * If the service is not bound, the call will be queued and the service will be bound first.
     * The service will be unbound after the controls are returned or the call times out.
     *
     * @param subscriber the subscriber that manages coordination for loading controls
     */
    fun maybeBindAndLoad(subscriber: IControlsSubscriber.Stub) {
        onLoadCanceller = executor.executeDelayed({
            // Didn't receive a response in time, log and send back error
            Log.d(TAG, "Timeout waiting onLoad for $componentName")
            subscriber.onError(token, "Timeout waiting onLoad")
            unbindService()
        }, LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        invokeOrQueue(Load(subscriber))
    }

    /**
     * Request a call to [IControlsProvider.loadSuggested].
     *
     * If the service is not bound, the call will be queued and the service will be bound first.
     * The service will be unbound if the call times out.
     *
     * @param subscriber the subscriber that manages coordination for loading controls
     */
    fun maybeBindAndLoadSuggested(subscriber: IControlsSubscriber.Stub) {
        onLoadCanceller = executor.executeDelayed({
            // Didn't receive a response in time, log and send back error
            Log.d(TAG, "Timeout waiting onLoadSuggested for $componentName")
            subscriber.onError(token, "Timeout waiting onLoadSuggested")
            unbindService()
        }, LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        invokeOrQueue(Suggest(subscriber))
    }

    fun cancelLoadTimeout() {
        onLoadCanceller?.run()
        onLoadCanceller = null
    }

    /**
     * Request a subscription to the [Publisher] returned by [ControlsProviderService.publisherFor]
     *
     * If the service is not bound, the call will be queued and the service will be bound first.
     *
     * @param controlIds a list of the ids of controls to send status back.
     */
    fun maybeBindAndSubscribe(controlIds: List<String>, subscriber: IControlsSubscriber) =
        invokeOrQueue(Subscribe(controlIds, subscriber))

    /**
     * Request a call to [ControlsProviderService.performControlAction].
     *
     * If the service is not bound, the call will be queued and the service will be bound first.
     *
     * @param controlId the id of the [Control] the action is performed on
     * @param action the action performed
     */
    fun maybeBindAndSendAction(controlId: String, action: ControlAction) =
        invokeOrQueue(Action(controlId, action))

    /**
     * Starts the subscription to the [ControlsProviderService] and requests status of controls.
     *
     * @param subscription the subscription to use to request controls
     * @see maybeBindAndLoad
     */
    fun startSubscription(subscription: IControlsSubscription, requestLimit: Long) {
        if (DEBUG) {
            Log.d(TAG, "startSubscription: $subscription")
        }

        wrapper?.request(subscription, requestLimit)
    }

    /**
     * Cancels the subscription to the [ControlsProviderService].
     *
     * @param subscription the subscription to cancel
     * @see maybeBindAndLoad
     */
    fun cancelSubscription(subscription: IControlsSubscription) {
        if (DEBUG) {
            Log.d(TAG, "cancelSubscription: $subscription")
        }

        wrapper?.cancel(subscription)
    }

    /**
     * Request bind to the service.
     */
    fun bindService() {
        bindService(true)
    }

    fun bindServiceForPanel() {
        bindService(bind = true, forPanel = true)
    }

    /**
     * Request unbind from the service.
     */
    fun unbindService() {
        onLoadCanceller?.run()
        onLoadCanceller = null

        bindService(false)
    }

    override fun toString(): String {
        return StringBuilder("ControlsProviderLifecycleManager(").apply {
            append("component=$componentName")
            append(", user=$user")
            append(")")
        }.toString()
    }

    /**
     * Service methods that can be queued or invoked, and are retryable for failure scenarios
     */
    abstract inner class ServiceMethod {
        fun run() {
            if (!callWrapper()) {
                queueServiceMethod(this)
                executor.execute { unbindAndCleanup("couldn't call through binder") }
            }
        }

        internal abstract fun callWrapper(): Boolean
    }

    inner class Load(val subscriber: IControlsSubscriber.Stub) : ServiceMethod() {
        override fun callWrapper(): Boolean {
            if (DEBUG) {
                Log.d(TAG, "load $componentName")
            }
            return wrapper?.load(subscriber) ?: false
        }
    }

    inner class Suggest(val subscriber: IControlsSubscriber.Stub) : ServiceMethod() {
        override fun callWrapper(): Boolean {
            if (DEBUG) {
                Log.d(TAG, "suggest $componentName")
            }
            return wrapper?.loadSuggested(subscriber) ?: false
        }
    }
    inner class Subscribe(
        val list: List<String>,
        val subscriber: IControlsSubscriber
    ) : ServiceMethod() {
        override fun callWrapper(): Boolean {
            if (DEBUG) {
                Log.d(TAG, "subscribe $componentName - $list")
            }

            return wrapper?.subscribe(list, subscriber) ?: false
        }
    }

    inner class Action(val id: String, val action: ControlAction) : ServiceMethod() {
        override fun callWrapper(): Boolean {
            if (DEBUG) {
                Log.d(TAG, "onAction $componentName - $id")
            }
            return wrapper?.action(id, action, actionCallbackService) ?: false
        }
    }
}
