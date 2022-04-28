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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
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

/**
 * Manager for the lifecycle of the connection to a given [ControlsProviderService].
 *
 * This class handles binding and unbinding and requests to the service. The class will queue
 * requests until the service is connected and dispatch them then.
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
    val componentName: ComponentName
) : IBinder.DeathRecipient {

    val token: IBinder = Binder()
    private var requiresBound = false
    @GuardedBy("queuedServiceMethods")
    private val queuedServiceMethods: MutableSet<ServiceMethod> = ArraySet()
    private var wrapper: ServiceWrapper? = null
    private var bindTryCount = 0
    private val TAG = javaClass.simpleName
    private var onLoadCanceller: Runnable? = null

    companion object {
        private const val BIND_RETRY_DELAY = 1000L // ms
        private const val LOAD_TIMEOUT_SECONDS = 20L // seconds
        private const val MAX_BIND_RETRIES = 5
        private const val DEBUG = true
        private val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_FOREGROUND_SERVICE or
            Context.BIND_NOT_PERCEPTIBLE
    }

    private val intent = Intent().apply {
        component = componentName
        putExtra(CALLBACK_BUNDLE, Bundle().apply {
            putBinder(CALLBACK_TOKEN, token)
        })
    }

    private fun bindService(bind: Boolean) {
        executor.execute {
            requiresBound = bind
            if (bind) {
                if (bindTryCount != MAX_BIND_RETRIES) {
                    if (DEBUG) {
                        Log.d(TAG, "Binding service $intent")
                    }
                    bindTryCount++
                    try {
                        val bound = context
                            .bindServiceAsUser(intent, serviceConnection, BIND_FLAGS, user)
                        if (!bound) {
                            context.unbindService(serviceConnection)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Failed to bind to service", e)
                    }
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Unbinding service $intent")
                }
                bindTryCount = 0
                wrapper?.run {
                    context.unbindService(serviceConnection)
                }
                wrapper = null
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (DEBUG) Log.d(TAG, "onServiceConnected $name")
            bindTryCount = 0
            wrapper = ServiceWrapper(IControlsProvider.Stub.asInterface(service))
            try {
                service.linkToDeath(this@ControlsProviderLifecycleManager, 0)
            } catch (_: RemoteException) {}
            handlePendingServiceMethods()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected $name")
            wrapper = null
            bindService(false)
        }

        override fun onNullBinding(name: ComponentName?) {
            if (DEBUG) Log.d(TAG, "onNullBinding $name")
            wrapper = null
            context.unbindService(this)
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

    override fun binderDied() {
        if (wrapper == null) return
        wrapper = null
        if (requiresBound) {
            if (DEBUG) {
                Log.d(TAG, "binderDied")
            }
            // Try rebinding some time later
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
                binderDied()
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
