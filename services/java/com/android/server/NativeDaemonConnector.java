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
import android.util.Slog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

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
    private static final boolean LOCAL_LOGD = false;

    private BlockingQueue<String> mResponseQueue;
    private OutputStream          mOutputStream;
    private String                TAG = "NativeDaemonConnector";
    private String                mSocket;
    private INativeDaemonConnectorCallbacks mCallbacks;

    private final int BUFFER_SIZE = 4096;

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
                Slog.e(TAG, "Error in NativeDaemonConnector", e);
                SystemClock.sleep(5000);
            }
        }
    }

    private void listenToSocket() throws IOException {
        LocalSocket socket = null;

        try {
            socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(mSocket,
                    LocalSocketAddress.Namespace.RESERVED);

            socket.connect(address);
            mCallbacks.onDaemonConnected();

            InputStream inputStream = socket.getInputStream();
            mOutputStream = socket.getOutputStream();

            byte[] buffer = new byte[BUFFER_SIZE];
            int start = 0;

            while (true) {
                int count = inputStream.read(buffer, start, BUFFER_SIZE - start);
                if (count < 0) break;

                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        String event = new String(buffer, start, i - start);
                        if (LOCAL_LOGD) Slog.d(TAG, String.format("RCV <- {%s}", event));

                        String[] tokens = event.split(" ");
                        try {
                            int code = Integer.parseInt(tokens[0]);

                            if (code >= ResponseCode.UnsolicitedInformational) {
                                try {
                                    if (!mCallbacks.onEvent(code, event, tokens)) {
                                        Slog.w(TAG, String.format(
                                                "Unhandled event (%s)", event));
                                    }
                                } catch (Exception ex) {
                                    Slog.e(TAG, String.format(
                                            "Error handling '%s'", event), ex);
                                }
                            } else {
                                try {
                                    mResponseQueue.put(event);
                                } catch (InterruptedException ex) {
                                    Slog.e(TAG, "Failed to put response onto queue", ex);
                                }
                            }
                        } catch (NumberFormatException nfe) {
                            Slog.w(TAG, String.format("Bad msg (%s)", event));
                        }
                        start = i + 1;
                    }
                }
                if (start != count) {
                    final int remaining = BUFFER_SIZE - start;
                    System.arraycopy(buffer, start, buffer, 0, remaining);
                    start = remaining;
                } else {
                    start = 0;
                }
            }
        } catch (IOException ex) {
            Slog.e(TAG, "Communications error", ex);
            throw ex;
        } finally {
            synchronized (this) {
                if (mOutputStream != null) {
                    try {
                        mOutputStream.close();
                    } catch (IOException e) {
                        Slog.w(TAG, "Failed closing output stream", e);
                    }
                    mOutputStream = null;
                }
            }

            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ex) {
                Slog.w(TAG, "Failed closing socket", ex);
            }
        }
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
            if (LOCAL_LOGD) Slog.d(TAG, String.format("SND -> {%s} {%s}", command, argument));
            if (mOutputStream == null) {
                Slog.e(TAG, "No connection to daemon", new IllegalStateException());
            } else {
                StringBuilder builder = new StringBuilder(command);
                if (argument != null) {
                    builder.append(argument);
                }
                builder.append('\0');

                try {
                    mOutputStream.write(builder.toString().getBytes());
                } catch (IOException ex) {
                    Slog.e(TAG, "IOException in sendCommand", ex);
                }
            }
        }
    }

    /**
     * Issue a command to the native daemon and return the responses
     */
    public synchronized ArrayList<String> doCommand(String cmd)
            throws NativeDaemonConnectorException  {
        sendCommand(cmd);

        ArrayList<String> response = new ArrayList<String>();
        boolean complete = false;
        int code = -1;

        while (!complete) {
            try {
                String line = mResponseQueue.take();
                if (LOCAL_LOGD) Slog.d(TAG, String.format("RSP <- {%s}", line));
                String[] tokens = line.split(" ");
                try {
                    code = Integer.parseInt(tokens[0]);
                } catch (NumberFormatException nfe) {
                    throw new NativeDaemonConnectorException(
                            String.format("Invalid response from daemon (%s)", line));
                }

                if ((code >= 200) && (code < 600)) {
                    complete = true;
                }
                response.add(line);
            } catch (InterruptedException ex) {
                Slog.e(TAG, "Failed to process response", ex);
            }
        }

        if (code >= ResponseCode.FailedRangeStart &&
                code <= ResponseCode.FailedRangeEnd) {
            /*
             * Note: The format of the last response in this case is
             *        "NNN <errmsg>"
             */
            throw new NativeDaemonConnectorException(
                    code, cmd, response.get(response.size()-1).substring(4));
        }
        return response;
    }

    /*
     * Issues a list command and returns the cooked list
     */
    public String[] doListCommand(String cmd, int expectedResponseCode)
            throws NativeDaemonConnectorException {

        ArrayList<String> rsp = doCommand(cmd);
        String[] rdata = new String[rsp.size()-1];
        int idx = 0;

        for (int i = 0; i < rsp.size(); i++) {
            String line = rsp.get(i);
            try {
                String[] tok = line.split(" ");
                int code = Integer.parseInt(tok[0]);
                if (code == expectedResponseCode) {
                    rdata[idx++] = line.substring(tok[0].length() + 1);
                } else if (code == NativeDaemonConnector.ResponseCode.CommandOkay) {
                    if (LOCAL_LOGD) Slog.d(TAG, String.format("List terminated with {%s}", line));
                    int last = rsp.size() -1;
                    if (i != last) {
                        Slog.w(TAG, String.format("Recv'd %d lines after end of list {%s}", (last-i), cmd));
                        for (int j = i; j <= last ; j++) {
                            Slog.w(TAG, String.format("ExtraData <%s>", rsp.get(i)));
                        }
                    }
                    return rdata;
                } else {
                    throw new NativeDaemonConnectorException(
                            String.format("Expected list response %d, but got %d",
                                    expectedResponseCode, code));
                }
            } catch (NumberFormatException nfe) {
                throw new NativeDaemonConnectorException(
                        String.format("Error reading code '%s'", line));
            }
        }
        throw new NativeDaemonConnectorException("Got an empty response");
    }
}
