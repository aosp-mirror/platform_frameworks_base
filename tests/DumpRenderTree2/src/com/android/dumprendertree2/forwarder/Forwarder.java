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

    private ServerSocket mServerSocket;

    private Set<ConnectionHandler> mConnectionHandlers = new HashSet<ConnectionHandler>();

    public Forwarder(int port, String remoteMachineIpAddress) {
        mPort = port;
        mRemoteMachineIpAddress = remoteMachineIpAddress;
    }

    @Override
    public void start() {
        Log.i(LOG_TAG, "start(): Starting fowarder on port: " + mPort);

        try {
            mServerSocket = new ServerSocket(mPort);
        } catch (IOException e) {
            Log.e(LOG_TAG, "mPort=" + mPort, e);
            return;
        }

        super.start();
    }

    @Override
    public void run() {
        while (true) {
            Socket localSocket;
            try {
                localSocket = mServerSocket.accept();
            } catch (IOException e) {
                /** This most likely means that mServerSocket is already closed */
                Log.w(LOG_TAG, "mPort=" + mPort, e);
                break;
            }

            Socket remoteSocket = null;
            final ConnectionHandler connectionHandler;
            try {
                remoteSocket = AdbUtils.createSocket();
                connectionHandler = new ConnectionHandler(
                        mRemoteMachineIpAddress, mPort, localSocket, remoteSocket);
            } catch (IOException exception) {
                try {
                    localSocket.close();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "mPort=" + mPort, e);
                }
                if (remoteSocket != null) {
                    try {
                        remoteSocket.close();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "mPort=" + mPort, e);
                    }
                }
                continue;
            }

            /**
             * We have to close the sockets after the ConnectionHandler finishes, so we
             * don't get "Too may open files" exception. We also remove the ConnectionHandler
             * from the collection to avoid memory issues.
             * */
            ConnectionHandler.OnFinishedCallback callback =
                    new ConnectionHandler.OnFinishedCallback() {
                @Override
                public void onFinished() {
                    synchronized (this) {
                        if (!mConnectionHandlers.remove(connectionHandler)) {
                            assert false : "removeConnectionHandler(): not in the collection";
                        }
                    }
                }
            };
            connectionHandler.registerOnConnectionHandlerFinishedCallback(callback);

            synchronized (this) {
                mConnectionHandlers.add(connectionHandler);
            }
            connectionHandler.start();
        }

        synchronized (this) {
            for (ConnectionHandler connectionHandler : mConnectionHandlers) {
                connectionHandler.stop();
            }
        }
    }

    public void finish() {
        try {
            mServerSocket.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "mPort=" + mPort, e);
        }
    }
}
