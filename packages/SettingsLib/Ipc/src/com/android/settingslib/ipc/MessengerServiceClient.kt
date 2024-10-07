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

package com.android.settingslib.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.annotation.OpenForTesting
import androidx.annotation.VisibleForTesting
import androidx.collection.ArrayMap
import com.google.common.base.Ticker
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DisposableHandle

/**
 * Client to communicate with [MessengerService].
 *
 * A dedicated [HandlerThread] is created to handle requests **sequentially**, there is only one
 * ongoing request per package.
 *
 * Must call [close] before [context] is destroyed to avoid context leaking. Note that
 * [MessengerService] is automatically unbound when context lifecycle is stopped. Further request
 * will result in service binding exception.
 *
 * @param context context used for service binding, note that context lifecycle affects the IPC
 *   service lifecycle
 * @param serviceConnectionIdleMs idle time in milliseconds before closing the service connection
 * @param name name of the handler thread
 */
abstract class MessengerServiceClient
@JvmOverloads
constructor(
    protected val context: Context,
    private val serviceConnectionIdleMs: Long = 30000L,
    name: String = TAG,
    private val metricsLogger: MetricsLogger? = null,
) : AutoCloseable {
    /** Per package [ServiceConnection]. */
    @VisibleForTesting internal val messengers = ArrayMap<String, Connection>()
    private val handlerThread = HandlerThread(name)
    @VisibleForTesting internal val handler: Handler

    init {
        handlerThread.start()
        val looper = handlerThread.looper
        handler = Handler(looper)
    }

    /**
     * Factory for service [Intent] creation.
     *
     * A typical implementation is create [Intent] with specific action.
     */
    protected abstract val serviceIntentFactory: () -> Intent

    override fun close() = close(true)

    fun close(join: Boolean) {
        handler.post {
            val exception = ClientClosedException()
            val connections = messengers.values.toTypedArray()
            for (connection in connections) connection.close(exception)
        }
        handlerThread.quitSafely()
        if (join) handlerThread.join()
    }

    /**
     * Invokes given API.
     *
     * @param packageName package name of the target service
     * @param apiDescriptor descriptor of API
     * @param request request parameter
     * @return Deferred object of the response, which could be used for [Deferred.await],
     *   [Deferred.cancel], etc.
     * @exception ApiException
     */
    // TODO: support timeout
    fun <Request, Response> invoke(
        packageName: String,
        apiDescriptor: ApiDescriptor<Request, Response>,
        request: Request,
    ): Deferred<Response> {
        if (apiDescriptor.id < 0) throw ClientInvalidApiException("Invalid id: ${apiDescriptor.id}")
        if (
            packageName == context.packageName &&
                Looper.getMainLooper().thread === Thread.currentThread()
        ) {
            // Deadlock as it might involve service creation, which requires main thread
            throw IllegalStateException("Invoke on main thread causes deadlock")
        }
        val wrapper = RequestWrapper(packageName, apiDescriptor, request, txnId.getAndIncrement())
        metricsLogger?.run {
            wrapper.logIpcEvent(this, IpcEvent.ENQUEUED)
            wrapper.deferred.invokeOnCompletion {
                wrapper.logIpcEvent(this, IpcEvent.COMPLETED, it)
            }
        }
        if (!handler.post { getConnection(packageName).enqueueRequest(wrapper) }) {
            wrapper.completeExceptionally(ClientClosedException())
        }
        return wrapper.deferred
    }

    private fun getConnection(packageName: String) =
        messengers.getOrPut(packageName) {
            Connection(
                handler.looper,
                context,
                packageName,
                serviceConnectionIdleMs,
                serviceIntentFactory,
                messengers,
                metricsLogger,
            )
        }

    @VisibleForTesting
    internal data class RequestWrapper<Request, Response>(
        val packageName: String,
        val apiDescriptor: ApiDescriptor<Request, Response>,
        val request: Request,
        val txnId: Int,
        val deferred: CompletableDeferred<Response> = CompletableDeferred(),
    ) {
        val data: Bundle
            get() = request.let { apiDescriptor.requestCodec.encode(it) }

        fun completeExceptionally(e: Exception) {
            deferred.completeExceptionally(e)
        }

        fun logIpcEvent(
            metricsLogger: MetricsLogger,
            event: @IpcEvent Int,
            cause: Throwable? = null,
        ) {
            try {
                metricsLogger.logIpcEvent(
                    packageName,
                    txnId,
                    apiDescriptor.id,
                    event,
                    cause,
                    ticker.read(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "fail to log ipc event: $event", e)
            }
        }
    }

    // NOTE: All ServiceConnection callbacks are invoked from main thread.
    @OpenForTesting
    @VisibleForTesting
    internal open class Connection(
        looper: Looper,
        private val context: Context,
        private val packageName: String,
        private val serviceConnectionIdleMs: Long,
        private val serviceIntentFactory: () -> Intent,
        private val messengers: ArrayMap<String, Connection>,
        private val metricsLogger: MetricsLogger?,
    ) : Handler(looper), ServiceConnection {
        private val clientMessenger = Messenger(this)
        internal val pendingRequests = ArrayDeque<RequestWrapper<*, *>>()
        internal var serviceMessenger: Messenger? = null
        internal open var connectionState: Int = STATE_INIT

        internal var disposableHandle: DisposableHandle? = null
        private val requestCompletionHandler =
            object : CompletionHandler {
                override fun invoke(cause: Throwable?) {
                    sendEmptyMessage(MSG_CHECK_REQUEST_STATE)
                }
            }

        override fun handleMessage(msg: Message) {
            if (msg.what < 0) {
                handleClientMessage(msg)
                return
            }
            Log.d(TAG, "receive response $msg")
            val request = pendingRequests.removeFirstOrNull()
            if (request == null) {
                Log.w(TAG, "Pending request is empty when got response")
                return
            }
            if (msg.arg1 != request.txnId || request.apiDescriptor.id != msg.what) {
                Log.w(TAG, "Mismatch ${request.apiDescriptor.id}, response=$msg")
                // add request back for retry
                pendingRequests.addFirst(request)
                return
            }
            handleServiceMessage(request, msg)
        }

        internal open fun handleClientMessage(msg: Message) {
            when (msg.what) {
                MSG_ON_SERVICE_CONNECTED -> {
                    if (connectionState == STATE_BINDING) {
                        connectionState = STATE_CONNECTED
                        serviceMessenger = Messenger(msg.obj as IBinder)
                        drainPendingRequests()
                    } else {
                        Log.w(TAG, "Got onServiceConnected when state is $connectionState")
                    }
                }
                MSG_REBIND_SERVICE -> {
                    if (pendingRequests.isEmpty()) {
                        removeMessages(MSG_CLOSE_ON_IDLE)
                        close(null)
                    } else {
                        // died when binding, reset state for rebinding
                        if (msg.obj != null && connectionState == STATE_BINDING) {
                            connectionState = STATE_CONNECTED
                        }
                        rebindService()
                    }
                }
                MSG_CLOSE_ON_IDLE -> {
                    if (pendingRequests.isEmpty()) close(null)
                }
                MSG_CHECK_REQUEST_STATE -> {
                    val request = pendingRequests.firstOrNull()
                    if (request != null && request.deferred.isCompleted) {
                        drainPendingRequests()
                    }
                }
                else -> Log.e(TAG, "Unknown msg: $msg")
            }
        }

        internal open fun handleServiceMessage(request: RequestWrapper<*, *>, response: Message) {
            @Suppress("UNCHECKED_CAST") val deferred = request.deferred as CompletableDeferred<Any?>
            if (deferred.isCompleted) {
                drainPendingRequests()
                return
            }
            metricsLogger?.let { request.logIpcEvent(it, IpcEvent.RESPONSE_RECEIVED) }
            disposableHandle?.dispose()
            if (response.arg2 == ApiServiceException.CODE_OK) {
                try {
                    deferred.complete(request.apiDescriptor.responseCodec.decode(response.data))
                } catch (e: Exception) {
                    request.completeExceptionally(ClientDecodeException(e))
                }
            } else {
                val errorCode = response.arg2
                val exception = ApiServiceException.of(errorCode)
                if (exception != null) {
                    request.completeExceptionally(exception)
                } else {
                    request.completeExceptionally(ClientUnknownResponseCodeException(errorCode))
                }
            }
            drainPendingRequests()
        }

        fun enqueueRequest(request: RequestWrapper<*, *>) {
            if (connectionState == STATE_CLOSED) {
                request.completeExceptionally(ClientClosedException())
                return
            }
            pendingRequests.add(request)
            if (pendingRequests.size == 1) {
                removeMessages(MSG_CLOSE_ON_IDLE)
                drainPendingRequests()
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "onServiceConnected $name")
            metricsLogger?.logServiceEvent(ServiceEvent.ON_SERVICE_CONNECTED)
            sendMessage(obtainMessage(MSG_ON_SERVICE_CONNECTED, service))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Service process crashed or killed, the connection remains alive, will receive
            // onServiceConnected when the Service is next running
            Log.i(TAG, "onServiceDisconnected $name")
            metricsLogger?.logServiceEvent(ServiceEvent.ON_SERVICE_DISCONNECTED)
            sendMessage(obtainMessage(MSG_REBIND_SERVICE))
        }

        override fun onBindingDied(name: ComponentName) {
            Log.i(TAG, "onBindingDied $name")
            metricsLogger?.logServiceEvent(ServiceEvent.ON_BINDING_DIED)
            // When service is connected and peer happens to be updated, both onServiceDisconnected
            // and onBindingDied callbacks are invoked.
            if (!hasMessages(MSG_REBIND_SERVICE)) {
                sendMessage(obtainMessage(MSG_REBIND_SERVICE, true))
            }
        }

        internal open fun drainPendingRequests() {
            disposableHandle = null
            if (pendingRequests.isEmpty()) {
                closeOnIdle(serviceConnectionIdleMs)
                return
            }
            val serviceMessenger = this.serviceMessenger
            if (serviceMessenger == null) {
                bindService()
                return
            }
            do {
                val request = pendingRequests.first()
                if (request.deferred.isCompleted) {
                    pendingRequests.removeFirst()
                } else {
                    sendServiceMessage(serviceMessenger, request)
                    return
                }
            } while (pendingRequests.isNotEmpty())
            closeOnIdle(serviceConnectionIdleMs)
        }

        internal open fun closeOnIdle(idleMs: Long) {
            if (idleMs <= 0 || !sendEmptyMessageDelayed(MSG_CLOSE_ON_IDLE, idleMs)) {
                close(null)
            }
        }

        internal open fun sendServiceMessage(
            serviceMessenger: Messenger,
            request: RequestWrapper<*, *>,
        ) {
            fun completeExceptionally(exception: Exception) {
                pendingRequests.removeFirst()
                request.completeExceptionally(exception)
                drainPendingRequests()
            }
            val message =
                obtainMessage(request.apiDescriptor.id, request.txnId, 0).apply {
                    replyTo = clientMessenger
                }
            try {
                message.data = request.data
            } catch (e: Exception) {
                completeExceptionally(ClientEncodeException(e))
                return
            }
            Log.d(TAG, "send $message")
            try {
                sendServiceMessage(serviceMessenger, message)
                metricsLogger?.let { request.logIpcEvent(it, IpcEvent.REQUEST_SENT) }
                disposableHandle = request.deferred.invokeOnCompletion(requestCompletionHandler)
            } catch (e: DeadObjectException) {
                Log.w(TAG, "Got DeadObjectException")
                rebindService()
            } catch (e: Exception) {
                completeExceptionally(ClientSendException("Fail to send $message", e))
            }
        }

        @Throws(Exception::class)
        internal open fun sendServiceMessage(serviceMessenger: Messenger, message: Message) =
            serviceMessenger.send(message)

        internal fun bindService() {
            if (connectionState == STATE_BINDING || connectionState == STATE_CLOSED) {
                Log.w(TAG, "Ignore bindService $packageName, state: $connectionState")
                return
            }
            connectionState = STATE_BINDING
            Log.i(TAG, "bindService $packageName")
            val intent = serviceIntentFactory.invoke()
            intent.setPackage(packageName)
            metricsLogger?.logServiceEvent(ServiceEvent.BIND_SERVICE)
            bindService(intent)?.let { close(it) }
        }

        private fun bindService(intent: Intent): Exception? =
            try {
                if (context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                    null
                } else {
                    ClientBindServiceException(null)
                }
            } catch (e: Exception) {
                ClientBindServiceException(e)
            }

        internal open fun rebindService() {
            Log.i(TAG, "rebindService $packageName")
            metricsLogger?.logServiceEvent(ServiceEvent.REBIND_SERVICE)
            unbindService()
            bindService()
        }

        internal fun close(exception: Exception?) {
            Log.i(TAG, "close connection $packageName", exception)
            connectionState = STATE_CLOSED
            messengers.remove(packageName, this)
            unbindService()
            if (pendingRequests.isNotEmpty()) {
                val reason = exception ?: ClientClosedException()
                do {
                    pendingRequests.removeFirst().deferred.completeExceptionally(reason)
                } while (pendingRequests.isNotEmpty())
            }
        }

        private fun unbindService() {
            disposableHandle?.dispose()
            disposableHandle = null
            serviceMessenger = null
            metricsLogger?.logServiceEvent(ServiceEvent.UNBIND_SERVICE)
            try {
                // "IllegalArgumentException: Service not registered" may be raised when peer app is
                // just updated (e.g. upgraded)
                context.unbindService(this)
            } catch (e: Exception) {
                Log.w(TAG, "exception raised when unbindService", e)
            }
        }

        private fun MetricsLogger.logServiceEvent(event: @ServiceEvent Int) {
            try {
                logServiceEvent(packageName, event, ticker.read())
            } catch (e: Exception) {
                Log.e(TAG, "fail to log service event: $event", e)
            }
        }
    }

    companion object {
        private const val TAG = "MessengerServiceClient"
        private val ticker: Ticker by lazy { Ticker.systemTicker() }

        @VisibleForTesting internal const val STATE_INIT = 0
        @VisibleForTesting internal const val STATE_BINDING = 1
        @VisibleForTesting internal const val STATE_CONNECTED = 2
        @VisibleForTesting internal const val STATE_CLOSED = 3

        @VisibleForTesting internal const val MSG_ON_SERVICE_CONNECTED = -1
        @VisibleForTesting internal const val MSG_REBIND_SERVICE = -2
        @VisibleForTesting internal const val MSG_CLOSE_ON_IDLE = -3
        @VisibleForTesting internal const val MSG_CHECK_REQUEST_STATE = -4

        @VisibleForTesting internal val txnId = AtomicInteger()
    }
}
