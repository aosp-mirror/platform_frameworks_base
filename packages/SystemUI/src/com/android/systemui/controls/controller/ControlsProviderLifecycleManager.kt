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
import android.service.controls.Control
import android.service.controls.ControlsProviderService.CALLBACK_BUNDLE
import android.service.controls.ControlsProviderService.CALLBACK_TOKEN
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsLoadCallback
import android.service.controls.IControlsProvider
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
import android.service.controls.actions.ControlAction
import android.util.ArraySet
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.TimeUnit

typealias LoadCallback = (List<Control>) -> Unit
class ControlsProviderLifecycleManager(
    private val context: Context,
    private val executor: DelayableExecutor,
    private val loadCallbackService: IControlsLoadCallback.Stub,
    private val actionCallbackService: IControlsActionCallback.Stub,
    private val subscriberService: IControlsSubscriber.Stub,
    val componentName: ComponentName
) : IBinder.DeathRecipient {

    var lastLoadCallback: LoadCallback? = null
        private set
    val token: IBinder = Binder()
    @GuardedBy("subscriptions")
    private val subscriptions = mutableListOf<IControlsSubscription>()
    private var unbindImmediate = false
    private var requiresBound = false
    private var isBound = false
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
        private const val LOAD_TIMEOUT = 5000L // ms
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
        requiresBound = bind
        if (bind) {
            if (bindTryCount == MAX_BIND_RETRIES) {
                return
            }
            if (DEBUG) {
                Log.d(TAG, "Binding service $intent")
            }
            bindTryCount++
            try {
                isBound = context.bindService(intent, serviceConnection, BIND_FLAGS)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to bind to service", e)
                isBound = false
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "Unbinding service $intent")
            }
            bindTryCount = 0
            wrapper = null
            if (isBound) {
                context.unbindService(serviceConnection)
                isBound = false
            }
        }
    }

    fun bindPermanently() {
        unbindImmediate = false
        unqueueMessage(Message.Unbind)
        bindService(true)
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
            isBound = false
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
        if (Message.Load in queue) {
            load()
        }
        queue.filter { it is Message.Subscribe }.flatMap { (it as Message.Subscribe).list }.run {
            subscribe(this)
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

    private fun unqueueMessage(message: Message) {
        synchronized(queuedMessages) {
            queuedMessages.removeIf { it.type == message.type }
        }
    }

    private fun load() {
        if (DEBUG) {
            Log.d(TAG, "load $componentName")
        }
        if (!(wrapper?.load(loadCallbackService) ?: false)) {
            queueMessage(Message.Load)
            binderDied()
        }
    }

    fun maybeBindAndLoad(callback: LoadCallback) {
        unqueueMessage(Message.Unbind)
        lastLoadCallback = callback
        onLoadCanceller = executor.executeDelayed({
            // Didn't receive a response in time, log and send back empty list
            Log.d(TAG, "Timeout waiting onLoad for $componentName")
            loadCallbackService.accept(token, emptyList())
        }, LOAD_TIMEOUT, TimeUnit.MILLISECONDS)
        if (isBound) {
            load()
        } else {
            queueMessage(Message.Load)
            unbindImmediate = true
            bindService(true)
        }
    }

    fun maybeBindAndSubscribe(controlIds: List<String>) {
        if (isBound) {
            subscribe(controlIds)
        } else {
            queueMessage(Message.Subscribe(controlIds))
            bindService(true)
        }
    }

    private fun subscribe(controlIds: List<String>) {
        if (DEBUG) {
            Log.d(TAG, "subscribe $componentName - $controlIds")
        }
        if (!(wrapper?.subscribe(controlIds, subscriberService) ?: false)) {
            queueMessage(Message.Subscribe(controlIds))
            binderDied()
        }
    }

    fun maybeBindAndSendAction(controlId: String, action: ControlAction) {
        if (isBound) {
            action(controlId, action)
        } else {
            queueMessage(Message.Action(controlId, action))
            bindService(true)
        }
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

    fun startSubscription(subscription: IControlsSubscription) {
        synchronized(subscriptions) {
            subscriptions.add(subscription)
        }
        wrapper?.request(subscription, MAX_CONTROLS_REQUEST)
    }

    fun unsubscribe() {
        if (DEBUG) {
            Log.d(TAG, "unsubscribe $componentName")
        }
        unqueueMessage(Message.Subscribe(emptyList())) // Removes all subscribe messages

        val subs = synchronized(subscriptions) {
            ArrayList(subscriptions).also {
                subscriptions.clear()
            }
        }

        subs.forEach {
            wrapper?.cancel(it)
        }
    }

    fun maybeUnbindAndRemoveCallback() {
        lastLoadCallback = null
        onLoadCanceller?.run()
        onLoadCanceller = null
        if (unbindImmediate) {
            bindService(false)
        }
    }

    fun unbindService() {
        unbindImmediate = true
        maybeUnbindAndRemoveCallback()
    }

    sealed class Message {
        abstract val type: Int
        object Load : Message() {
            override val type = MSG_LOAD
        }
        object Unbind : Message() {
            override val type = MSG_UNBIND
        }
        class Subscribe(val list: List<String>) : Message() {
            override val type = MSG_SUBSCRIBE
        }
        class Action(val id: String, val action: ControlAction) : Message() {
            override val type = MSG_ACTION
        }
    }
}
