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

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [Messenger] based bound service for IPC.
 *
 * A dedicated [HandlerThread] is created to handle all requests.
 *
 * @param apiHandlers API handlers associated with the service
 * @param permissionChecker Checker for permission
 * @param name name of the handler thread
 */
open class MessengerService(
    private val apiHandlers: List<ApiHandler<*, *>>,
    private val permissionChecker: PermissionChecker,
    name: String = TAG,
) : Service() {
    @VisibleForTesting internal val handlerThread = HandlerThread(name)
    @VisibleForTesting internal lateinit var handler: IncomingHandler
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        handler =
            IncomingHandler(
                handlerThread.looper,
                applicationContext as Application,
                apiHandlers.toSortedArray(),
                permissionChecker,
            )
        messenger = Messenger(handler)
        Log.i(TAG, "onCreate HandlerThread ${handlerThread.threadId}")
    }

    override fun onBind(intent: Intent): IBinder? {
        // this method is executed only once even there is more than 1 client
        Log.i(TAG, "onBind $intent")
        return messenger.binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // invoked when ALL clients are unbound
        Log.i(TAG, "onUnbind $intent")
        handler.coroutineScope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy HandlerThread ${handlerThread.threadId}")
        handlerThread.quitSafely()
        super.onDestroy()
    }

    @VisibleForTesting
    internal class IncomingHandler(
        looper: Looper,
        private val application: Application,
        private val apiHandlers: Array<ApiHandler<*, *>>,
        private val permissionChecker: PermissionChecker,
    ) : Handler(looper) {
        @VisibleForTesting internal val myUid = Process.myUid()
        val coroutineScope = CoroutineScope(asCoroutineDispatcher().immediate + SupervisorJob())

        override fun handleMessage(msg: Message) {
            coroutineScope.launch { handle(msg) }
        }

        @VisibleForTesting
        internal suspend fun handle(msg: Message) {
            Log.d(TAG, "receive request $msg")
            val replyTo = msg.replyTo
            if (replyTo == null) {
                Log.e(TAG, "Ignore msg without replyTo: $msg")
                return
            }
            val apiId = msg.what
            val txnId = msg.arg1
            val callingUid = msg.sendingUid
            val data = msg.data
            // WARNING: never access "msg" beyond this point as it may be recycled by Looper
            val response = Message.obtain(null, apiId, txnId, ApiServiceException.CODE_OK)
            try {
                if (permissionChecker.check(application, myUid, callingUid)) {
                    @Suppress("UNCHECKED_CAST")
                    val apiHandler = findApiHandler(apiId) as? ApiHandler<Any, Any>
                    if (apiHandler != null) {
                        val request = apiHandler.requestCodec.decode(data)
                        if (apiHandler.hasPermission(application, myUid, callingUid, request)) {
                            val result = apiHandler.invoke(application, myUid, callingUid, request)
                            response.data = apiHandler.responseCodec.encode(result)
                        } else {
                            response.arg2 = ApiServiceException.CODE_PERMISSION_DENIED
                        }
                    } else {
                        response.arg2 = ApiServiceException.CODE_UNKNOWN_API
                        Log.e(TAG, "Unknown request [txnId=$txnId,apiId=$apiId]")
                    }
                } else {
                    response.arg2 = ApiServiceException.CODE_PERMISSION_DENIED
                }
            } catch (e: Exception) {
                response.arg2 = ApiServiceException.CODE_INTERNAL_ERROR
                Log.e(TAG, "Internal error when handle [txnId=$txnId,apiId=$apiId]", e)
            }
            try {
                replyTo.send(response)
            } catch (e: Exception) {
                Log.w(TAG, "Fail to send response for [txnId=$txnId,apiId=$apiId]", e)
                // nothing to do
            }
        }

        @VisibleForTesting
        internal fun findApiHandler(id: Int): ApiHandler<*, *>? {
            var low = 0
            var high = apiHandlers.size
            while (low < high) {
                val mid = (low + high).ushr(1) // safe from overflows
                val api = apiHandlers[mid]
                when {
                    api.id < id -> low = mid + 1
                    api.id > id -> high = mid
                    else -> return api
                }
            }
            return null
        }
    }

    companion object {
        @VisibleForTesting internal const val TAG = "MessengerService"
    }
}

@VisibleForTesting
internal fun List<ApiHandler<*, *>>.toSortedArray() =
    toTypedArray().also { array ->
        if (array.isEmpty()) return@also
        array.sortBy { it.id }
        if (array[0].id < 0) throw IllegalArgumentException("negative id: ${array[0]}")
        for (index in 1 until array.size) {
            if (array[index - 1].id == array[index].id) {
                throw IllegalArgumentException("conflict id: ${array[index - 1]} ${array[index]}")
            }
        }
    }
