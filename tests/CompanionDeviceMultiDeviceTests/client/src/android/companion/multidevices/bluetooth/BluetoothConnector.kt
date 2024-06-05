/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.multidevices.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.companion.CompanionDeviceManager
import android.util.Log
import java.io.IOException
import java.util.UUID

class BluetoothConnector(
    private val adapter: BluetoothAdapter,
    private val cdm: CompanionDeviceManager
) {
    companion object {
        private const val TAG = "CDM_BluetoothServer"

        private val SERVICE_NAME = "CDM_BluetoothChannel"
        private val SERVICE_UUID = UUID.fromString("435fe1d9-56c5-455d-a516-d5e6b22c52f9")

        // Registry of bluetooth server threads
        private val serverThreads = mutableMapOf<Int, BluetoothServerThread>()

        // Registry of remote bluetooth devices
        private val remoteDevices = mutableMapOf<Int, BluetoothDevice>()

        // Set of connected client sockets
        private val clientSockets = mutableMapOf<Int, BluetoothSocket>()
    }

    fun attachClientSocket(associationId: Int) {
        try {
            val device = remoteDevices[associationId]!!
            val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            if (clientSockets.containsKey(associationId)) {
                detachClientSocket(associationId)
                clientSockets[associationId] = socket
            } else {
                clientSockets += associationId to socket
            }

            socket.connect()
            Log.d(TAG, "Attaching client socket $socket.")
            cdm.attachSystemDataTransport(
                    associationId,
                    socket.inputStream,
                    socket.outputStream
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to attach client socket.", e)
            throw RuntimeException(e)
        }
    }

    fun attachServerSocket(associationId: Int) {
        val serverThread: BluetoothServerThread
        if (serverThreads.containsKey(associationId)) {
            serverThread = serverThreads[associationId]!!
        } else {
            serverThread = BluetoothServerThread(associationId)
            serverThreads += associationId to serverThread
        }

        // Start thread
        if (!serverThread.isOpen) {
            serverThread.start()
        }
    }

    fun closeAllSockets() {
        val iter = clientSockets.keys.iterator()
        while (iter.hasNext()) {
            detachClientSocket(iter.next())
        }
        for (thread in serverThreads.values) {
            thread.shutdown()
        }
        serverThreads.clear()
    }

    fun registerDevice(associationId: Int, remoteDevice: BluetoothDevice) {
        remoteDevices[associationId] = remoteDevice
    }

    private fun detachClientSocket(associationId: Int) {
        try {
            Log.d(TAG, "Detaching client socket.")
            cdm.detachSystemDataTransport(associationId)
            clientSockets[associationId]?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to detach client socket.", e)
            throw RuntimeException(e)
        }
    }

    inner class BluetoothServerThread(
        private val associationId: Int
    ) : Thread() {
        private lateinit var mServerSocket: BluetoothServerSocket

        var isOpen = false

        override fun run() {
            try {
                Log.d(TAG, "Listening for remote connections...")
                mServerSocket = adapter.listenUsingRfcommWithServiceRecord(
                        SERVICE_NAME,
                        SERVICE_UUID
                )
                isOpen = true
                do {
                    val socket = mServerSocket.accept()
                    Log.d(TAG, "Attaching server socket $socket.")
                    cdm.attachSystemDataTransport(
                            associationId,
                            socket.inputStream,
                            socket.outputStream
                    )
                } while (isOpen)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }

        fun shutdown() {
            if (!isOpen || !this::mServerSocket.isInitialized) return

            try {
                Log.d(TAG, "Closing server socket.")
                cdm.detachSystemDataTransport(associationId)
                mServerSocket.close()
                isOpen = false
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }
}
