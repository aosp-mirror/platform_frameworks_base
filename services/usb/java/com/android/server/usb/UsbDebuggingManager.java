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

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Base64;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.FgThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.Arrays;

public class UsbDebuggingManager {
    private static final String TAG = "UsbDebuggingManager";
    private static final boolean DEBUG = false;

    private final String ADBD_SOCKET = "adbd";
    private final String ADB_DIRECTORY = "misc/adb";
    private final String ADB_KEYS_FILE = "adb_keys";
    private final int BUFFER_SIZE = 4096;

    private final Context mContext;
    private final Handler mHandler;
    private UsbDebuggingThread mThread;
    private boolean mAdbEnabled = false;
    private String mFingerprints;

    public UsbDebuggingManager(Context context) {
        mHandler = new UsbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
    }

    class UsbDebuggingThread extends Thread {
        private boolean mStopped;
        private LocalSocket mSocket;
        private OutputStream mOutputStream;
        private InputStream mInputStream;

        UsbDebuggingThread() {
            super(TAG);
        }

        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "Entering thread");
            while (true) {
                synchronized (this) {
                    if (mStopped) {
                        if (DEBUG) Slog.d(TAG, "Exiting thread");
                        return;
                    }
                    try {
                        openSocketLocked();
                    } catch (Exception e) {
                        /* Don't loop too fast if adbd dies, before init restarts it */
                        SystemClock.sleep(1000);
                    }
                }
                try {
                    listenToSocket();
                } catch (Exception e) {
                    /* Don't loop too fast if adbd dies, before init restarts it */
                    SystemClock.sleep(1000);
                }
            }
        }

        private void openSocketLocked() throws IOException {
            try {
                LocalSocketAddress address = new LocalSocketAddress(ADBD_SOCKET,
                        LocalSocketAddress.Namespace.RESERVED);
                mInputStream = null;

                if (DEBUG) Slog.d(TAG, "Creating socket");
                mSocket = new LocalSocket();
                mSocket.connect(address);

                mOutputStream = mSocket.getOutputStream();
                mInputStream = mSocket.getInputStream();
            } catch (IOException ioe) {
                closeSocketLocked();
                throw ioe;
            }
        }

        private void listenToSocket() throws IOException {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    int count = mInputStream.read(buffer);
                    if (count < 0) {
                        break;
                    }

                    if (buffer[0] == 'P' && buffer[1] == 'K') {
                        String key = new String(Arrays.copyOfRange(buffer, 2, count));
                        Slog.d(TAG, "Received public key: " + key);
                        Message msg = mHandler.obtainMessage(UsbDebuggingHandler.MESSAGE_ADB_CONFIRM);
                        msg.obj = key;
                        mHandler.sendMessage(msg);
                    } else {
                        Slog.e(TAG, "Wrong message: "
                                + (new String(Arrays.copyOfRange(buffer, 0, 2))));
                        break;
                    }
                }
            } finally {
                synchronized (this) {
                    closeSocketLocked();
                }
            }
        }

        private void closeSocketLocked() {
            if (DEBUG) Slog.d(TAG, "Closing socket");
            try {
                if (mOutputStream != null) {
                    mOutputStream.close();
                    mOutputStream = null;
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed closing output stream: " + e);
            }

            try {
                if (mSocket != null) {
                    mSocket.close();
                    mSocket = null;
                }
            } catch (IOException ex) {
                Slog.e(TAG, "Failed closing socket: " + ex);
            }
        }

        /** Call to stop listening on the socket and exit the thread. */
        void stopListening() {
            synchronized (this) {
                mStopped = true;
                closeSocketLocked();
            }
        }

        void sendResponse(String msg) {
            synchronized (this) {
                if (!mStopped && mOutputStream != null) {
                    try {
                        mOutputStream.write(msg.getBytes());
                    }
                    catch (IOException ex) {
                        Slog.e(TAG, "Failed to write response:", ex);
                    }
                }
            }
        }
    }

    class UsbDebuggingHandler extends Handler {
        private static final int MESSAGE_ADB_ENABLED = 1;
        private static final int MESSAGE_ADB_DISABLED = 2;
        private static final int MESSAGE_ADB_ALLOW = 3;
        private static final int MESSAGE_ADB_DENY = 4;
        private static final int MESSAGE_ADB_CONFIRM = 5;
        private static final int MESSAGE_ADB_CLEAR = 6;

        public UsbDebuggingHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_ADB_ENABLED:
                    if (mAdbEnabled)
                        break;

                    mAdbEnabled = true;

                    mThread = new UsbDebuggingThread();
                    mThread.start();

                    break;

                case MESSAGE_ADB_DISABLED:
                    if (!mAdbEnabled)
                        break;

                    mAdbEnabled = false;

                    if (mThread != null) {
                        mThread.stopListening();
                        mThread = null;
                    }

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

                    if (mThread != null) {
                        mThread.sendResponse("OK");
                    }
                    break;
                }

                case MESSAGE_ADB_DENY:
                    if (mThread != null) {
                        mThread.sendResponse("NO");
                    }
                    break;

                case MESSAGE_ADB_CONFIRM: {
                    if ("trigger_restart_min_framework".equals(
                            SystemProperties.get("vold.decrypt"))) {
                        Slog.d(TAG, "Deferring adb confirmation until after vold decrypt");
                        if (mThread != null) {
                            mThread.sendResponse("NO");
                        }
                        break;
                    }
                    String key = (String)msg.obj;
                    String fingerprints = getFingerprints(key);
                    if ("".equals(fingerprints)) {
                        if (mThread != null) {
                            mThread.sendResponse("NO");
                        }
                        break;
                    }
                    mFingerprints = fingerprints;
                    startConfirmation(key, mFingerprints);
                    break;
                }

                case MESSAGE_ADB_CLEAR:
                    deleteKeyFile();
                    break;
            }
        }
    }

    private String getFingerprints(String key) {
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        MessageDigest digester;

        if (key == null) {
            return "";
        }

        try {
            digester = MessageDigest.getInstance("MD5");
        } catch (Exception ex) {
            Slog.e(TAG, "Error getting digester", ex);
            return "";
        }

        byte[] base64_data = key.split("\\s+")[0].getBytes();
        byte[] digest;
        try {
            digest = digester.digest(Base64.decode(base64_data, Base64.DEFAULT));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "error doing base64 decoding", e);
            return "";
        }
        for (int i = 0; i < digest.length; i++) {
            sb.append(hex.charAt((digest[i] >> 4) & 0xf));
            sb.append(hex.charAt(digest[i] & 0xf));
            if (i < digest.length - 1)
                sb.append(":");
        }
        return sb.toString();
    }

    private void startConfirmation(String key, String fingerprints) {
        int currentUserId = ActivityManager.getCurrentUser();
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(currentUserId);
        String componentString;
        if (userInfo.isAdmin()) {
            componentString = Resources.getSystem().getString(
                    com.android.internal.R.string.config_customAdbPublicKeyConfirmationComponent);
        } else {
            // If the current foreground user is not the admin user we send a different
            // notification specific to secondary users.
            componentString = Resources.getSystem().getString(
                    R.string.config_customAdbPublicKeyConfirmationSecondaryUserComponent);
        }
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        if (startConfirmationActivity(componentName, userInfo.getUserHandle(), key, fingerprints)
                || startConfirmationService(componentName, userInfo.getUserHandle(),
                        key, fingerprints)) {
            return;
        }
        Slog.e(TAG, "unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component "
                + componentString + " as an Activity or a Service");
    }

    /**
     * @returns true if the componentName led to an Activity that was started.
     */
    private boolean startConfirmationActivity(ComponentName componentName, UserHandle userHandle,
            String key, String fingerprints) {
        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            try {
                mContext.startActivityAsUser(intent, userHandle);
                return true;
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "unable to start adb whitelist activity: " + componentName, e);
            }
        }
        return false;
    }

    /**
     * @returns true if the componentName led to a Service that was started.
     */
    private boolean startConfirmationService(ComponentName componentName, UserHandle userHandle,
            String key, String fingerprints) {
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        try {
            if (mContext.startServiceAsUser(intent, userHandle) != null) {
                return true;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "unable to start adb whitelist service: " + componentName, e);
        }
        return false;
    }

    private Intent createConfirmationIntent(ComponentName componentName, String key,
            String fingerprints) {
        Intent intent = new Intent();
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        intent.putExtra("key", key);
        intent.putExtra("fingerprints", fingerprints);
        return intent;
    }

    private File getUserKeyFile() {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);

        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return null;
        }

        return new File(adbDir, ADB_KEYS_FILE);
    }

    private void writeKey(String key) {
        try {
            File keyFile = getUserKeyFile();

            if (keyFile == null) {
                return;
            }

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

    private void deleteKeyFile() {
        File keyFile = getUserKeyFile();
        if (keyFile != null) {
            keyFile.delete();
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

    public void clearUsbDebuggingKeys() {
        mHandler.sendEmptyMessage(UsbDebuggingHandler.MESSAGE_ADB_CLEAR);
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("USB Debugging State:");
        pw.println("  Connected to adbd: " + (mThread != null));
        pw.println("  Last key received: " + mFingerprints);
        pw.println("  User keys:");
        try {
            pw.println(FileUtils.readTextFile(new File("/data/misc/adb/adb_keys"), 0, null));
        } catch (IOException e) {
            pw.println("IOException: " + e);
        }
        pw.println("  System keys:");
        try {
            pw.println(FileUtils.readTextFile(new File("/adb_keys"), 0, null));
        } catch (IOException e) {
            pw.println("IOException: " + e);
        }
    }
}
