/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.os;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Slog;
import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Represents a connection to {@code installd}. Allows multiple connect and
 * disconnect cycles.
 *
 * @hide for internal use only
 */
public class InstallerConnection {
    private static final String TAG = "InstallerConnection";
    private static final boolean LOCAL_DEBUG = false;

    private InputStream mIn;
    private OutputStream mOut;
    private LocalSocket mSocket;

    private final byte buf[] = new byte[1024];

    public InstallerConnection() {
    }

    public synchronized String transact(String cmd) {
        if (!connect()) {
            Slog.e(TAG, "connection failed");
            return "-1";
        }

        if (!writeCommand(cmd)) {
            /*
             * If installd died and restarted in the background (unlikely but
             * possible) we'll fail on the next write (this one). Try to
             * reconnect and write the command one more time before giving up.
             */
            Slog.e(TAG, "write command failed? reconnect!");
            if (!connect() || !writeCommand(cmd)) {
                return "-1";
            }
        }
        if (LOCAL_DEBUG) {
            Slog.i(TAG, "send: '" + cmd + "'");
        }

        final int replyLength = readReply();
        if (replyLength > 0) {
            String s = new String(buf, 0, replyLength);
            if (LOCAL_DEBUG) {
                Slog.i(TAG, "recv: '" + s + "'");
            }
            return s;
        } else {
            if (LOCAL_DEBUG) {
                Slog.i(TAG, "fail");
            }
            return "-1";
        }
    }

    public int execute(String cmd) {
        String res = transact(cmd);
        try {
            return Integer.parseInt(res);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public int dexopt(String apkPath, int uid, boolean isPublic,
            String instructionSet, int dexoptNeeded, boolean bootComplete) {
        return dexopt(apkPath, uid, isPublic, "*", instructionSet, dexoptNeeded,
                false, false, null, bootComplete);
    }

    public int dexopt(String apkPath, int uid, boolean isPublic, String pkgName,
            String instructionSet, int dexoptNeeded, boolean vmSafeMode,
            boolean debuggable, String outputPath, boolean bootComplete) {
        StringBuilder builder = new StringBuilder("dexopt");
        builder.append(' ');
        builder.append(apkPath);
        builder.append(' ');
        builder.append(uid);
        builder.append(isPublic ? " 1" : " 0");
        builder.append(' ');
        builder.append(pkgName);
        builder.append(' ');
        builder.append(instructionSet);
        builder.append(' ');
        builder.append(dexoptNeeded);
        builder.append(vmSafeMode ? " 1" : " 0");
        builder.append(debuggable ? " 1" : " 0");
        builder.append(' ');
        builder.append(outputPath != null ? outputPath : "!");
        builder.append(bootComplete ? " 1" : " 0");
        return execute(builder.toString());
    }

    private boolean connect() {
        if (mSocket != null) {
            return true;
        }
        Slog.i(TAG, "connecting...");
        try {
            mSocket = new LocalSocket();

            LocalSocketAddress address = new LocalSocketAddress("installd",
                    LocalSocketAddress.Namespace.RESERVED);

            mSocket.connect(address);

            mIn = mSocket.getInputStream();
            mOut = mSocket.getOutputStream();
        } catch (IOException ex) {
            disconnect();
            return false;
        }
        return true;
    }

    public void disconnect() {
        Slog.i(TAG, "disconnecting...");
        IoUtils.closeQuietly(mSocket);
        IoUtils.closeQuietly(mIn);
        IoUtils.closeQuietly(mOut);

        mSocket = null;
        mIn = null;
        mOut = null;
    }


    private boolean readFully(byte[] buffer, int len) {
        try {
            Streams.readFully(mIn, buffer, 0, len);
        } catch (IOException ioe) {
            Slog.e(TAG, "read exception");
            disconnect();
            return false;
        }

        if (LOCAL_DEBUG) {
            Slog.i(TAG, "read " + len + " bytes");
        }

        return true;
    }

    private int readReply() {
        if (!readFully(buf, 2)) {
            return -1;
        }

        final int len = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8);
        if ((len < 1) || (len > buf.length)) {
            Slog.e(TAG, "invalid reply length (" + len + ")");
            disconnect();
            return -1;
        }

        if (!readFully(buf, len)) {
            return -1;
        }

        return len;
    }

    private boolean writeCommand(String cmdString) {
        final byte[] cmd = cmdString.getBytes();
        final int len = cmd.length;
        if ((len < 1) || (len > buf.length)) {
            return false;
        }

        buf[0] = (byte) (len & 0xff);
        buf[1] = (byte) ((len >> 8) & 0xff);
        try {
            mOut.write(buf, 0, 2);
            mOut.write(cmd, 0, len);
        } catch (IOException ex) {
            Slog.e(TAG, "write error");
            disconnect();
            return false;
        }
        return true;
    }

    public void waitForConnection() {
        for (;;) {
            if (execute("ping") >= 0) {
                return;
            }
            Slog.w(TAG, "installd not ready");
            SystemClock.sleep(1000);
        }
    }
}
