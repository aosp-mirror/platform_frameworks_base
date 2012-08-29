/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Slog;
import android.util.Base64;

import java.lang.Thread;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.Arrays;

public class UsbDebuggingManager implements Runnable {
    private static final String TAG = "UsbDebuggingManager";
    private static final boolean DEBUG = false;

    private final String ADBD_SOCKET = "adbd";
    private final String ADB_DIRECTORY = "misc/adb";
    private final String ADB_KEYS_FILE = "adb_keys";
    private final int BUFFER_SIZE = 4096;

    private final Context mContext;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private Thread mThread;
    private boolean mAdbEnabled = false;
    private String mFingerprints;
    private LocalSocket mSocket = null;
    private OutputStream mOutputStream = null;

    public UsbDebuggingManager(Context context) {
        mHandlerThread = new HandlerThread("UsbDebuggingHandler");
        mHandlerThread.start();
        mHandler = new UsbDebuggingHandler(mHandlerThread.getLooper());
        mContext = context;
    }

    private void listenToSocket() throws IOException {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            LocalSocketAddress address = new LocalSocketAddress(ADBD_SOCKET,
                                         LocalSocketAddress.Namespace.RESERVED);
            InputStream inputStream = null;

            mSocket = new LocalSocket();
            mSocket.connect(address);

            mOutputStream = mSocket.getOutputStream();
            inputStream = mSocket.getInputStream();

            while (true) {
                int count = inputStream.read(buffer);
                if (count < 0) {
                    Slog.e(TAG, "got " + count + " reading");
                    break;
                }

                if (buffer[0] == 'P' && buffer[1] == 'K') {
                    String key = new String(Arrays.copyOfRange(buffer, 2, count));
                    Slog.d(TAG, "Received public key: " + key);
                    Message msg = mHandler.obtainMessage(UsbDebuggingHandler.MESSAGE_ADB_CONFIRM);
                    msg.obj = key;
                    mHandler.sendMessage(msg);
                }
                else {
                    Slog.e(TAG, "Wrong message: " + (new String(Arrays.copyOfRange(buffer, 0, 2))));
                    break;
                }
            }
        } catch (IOException ex) {
            Slog.e(TAG, "Communication error: ", ex);
            throw ex;
        } finally {
            closeSocket();
        }
    }

    @Override
    public void run() {
        while (mAdbEnabled) {
            try {
                listenToSocket();
            } catch (Exception e) {
                /* Don't loop too fast if adbd dies, before init restarts it */
                SystemClock.sleep(1000);
            }
        }
    }

    private void closeSocket() {
        try {
            mOutputStream.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed closing output stream: " + e);
        }

        try {
            mSocket.close();
        } catch (IOException ex) {
            Slog.e(TAG, "Failed closing socket: " + ex);
        }
    }

    private void sendResponse(String msg) {
        if (mOutputStream != null) {
            try {
                mOutputStream.write(msg.getBytes());
            }
            catch (IOException ex) {
                Slog.e(TAG, "Failed to write response:", ex);
            }
        }
    }

    class UsbDebuggingHandler extends Handler {
        private static final int MESSAGE_ADB_ENABLED = 1;
        private static final int MESSAGE_ADB_DISABLED = 2;
        private static final int MESSAGE_ADB_ALLOW = 3;
        private static final int MESSAGE_ADB_DENY = 4;
        private static final int MESSAGE_ADB_CONFIRM = 5;

        public UsbDebuggingHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_ADB_ENABLED:
                    if (mAdbEnabled)
                        break;

                    mAdbEnabled = true;

                    mThread = new Thread(UsbDebuggingManager.this);
                    mThread.start();

                    break;

                case MESSAGE_ADB_DISABLED:
                    if (!mAdbEnabled)
                        break;

                    mAdbEnabled = false;
                    closeSocket();

                    try {
                        mThread.join();
                    } catch (Exception ex) {
                    }

                    mThread = null;
                    mOutputStream = null;
                    mSocket = null;
                    break;

                case MESSAGE_ADB_ALLOW: {
                    String key = (String)msg.obj;
                    String fingerprints = getFingerprints(key);

                    if (!fingerprints.equals(mFingerprints)) {
                        Slog.e(TAG, "Fingerprints do not match. Got "
                                + fingerprints + ", expected " + mFingerprints);
                        break;
                    }

                    if (msg.arg1 == 1) {
                        writeKey(key);
                    }

                    sendResponse("OK");
                    break;
                }

                case MESSAGE_ADB_DENY:
                    sendResponse("NO");
                    break;

                case MESSAGE_ADB_CONFIRM: {
                    String key = (String)msg.obj;
                    mFingerprints = getFingerprints(key);
                    showConfirmationDialog(key, mFingerprints);
                    break;
                }
            }
        }
    }

    private String getFingerprints(String key) {
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        MessageDigest digester;

        try {
            digester = MessageDigest.getInstance("MD5");
        } catch (Exception ex) {
            Slog.e(TAG, "Error getting digester: " + ex);
            return "";
        }

        byte[] base64_data = key.split("\\s+")[0].getBytes();
        byte[] digest = digester.digest(Base64.decode(base64_data, Base64.DEFAULT));

        for (int i = 0; i < digest.length; i++) {
            sb.append(hex.charAt((digest[i] >> 4) & 0xf));
            sb.append(hex.charAt(digest[i] & 0xf));
            if (i < digest.length - 1)
                sb.append(":");
        }
        return sb.toString();
    }

    private void showConfirmationDialog(String key, String fingerprints) {
        Intent dialogIntent = new Intent();

        dialogIntent.setClassName("com.android.systemui",
                "com.android.systemui.usb.UsbDebuggingActivity");
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        dialogIntent.putExtra("key", key);
        dialogIntent.putExtra("fingerprints", fingerprints);
        try {
            mContext.startActivity(dialogIntent);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start UsbDebuggingActivity");
        }
    }

    private void writeKey(String key) {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);

        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return;
        }

        try {
            File keyFile = new File(adbDir, ADB_KEYS_FILE);

            if (!keyFile.exists()) {
                keyFile.createNewFile();
                FileUtils.setPermissions(keyFile.toString(),
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                    FileUtils.S_IRGRP, -1, -1);
            }

            FileOutputStream fo = new FileOutputStream(keyFile, true);
            fo.write(key.getBytes());
            fo.write('\n');
            fo.close();
        }
        catch (IOException ex) {
            Slog.e(TAG, "Error writing key:" + ex);
        }
    }


    public void setAdbEnabled(boolean enabled) {
        mHandler.sendEmptyMessage(enabled ? UsbDebuggingHandler.MESSAGE_ADB_ENABLED
                                          : UsbDebuggingHandler.MESSAGE_ADB_DISABLED);
    }

    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
        Message msg = mHandler.obtainMessage(UsbDebuggingHandler.MESSAGE_ADB_ALLOW);
        msg.arg1 = alwaysAllow ? 1 : 0;
        msg.obj = publicKey;
        mHandler.sendMessage(msg);
    }

    public void denyUsbDebugging() {
        mHandler.sendEmptyMessage(UsbDebuggingHandler.MESSAGE_ADB_DENY);
    }


    public void dump(FileDescriptor fd, PrintWriter pw) {
        pw.println("  USB Debugging State:");
        pw.println("    Connected to adbd: " + (mOutputStream != null));
        pw.println("    Last key received: " + mFingerprints);
        pw.println("    User keys:");
        try {
            pw.println(FileUtils.readTextFile(new File("/data/misc/adb/adb_keys"), 0, null));
        } catch (IOException e) {
            pw.println("IOException: " + e);
        }
        pw.println("    System keys:");
        try {
            pw.println(FileUtils.readTextFile(new File("/adb_keys"), 0, null));
        } catch (IOException e) {
            pw.println("IOException: " + e);
        }
    }
}
