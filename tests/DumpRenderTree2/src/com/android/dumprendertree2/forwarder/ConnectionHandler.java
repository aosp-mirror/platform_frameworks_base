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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Worker class for {@link Forwarder}. A ConnectionHandler will be created once the Forwarder
 * accepts an incoming connection, and it will then forward the incoming/outgoing streams to a
 * connection already proxied by adb networking (see also {@link AdbUtils}).
 */
public class ConnectionHandler {

    private static final String LOG_TAG = "ConnectionHandler";

    private class SocketPipeThread extends Thread {

        private Socket mInSocket, mOutSocket;

        public SocketPipeThread(Socket inSocket, Socket outSocket) {
            mInSocket = inSocket;
            mOutSocket = outSocket;
        }

        @Override
        public void run() {
            InputStream is;
            OutputStream os;
            try {
                synchronized (this) {
                    is = mInSocket.getInputStream();
                    os = mOutSocket.getOutputStream();
                }
            } catch (IOException e) {
                Log.w(LOG_TAG, this.toString(), e);
                return;
            }

            byte[] buffer = new byte[4096];
            int length;
            while (true) {
                try {
                    synchronized (this) {
                        if ((length = is.read(buffer)) <= 0) {
                            break;
                        }
                        os.write(buffer, 0, length);
                    }
                } catch (IOException e) {
                    /** This exception means one of the streams is closed */
                    Log.v(LOG_TAG, this.toString(), e);
                    break;
                }
            }
        }

        @Override
        public String toString() {
            return "SocketPipeThread:\n" + mInSocket + "\n=>\n" + mOutSocket;
        }
    }

    private Socket mFromSocket, mToSocket;
    private SocketPipeThread mFromToPipe, mToFromPipe;

    public ConnectionHandler(Socket fromSocket, Socket toSocket) {
        mFromSocket = fromSocket;
        mToSocket = toSocket;
        mFromToPipe = new SocketPipeThread(mFromSocket, mToSocket);
        mToFromPipe = new SocketPipeThread(mToSocket, mFromSocket);
    }

    public void start() {
        mFromToPipe.start();
        mToFromPipe.start();
    }

    public void stop() {
        shutdown(mFromSocket);
        shutdown(mToSocket);
    }

    private void shutdown(Socket socket) {
        try {
            synchronized (mFromToPipe) {
                synchronized (mToFromPipe) {
                    /** This will stop the while loop in the run method */
                    socket.shutdownInput();
                    socket.shutdownOutput();
                    socket.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "mFromToPipe=" + mFromToPipe + " mToFromPipe=" + mToFromPipe, e);
        }
    }
}