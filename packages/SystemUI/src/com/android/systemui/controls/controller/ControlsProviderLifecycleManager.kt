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
    @GuardedBy("subscriptions")
    private val subscriptions = mutableListOf<IControlsSubscription>()
    private var requiresBound = false
    @GuardedBy("queuedMessages")
    private val queuedMessages: MutableSet<Message> = ArraySet()
    private var wrapper: ServiceWrapper? = null
    private var bindTryCount = 0
    private val TAG = javaClass.simpleName
    private var onLoadCanceller: Runnable? = null

    companion object {
        private const val MSG_LOAD = 0
        private const val MSG_SUBSCRIBE = 1
        private const val MSG_ACTION = 2
        private const val MSG_UNBIND = 3
        private const val BIND_RETRY_DELAY = 1000L // ms
        private const val LOAD_TIMEOUT_SECONDS = 30L // seconds
        private const val MAX_BIND_RETRIES = 5
        private const val MAX_CONTROLS_REQUEST = 100000L
        private const val DEBUG = true
        private val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_FOREGROUND_SERVICE or
                Context.BIND_WAIVE_PRIORITY
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
                        context.bindServiceAsUser(intent, serviceConnection, BIND_FLAGS, user)
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
            handlePendingMessages()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected $name")
            wrapper = null
            bindService(false)
        }
    }

    private fun handlePendingMessages() {
        val queue = synchronized(queuedMessages) {
            ArraySet(queuedMessages).also {
                queuedMessages.clear()
            }
        }
        if (Message.Unbind in queue) {
            bindService(false)
            return
        }

        queue.filter { it is Message.Load }.forEach {
            val msg = it as Message.Load
            load(msg.subscriber)
        }

        queue.filter { it is Message.Subscribe }.forEach {
            val msg = it as Message.Subscribe
            subscribe(msg.list, msg.subscriber)
        }
        queue.filter { it is Message.Action }.forEach {
            val msg = it as Message.Action
            action(msg.id, msg.action)
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

    private fun queueMessage(message: Message) {
        synchronized(queuedMessages) {
            queuedMessages.add(message)
        }
    }

    private fun unqueueMessageType(type: Int) {
        synchronized(queuedMessages) {
            queuedMessages.removeIf { it.type == type }
        }
    }

    private fun load(subscriber: IControlsSubscriber.Stub) {
        if (DEBUG) {
            Log.d(TAG, "load $componentName")
        }
        if (!(wrapper?.load(subscriber) ?: false)) {
            queueMessage(Message.Load(subscriber))
            binderDied()
        }
    }

    private inline fun invokeOrQueue(f: () -> Unit, msg: Message) {
        wrapper?.run {
            f()
        } ?: run {
            queueMessage(msg)
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
        unqueueMessageType(MSG_UNBIND)
        onLoadCanceller = executor.executeDelayed({
            // Didn't receive a response in time, log and send back error
            Log.d(TAG, "Timeout waiting onLoad for $componentName")
            subscriber.onError(token, "Timeout waiting onLoad")
            unbindService()
        }, LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        invokeOrQueue({ load(subscriber) }, Message.Load(subscriber))
    }

    /**
     * Request a subscription to the [Publisher] returned by [ControlsProviderService.publisherFor]
     *
     * If the service is not bound, the call will be queued and the service will be bound first.
     *
     * @param controlIds a list of the ids of controls to send status back.
     */
    fun maybeBindAndSubscribe(controlIds: List<String>, subscriber: IControlsSubscriber) {
        invokeOrQueue(
            { subscribe(controlIds, subscriber) },
            Message.Subscribe(controlIds, subscriber)
        )
    }

    private fun subscribe(controlIds: List<String>, subscriber: IControlsSubscriber) {
        if (DEBUG) {
            Log.d(TAG, "subscribe $componentName - $controlIds")
        }

        if (!(wrapper?.subscribe(controlIds, subscriber) ?: false)) {
            queueMessage(Message.Subscribe(controlIds, subscriber))
            binderDied()
        }
    }

    /**
     * Request a call to [ControlsProviderService.performControlAction].
     *
     * If the service is not bound, the call will be queued and the service will be bound first.
     *
     * @param controlId the id of the [Control] the action is performed on
     * @param action the action performed
     */
    fun maybeBindAndSendAction(controlId: String, action: ControlAction) {
        invokeOrQueue({ action(controlId, action) }, Message.Action(controlId, action))
    }

    private fun action(controlId: String, action: ControlAction) {
        if (DEBUG) {
            Log.d(TAG, "onAction $componentName - $controlId")
        }
        if (!(wrapper?.action(controlId, action, actionCallbackService) ?: false)) {
            queueMessage(Message.Action(controlId, action))
            binderDied()
        }
    }

    /**
     * Starts the subscription to the [ControlsProviderService] and requests status of controls.
     *
     * @param subscription the subscription to use to request controls
     * @see maybeBindAndLoad
     */
    fun startSubscription(subscription: IControlsSubscription) {
        if (DEBUG) {
            Log.d(TAG, "startSubscription: $subscription")
        }
        synchronized(subscriptions) {
            subscriptions.add(subscription)
        }
        wrapper?.request(subscription, MAX_CONTROLS_REQUEST)
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
        synchronized(subscriptions) {
            subscriptions.remove(subscription)
        }
        wrapper?.cancel(subscription)
    }

    /**
     * Request bind to the service.
     */
    fun bindService() {
        unqueueMessageType(MSG_UNBIND)
        bindService(true)
    }

    /**
     * Request unbind from the service.
     */
    fun unbindService() {
        onLoadCanceller?.run()
        onLoadCanceller = null

        // be sure to cancel all subscriptions
        val subs = synchronized(subscriptions) {
            ArrayList(subscriptions).also {
                subscriptions.clear()
            }
        }

        subs.forEach {
            wrapper?.cancel(it)
        }

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
     * Messages for the internal queue.
     */
    sealed class Message {
        abstract val type: Int
        class Load(val subscriber: IControlsSubscriber.Stub) : Message() {
            override val type = MSG_LOAD
        }
        object Unbind : Message() {
            override val type = MSG_UNBIND
        }
        class Subscribe(val list: List<String>, val subscriber: IControlsSubscriber) : Message() {
            override val type = MSG_SUBSCRIBE
        }
        class Action(val id: String, val action: ControlAction) : Message() {
            override val type = MSG_ACTION
        }
    }
}
