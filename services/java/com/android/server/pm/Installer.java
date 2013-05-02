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
 * limitations under the License.
 */

package com.android.server.pm;

import android.content.pm.PackageStats;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Slog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class Installer {
    private static final String TAG = "Installer";

    private static final boolean LOCAL_DEBUG = false;

    InputStream mIn;

    OutputStream mOut;

    LocalSocket mSocket;

    byte buf[] = new byte[1024];

    int buflen = 0;

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

    private void disconnect() {
        Slog.i(TAG, "disconnecting...");
        try {
            if (mSocket != null)
                mSocket.close();
        } catch (IOException ex) {
        }
        try {
            if (mIn != null)
                mIn.close();
        } catch (IOException ex) {
        }
        try {
            if (mOut != null)
                mOut.close();
        } catch (IOException ex) {
        }
        mSocket = null;
        mIn = null;
        mOut = null;
    }

    private boolean readBytes(byte buffer[], int len) {
        int off = 0, count;
        if (len < 0)
            return false;
        while (off != len) {
            try {
                count = mIn.read(buffer, off, len - off);
                if (count <= 0) {
                    Slog.e(TAG, "read error " + count);
                    break;
                }
                off += count;
            } catch (IOException ex) {
                Slog.e(TAG, "read exception");
                break;
            }
        }
        if (LOCAL_DEBUG) {
            Slog.i(TAG, "read " + len + " bytes");
        }
        if (off == len)
            return true;
        disconnect();
        return false;
    }

    private boolean readReply() {
        int len;
        buflen = 0;
        if (!readBytes(buf, 2))
            return false;
        len = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8);
        if ((len < 1) || (len > 1024)) {
            Slog.e(TAG, "invalid reply length (" + len + ")");
            disconnect();
            return false;
        }
        if (!readBytes(buf, len))
            return false;
        buflen = len;
        return true;
    }

    private boolean writeCommand(String _cmd) {
        byte[] cmd = _cmd.getBytes();
        int len = cmd.length;
        if ((len < 1) || (len > 1024))
            return false;
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

    private synchronized String transaction(String cmd) {
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
        if (readReply()) {
            String s = new String(buf, 0, buflen);
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

    private int execute(String cmd) {
        String res = transaction(cmd);
        try {
            return Integer.parseInt(res);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public int install(String name, int uid, int gid, String seinfo) {
        StringBuilder builder = new StringBuilder("install");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(gid);
        builder.append(' ');
        builder.append(seinfo != null ? seinfo : "!");
        return execute(builder.toString());
    }

    public int dexopt(String apkPath, int uid, boolean isPublic) {
        StringBuilder builder = new StringBuilder("dexopt");
        builder.append(' ');
        builder.append(apkPath);
        builder.append(' ');
        builder.append(uid);
        builder.append(isPublic ? " 1" : " 0");
        return execute(builder.toString());
    }

    public int movedex(String srcPath, String dstPath) {
        StringBuilder builder = new StringBuilder("movedex");
        builder.append(' ');
        builder.append(srcPath);
        builder.append(' ');
        builder.append(dstPath);
        return execute(builder.toString());
    }

    public int rmdex(String codePath) {
        StringBuilder builder = new StringBuilder("rmdex");
        builder.append(' ');
        builder.append(codePath);
        return execute(builder.toString());
    }

    public int remove(String name, int userId) {
        StringBuilder builder = new StringBuilder("remove");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return execute(builder.toString());
    }

    public int rename(String oldname, String newname) {
        StringBuilder builder = new StringBuilder("rename");
        builder.append(' ');
        builder.append(oldname);
        builder.append(' ');
        builder.append(newname);
        return execute(builder.toString());
    }

    public int fixUid(String name, int uid, int gid) {
        StringBuilder builder = new StringBuilder("fixuid");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(gid);
        return execute(builder.toString());
    }

    public int deleteCacheFiles(String name, int userId) {
        StringBuilder builder = new StringBuilder("rmcache");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return execute(builder.toString());
    }

    public int createUserData(String name, int uid, int userId) {
        StringBuilder builder = new StringBuilder("mkuserdata");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(userId);
        return execute(builder.toString());
    }

    public int removeUserDataDirs(int userId) {
        StringBuilder builder = new StringBuilder("rmuser");
        builder.append(' ');
        builder.append(userId);
        return execute(builder.toString());
    }

    public int clearUserData(String name, int userId) {
        StringBuilder builder = new StringBuilder("rmuserdata");
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return execute(builder.toString());
    }

    public boolean ping() {
        if (execute("ping") < 0) {
            return false;
        } else {
            return true;
        }
    }

    public int freeCache(long freeStorageSize) {
        StringBuilder builder = new StringBuilder("freecache");
        builder.append(' ');
        builder.append(String.valueOf(freeStorageSize));
        return execute(builder.toString());
    }

    public int getSizeInfo(String pkgName, int persona, String apkPath, String libDirPath,
            String fwdLockApkPath, String asecPath, PackageStats pStats) {
        StringBuilder builder = new StringBuilder("getsize");
        builder.append(' ');
        builder.append(pkgName);
        builder.append(' ');
        builder.append(persona);
        builder.append(' ');
        builder.append(apkPath);
        builder.append(' ');
        builder.append(libDirPath != null ? libDirPath : "!");
        builder.append(' ');
        builder.append(fwdLockApkPath != null ? fwdLockApkPath : "!");
        builder.append(' ');
        builder.append(asecPath != null ? asecPath : "!");

        String s = transaction(builder.toString());
        String res[] = s.split(" ");

        if ((res == null) || (res.length != 5)) {
            return -1;
        }
        try {
            pStats.codeSize = Long.parseLong(res[1]);
            pStats.dataSize = Long.parseLong(res[2]);
            pStats.cacheSize = Long.parseLong(res[3]);
            pStats.externalCodeSize = Long.parseLong(res[4]);
            return Integer.parseInt(res[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public int moveFiles() {
        return execute("movefiles");
    }

    /**
     * Links the native library directory in an application's directory to its
     * real location.
     *
     * @param dataPath data directory where the application is
     * @param nativeLibPath target native library path
     * @return -1 on error
     */
    public int linkNativeLibraryDirectory(String dataPath, String nativeLibPath, int userId) {
        if (dataPath == null) {
            Slog.e(TAG, "linkNativeLibraryDirectory dataPath is null");
            return -1;
        } else if (nativeLibPath == null) {
            Slog.e(TAG, "linkNativeLibraryDirectory nativeLibPath is null");
            return -1;
        }

        StringBuilder builder = new StringBuilder("linklib ");
        builder.append(dataPath);
        builder.append(' ');
        builder.append(nativeLibPath);
        builder.append(' ');
        builder.append(userId);

        return execute(builder.toString());
    }
}
