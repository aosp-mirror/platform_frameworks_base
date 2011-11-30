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

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;

import com.google.android.collect.Lists;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Generic connector class for interfacing with a native daemon which uses the
 * {@code libsysutils} FrameworkListener protocol.
 */
final class NativeDaemonConnector implements Runnable, Handler.Callback, Watchdog.Monitor {
    private static final boolean LOGD = false;

    private final String TAG;

    private String mSocket;
    private OutputStream mOutputStream;

    private final BlockingQueue<NativeDaemonEvent> mResponseQueue;

    private INativeDaemonConnectorCallbacks mCallbacks;
    private Handler mCallbackHandler;

    /** Lock held whenever communicating with native daemon. */
    private final Object mDaemonLock = new Object();

    private final int BUFFER_SIZE = 4096;

    NativeDaemonConnector(INativeDaemonConnectorCallbacks callbacks, String socket,
            int responseQueueSize, String logTag) {
        mCallbacks = callbacks;
        mSocket = socket;
        mResponseQueue = new LinkedBlockingQueue<NativeDaemonEvent>(responseQueueSize);
        TAG = logTag != null ? logTag : "NativeDaemonConnector";
    }

    @Override
    public void run() {
        HandlerThread thread = new HandlerThread(TAG + ".CallbackHandler");
        thread.start();
        mCallbackHandler = new Handler(thread.getLooper(), this);

        while (true) {
            try {
                listenToSocket();
            } catch (Exception e) {
                Slog.e(TAG, "Error in NativeDaemonConnector", e);
                SystemClock.sleep(5000);
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        String event = (String) msg.obj;
        try {
            if (!mCallbacks.onEvent(msg.what, event, event.split(" "))) {
                Slog.w(TAG, String.format(
                        "Unhandled event '%s'", event));
            }
        } catch (Exception e) {
            Slog.e(TAG, String.format(
                    "Error handling '%s'", event), e);
        }
        return true;
    }

    private void listenToSocket() throws IOException {
        LocalSocket socket = null;

        try {
            socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(mSocket,
                    LocalSocketAddress.Namespace.RESERVED);

            socket.connect(address);

            InputStream inputStream = socket.getInputStream();
            mOutputStream = socket.getOutputStream();

            mCallbacks.onDaemonConnected();

            byte[] buffer = new byte[BUFFER_SIZE];
            int start = 0;

            while (true) {
                int count = inputStream.read(buffer, start, BUFFER_SIZE - start);
                if (count < 0) break;

                // Add our starting point to the count and reset the start.
                count += start;
                start = 0;

                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        final String rawEvent = new String(buffer, start, i - start);
                        if (LOGD) Slog.d(TAG, "RCV <- " + rawEvent);

                        try {
                            final NativeDaemonEvent event = NativeDaemonEvent.parseRawEvent(
                                    rawEvent);
                            if (event.isClassUnsolicited()) {
                                mCallbackHandler.sendMessage(mCallbackHandler.obtainMessage(
                                        event.getCode(), event.getRawEvent()));
                            } else {
                                try {
                                    mResponseQueue.put(event);
                                } catch (InterruptedException ex) {
                                    Slog.e(TAG, "Failed to put response onto queue: " + ex);
                                }
                            }
                        } catch (IllegalArgumentException e) {
                            Slog.w(TAG, "Problem parsing message: " + rawEvent, e);
                        }

                        start = i + 1;
                    }
                }

                // We should end at the amount we read. If not, compact then
                // buffer and read again.
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
            synchronized (mDaemonLock) {
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

    /**
     * Send command to daemon, escaping arguments as needed.
     *
     * @return the final command issued.
     */
    private String sendCommandLocked(String cmd, Object... args)
            throws NativeDaemonConnectorException {
        // TODO: eventually enforce that cmd doesn't contain arguments
        if (cmd.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("unexpected command: " + cmd);
        }

        final StringBuilder builder = new StringBuilder(cmd);
        for (Object arg : args) {
            final String argString = String.valueOf(arg);
            if (argString.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }

            builder.append(' ');
            appendEscaped(builder, argString);
        }

        final String unterminated = builder.toString();
        if (LOGD) Slog.d(TAG, "SND -> " + unterminated);

        builder.append('\0');

        if (mOutputStream == null) {
            throw new NativeDaemonConnectorException("missing output stream");
        } else {
            try {
                mOutputStream.write(builder.toString().getBytes());
            } catch (IOException e) {
                throw new NativeDaemonConnectorException("problem sending command", e);
            }
        }

        return unterminated;
    }

    /**
     * Issue a command to the native daemon and return the responses.
     */
    public NativeDaemonEvent[] execute(String cmd, Object... args)
            throws NativeDaemonConnectorException {
        synchronized (mDaemonLock) {
            return executeLocked(cmd, args);
        }
    }

    private NativeDaemonEvent[] executeLocked(String cmd, Object... args)
            throws NativeDaemonConnectorException {
        final ArrayList<NativeDaemonEvent> events = Lists.newArrayList();

        mResponseQueue.clear();

        final String sentCommand = sendCommandLocked(cmd, args);

        NativeDaemonEvent event = null;
        do {
            try {
                event = mResponseQueue.take();
            } catch (InterruptedException e) {
                Slog.w(TAG, "interrupted waiting for event line");
                continue;
            }
            events.add(event);
        } while (event.isClassContinue());

        if (event.isClassClientError()) {
            throw new NativeDaemonArgumentException(sentCommand, event);
        }
        if (event.isClassServerError()) {
            throw new NativeDaemonFailureException(sentCommand, event);
        }

        return events.toArray(new NativeDaemonEvent[events.size()]);
    }

    /**
     * Issue a command to the native daemon and return the raw responses.
     *
     * @deprecated callers should move to {@link #execute(String, Object...)}
     *             which returns parsed {@link NativeDaemonEvent}.
     */
    @Deprecated
    public ArrayList<String> doCommand(String cmd) throws NativeDaemonConnectorException {
        final ArrayList<String> rawEvents = Lists.newArrayList();
        final NativeDaemonEvent[] events = execute(cmd);
        for (NativeDaemonEvent event : events) {
            rawEvents.add(event.getRawEvent());
        }
        return rawEvents;
    }

    /**
     * Issues a list command and returns the cooked list of all
     * {@link NativeDaemonEvent#getMessage()} which match requested code.
     */
    public String[] doListCommand(String cmd, int expectedCode)
            throws NativeDaemonConnectorException {
        final ArrayList<String> list = Lists.newArrayList();

        final NativeDaemonEvent[] events = execute(cmd);
        for (int i = 0; i < events.length - 1; i++) {
            final NativeDaemonEvent event = events[i];
            final int code = event.getCode();
            if (code == expectedCode) {
                list.add(event.getMessage());
            } else {
                throw new NativeDaemonConnectorException(
                        "unexpected list response " + code + " instead of " + expectedCode);
            }
        }

        final NativeDaemonEvent finalEvent = events[events.length - 1];
        if (!finalEvent.isClassOk()) {
            throw new NativeDaemonConnectorException("unexpected final event: " + finalEvent);
        }

        return list.toArray(new String[list.size()]);
    }

    /**
     * Append the given argument to {@link StringBuilder}, escaping as needed,
     * and surrounding with quotes when it contains spaces.
     */
    // @VisibleForTesting
    static void appendEscaped(StringBuilder builder, String arg) {
        final boolean hasSpaces = arg.indexOf(' ') >= 0;
        if (hasSpaces) {
            builder.append('"');
        }

        final int length = arg.length();
        for (int i = 0; i < length; i++) {
            final char c = arg.charAt(i);

            if (c == '"') {
                builder.append("\\\"");
            } else if (c == '\\') {
                builder.append("\\\\");
            } else {
                builder.append(c);
            }
        }

        if (hasSpaces) {
            builder.append('"');
        }
    }

    private static class NativeDaemonArgumentException extends NativeDaemonConnectorException {
        public NativeDaemonArgumentException(String command, NativeDaemonEvent event) {
            super(command, event);
        }

        @Override
        public IllegalArgumentException rethrowAsParcelableException() {
            throw new IllegalArgumentException(getMessage(), this);
        }
    }

    private static class NativeDaemonFailureException extends NativeDaemonConnectorException {
        public NativeDaemonFailureException(String command, NativeDaemonEvent event) {
            super(command, event);
        }
    }

    /** {@inheritDoc} */
    public void monitor() {
        synchronized (mDaemonLock) { }
    }
}
