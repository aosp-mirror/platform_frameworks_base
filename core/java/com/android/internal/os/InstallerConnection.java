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
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

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

    private volatile Object mWarnIfHeld;

    private final byte buf[] = new byte[1024];

    public InstallerConnection() {
    }

    /**
     * Yell loudly if someone tries making future calls while holding a lock on
     * the given object.
     */
    public void setWarnIfHeld(Object warnIfHeld) {
        Preconditions.checkState(mWarnIfHeld == null);
        mWarnIfHeld = Preconditions.checkNotNull(warnIfHeld);
    }

    public synchronized String transact(String cmd) {
        if (mWarnIfHeld != null && Thread.holdsLock(mWarnIfHeld)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding 0x"
                    + Integer.toHexString(System.identityHashCode(mWarnIfHeld)), new Throwable());
        }

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

    public String[] execute(String cmd, Object... args) throws InstallerException {
        final StringBuilder builder = new StringBuilder(cmd);
        for (Object arg : args) {
            String escaped;
            if (arg == null) {
                escaped = "";
            } else {
                escaped = String.valueOf(arg);
            }
            if (escaped.indexOf('\0') != -1 || escaped.indexOf(' ') != -1 || "!".equals(escaped)) {
                throw new InstallerException(
                        "Invalid argument while executing " + cmd + " " + Arrays.toString(args));
            }
            if (TextUtils.isEmpty(escaped)) {
                escaped = "!";
            }
            builder.append(' ').append(escaped);
        }
        final String[] resRaw = transact(builder.toString()).split(" ");
        int res = -1;
        try {
            res = Integer.parseInt(resRaw[0]);
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException ignored) {
        }
        if (res != 0) {
            throw new InstallerException(
                    "Failed to execute " + cmd + " " + Arrays.toString(args) + ": " + res);
        }
        return resRaw;
    }

    public void dexopt(String apkPath, int uid, String instructionSet, int dexoptNeeded,
            int dexFlags, String compilerFilter, String volumeUuid, String sharedLibraries)
            throws InstallerException {
        dexopt(apkPath, uid, "*", instructionSet, dexoptNeeded, null /*outputPath*/, dexFlags,
                compilerFilter, volumeUuid, sharedLibraries);
    }

    public void dexopt(String apkPath, int uid, String pkgName, String instructionSet,
            int dexoptNeeded, String outputPath, int dexFlags, String compilerFilter,
            String volumeUuid, String sharedLibraries) throws InstallerException {
        execute("dexopt",
                apkPath,
                uid,
                pkgName,
                instructionSet,
                dexoptNeeded,
                outputPath,
                dexFlags,
                compilerFilter,
                volumeUuid,
                sharedLibraries);
    }

    private boolean safeParseBooleanResult(String[] res) throws InstallerException {
        if ((res == null) || (res.length != 2)) {
            throw new InstallerException("Invalid size result: " + Arrays.toString(res));
        }

        // Just as a sanity check. Anything != "true" will be interpreted as false by parseBoolean.
        if (!res[1].equals("true") && !res[1].equals("false")) {
            throw new InstallerException("Invalid boolean result: " + Arrays.toString(res));
        }

        return Boolean.parseBoolean(res[1]);
    }

    public boolean mergeProfiles(int uid, String pkgName) throws InstallerException {
        final String[] res = execute("merge_profiles", uid, pkgName);

        return safeParseBooleanResult(res);
    }

    public boolean dumpProfiles(String gid, String packageName, String codePaths)
            throws InstallerException {
        final String[] res = execute("dump_profiles", gid, packageName, codePaths);

        return safeParseBooleanResult(res);
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
            try {
                execute("ping");
                return;
            } catch (InstallerException ignored) {
            }
            Slog.w(TAG, "installd not ready");
            SystemClock.sleep(1000);
        }
    }

    public static class InstallerException extends Exception {
        public InstallerException(String detailMessage) {
            super(detailMessage);
        }
    }
}
