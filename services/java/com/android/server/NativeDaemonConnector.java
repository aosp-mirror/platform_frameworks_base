/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.net.LocalSocketAddress;
import android.net.LocalSocket;
import android.os.Environment;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.lang.IllegalStateException;

import java.util.List;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Generic connector class for interfacing with a native
 * daemon which uses the libsysutils FrameworkListener
 * protocol.
 */
final class NativeDaemonConnector implements Runnable {

    private BlockingQueue<String> mResponseQueue;
    private OutputStream          mOutputStream;
    private String                TAG = "NativeDaemonConnector";
    private String                mSocket;
    private INativeDaemonConnectorCallbacks mCallbacks;

    class ResponseCode {
        public static final int ActionInitiated                = 100;

        public static final int CommandOkay                    = 200;

        // The range of 400 -> 599 is reserved for cmd failures
        public static final int OperationFailed                = 400;
        public static final int CommandSyntaxError             = 500;
        public static final int CommandParameterError          = 501;

        public static final int UnsolicitedInformational       = 600;

        //
        public static final int FailedRangeStart               = 400;
        public static final int FailedRangeEnd                 = 599;
    }

    NativeDaemonConnector(INativeDaemonConnectorCallbacks callbacks,
                          String socket, int responseQueueSize, String logTag) {
        mCallbacks = callbacks;
        if (logTag != null)
            TAG = logTag;
        mSocket = socket;
        mResponseQueue = new LinkedBlockingQueue<String>(responseQueueSize);
    }

    public void run() {

        while (true) {
            try {
                listenToSocket();
            } catch (Exception e) {
                Log.e(TAG, "Error in NativeDaemonConnector", e);
                SystemClock.sleep(1000);
            }
        }
    }

    private void listenToSocket() {
       LocalSocket socket = null;

        try {
            socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(mSocket,
                    LocalSocketAddress.Namespace.RESERVED);

            socket.connect(address);
            mCallbacks.onDaemonConnected();

            InputStream inputStream = socket.getInputStream();
            mOutputStream = socket.getOutputStream();

            byte[] buffer = new byte[4096];

            while (true) {
                int count = inputStream.read(buffer);
                if (count < 0) break;

                int start = 0;
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        String event = new String(buffer, start, i - start);
//                        Log.d(TAG, "Got packet {" + event + "}");

                        String[] tokens = event.split(" ");
                        try {
                            int code = Integer.parseInt(tokens[0]);

                            if (code >= ResponseCode.UnsolicitedInformational) {
                                try {
                                    if (!mCallbacks.onEvent(code, event, tokens)) {
                                        Log.w(TAG, String.format(
                                                "Unhandled event (%s)", event));
                                    }
                                } catch (Exception ex) {
                                    Log.e(TAG, String.format(
                                            "Error handling '%s'", event), ex);
                                }
                            } else {
                                try {
                                    mResponseQueue.put(event);
                                } catch (InterruptedException ex) {
                                    Log.e(TAG, "Failed to put response onto queue", ex);
                                }
                            }
                        } catch (NumberFormatException nfe) {
                            Log.w(TAG, String.format("Bad msg (%s)", event));
                        }
                        start = i + 1;
                    }
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Communications error", ex);
        }

        synchronized (this) {
            if (mOutputStream != null) {
                try {
                    mOutputStream.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed closing output stream", e);
                }

                mOutputStream = null;
            }
        }

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ex) {
            Log.w(TAG, "Failed closing socket", ex);
        }

        Log.e(TAG, "Failed to connect to native daemon",
                new IllegalStateException());
        SystemClock.sleep(5000);
    }

    private void sendCommand(String command) {
        sendCommand(command, null);
    }

    /**
     * Sends a command to the daemon with a single argument
     *
     * @param command  The command to send to the daemon
     * @param argument The argument to send with the command (or null)
     */
    private void sendCommand(String command, String argument) {
        synchronized (this) {
             Log.d(TAG, "sendCommand {" + command + "} {" + argument + "}");
            if (mOutputStream == null) {
                Log.e(TAG, "No connection to daemon", new IllegalStateException());
            } else {
                StringBuilder builder = new StringBuilder(command);
                if (argument != null) {
                    builder.append(argument);
                }
                builder.append('\0');

                try {
                    mOutputStream.write(builder.toString().getBytes());
                } catch (IOException ex) {
                    Log.e(TAG, "IOException in sendCommand", ex);
                }
            }
        }
    }

    public synchronized ArrayList<String> doCommand(String cmd) throws IllegalStateException {
        sendCommand(cmd);

        ArrayList<String> response = new ArrayList<String>();
        boolean complete = false;
        int code = -1;

        while (!complete) {
            try {
                String line = mResponseQueue.take();
//                Log.d(TAG, "Removed off queue -> " + line);
                String[] tokens = line.split(" ");
                try {
                    code = Integer.parseInt(tokens[0]);
                } catch (NumberFormatException nfe) {
                    throw new IllegalStateException(
                            String.format("Invalid response from daemon (%s)", line));
                }

                if ((code >= 200) && (code < 600))
                    complete = true;
                response.add(line);
            } catch (InterruptedException ex) {
                Log.e(TAG, "InterruptedException");
            }
        }

        if (code >= ResponseCode.FailedRangeStart &&
                code <= ResponseCode.FailedRangeEnd) {
            throw new IllegalStateException(String.format(
                                               "Command %s failed with code %d",
                                                cmd, code));
        }
        return response;
    }
}
