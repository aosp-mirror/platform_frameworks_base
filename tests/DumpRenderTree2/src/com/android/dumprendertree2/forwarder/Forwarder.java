/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2.forwarder;

import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/**
 * A port forwarding server. Listens on localhost on specified port and forwards the tcp
 * communications to external socket via adb networking proxy.
 */
public class Forwarder extends Thread {
    private static final String LOG_TAG = "Forwarder";

    private int mPort;
    private String mRemoteMachineIpAddress;

    private Boolean mIsRunning = false;
    private ServerSocket mServerSocket;

    private Set<ConnectionHandler> mConnectionHandlers = new HashSet<ConnectionHandler>();

    public Forwarder(int port, String remoteMachineIpAddress) {
        mPort = port;
        mRemoteMachineIpAddress = remoteMachineIpAddress;
    }

    @Override
    public void start() {
        Log.i(LOG_TAG, "start(): Starting fowarder on port: " + mPort);
        synchronized (this) {
            if (mIsRunning) {
                Log.w(LOG_TAG, "start(): Forwarder on port: " + mPort + " already running! NOOP.");
                return;
            }
        }

        try {
            mServerSocket = new ServerSocket(mPort);
        } catch (IOException e) {
            Log.e(LOG_TAG, "mPort=" + mPort, e);
            return;
        }

        mIsRunning = true;
        super.start();
    }

    @Override
    public void run() {
        while (true) {
            synchronized (this) {
                if (!mIsRunning) {
                    return;
                }

                /** These sockets will be closed when Forwarder.stop() is called */
                Socket localSocket;
                Socket remoteSocket;
                try {
                    localSocket = mServerSocket.accept();
                    remoteSocket = AdbUtils.getSocketToRemoteMachine(mRemoteMachineIpAddress,
                            mPort);
                } catch (IOException e) {
                    /** This most likely means that mServerSocket is already closed */
                    Log.w(LOG_TAG + "mPort=" + mPort, e);
                    return;
                }

                if (remoteSocket == null) {
                    try {
                        localSocket.close();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "mPort=" + mPort, e);
                    }

                    Log.e(LOG_TAG, "run(): mPort= " + mPort + " Failed to start forwarding from " +
                            localSocket);
                    continue;
                }

                ConnectionHandler forwarder = new ConnectionHandler(localSocket, remoteSocket);
                mConnectionHandlers.add(forwarder);
                forwarder.start();

            }
        }
    }

    public void finish() {
        synchronized (this) {
            if (!mIsRunning) {
                return;
            }
        }

        try {
            mServerSocket.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "mPort=" + mPort, e);
        }

        synchronized (this) {
            mIsRunning = false;
        }

        for (ConnectionHandler connectionHandler : mConnectionHandlers) {
            connectionHandler.stop();
        }
        mConnectionHandlers.clear();
    }
}