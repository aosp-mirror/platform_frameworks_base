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

import androidx.annotation.IntDef

/** Interface for metrics logging. */
interface MetricsLogger {

    /**
     * Logs service connection event.
     *
     * @param packageName package name of the service connection
     * @param event service event type
     * @param elapsedRealtimeNanos nanoseconds since boot, including time spent in sleep
     * @see [android.os.SystemClock.elapsedRealtimeNanos]
     */
    fun logServiceEvent(packageName: String, event: @ServiceEvent Int, elapsedRealtimeNanos: Long)

    /**
     * Logs ipc call event.
     *
     * @param packageName package name of the service connection
     * @param txnId unique transaction id of the ipc call
     * @param ipc ipc API id
     * @param event ipc event type
     * @param cause cause when ipc request completed, provided only when [event] is
     *   [IpcEvent.COMPLETED]
     * @param elapsedRealtimeNanos nanoseconds since boot, including time spent in sleep
     * @see [android.os.SystemClock.elapsedRealtimeNanos]
     */
    fun logIpcEvent(
        packageName: String,
        txnId: Int,
        ipc: Int,
        event: Int,
        cause: Throwable?,
        elapsedRealtimeNanos: Long,
    )
}

/** Service connection events (for client). */
@Target(AnnotationTarget.TYPE)
@IntDef(
    ServiceEvent.BIND_SERVICE,
    ServiceEvent.UNBIND_SERVICE,
    ServiceEvent.REBIND_SERVICE,
    ServiceEvent.ON_SERVICE_CONNECTED,
    ServiceEvent.ON_SERVICE_DISCONNECTED,
    ServiceEvent.ON_BINDING_DIED,
)
@Retention(AnnotationRetention.SOURCE)
annotation class ServiceEvent {
    companion object {
        /** Event of [android.content.Context.bindService] call. */
        const val BIND_SERVICE = 0

        /** Event of [android.content.Context.unbindService] call. */
        const val UNBIND_SERVICE = 1

        /** Event to rebind service. */
        const val REBIND_SERVICE = 2

        /** Event of [android.content.ServiceConnection.onServiceConnected] callback. */
        const val ON_SERVICE_CONNECTED = 3

        /** Event of [android.content.ServiceConnection.onServiceDisconnected] callback. */
        const val ON_SERVICE_DISCONNECTED = 4

        /** Event of [android.content.ServiceConnection.onBindingDied] callback. */
        const val ON_BINDING_DIED = 5
    }
}

/** Events of a ipc call. */
@Target(AnnotationTarget.TYPE)
@IntDef(IpcEvent.ENQUEUED, IpcEvent.REQUEST_SENT, IpcEvent.RESPONSE_RECEIVED, IpcEvent.COMPLETED)
@Retention(AnnotationRetention.SOURCE)
annotation class IpcEvent {
    companion object {
        /** Event of IPC request enqueued. */
        const val ENQUEUED = 0

        /** Event of IPC request has been sent to service. */
        const val REQUEST_SENT = 1

        /** Event of IPC response received from service. */
        const val RESPONSE_RECEIVED = 2

        /** Event of IPC request completed. */
        const val COMPLETED = 3
    }
}
