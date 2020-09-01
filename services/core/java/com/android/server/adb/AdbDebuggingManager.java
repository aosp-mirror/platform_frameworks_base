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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.adb;

import static com.android.internal.util.dump.DumpUtils.writeStringIfNotNull;

import android.annotation.TestApi;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.debug.AdbProtoEnums;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.adb.AdbDebuggingManagerProto;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;
import android.util.StatsLog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides communication to the Android Debug Bridge daemon to allow, deny, or clear public keysi
 * that are authorized to connect to the ADB service itself.
 */
public class AdbDebuggingManager {
    private static final String TAG = "AdbDebuggingManager";
    private static final boolean DEBUG = false;

    private static final String ADBD_SOCKET = "adbd";
    private static final String ADB_DIRECTORY = "misc/adb";
    // This file contains keys that will always be allowed to connect to the device via adb.
    private static final String ADB_KEYS_FILE = "adb_keys";
    // This file contains keys that will be allowed to connect without user interaction as long
    // as a subsequent connection occurs within the allowed duration.
    private static final String ADB_TEMP_KEYS_FILE = "adb_temp_keys.xml";
    private static final int BUFFER_SIZE = 65536;

    private final Context mContext;
    private final Handler mHandler;
    private AdbDebuggingThread mThread;
    private boolean mAdbEnabled = false;
    private String mFingerprints;
    private final List<String> mConnectedKeys;
    private String mConfirmComponent;
    private final File mTestUserKeyFile;

    public AdbDebuggingManager(Context context) {
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
        mTestUserKeyFile = null;
        mConnectedKeys = new ArrayList<>(1);
    }

    /**
     * Constructor that accepts the component to be invoked to confirm if the user wants to allow
     * an adb connection from the key.
     */
    @TestApi
    protected AdbDebuggingManager(Context context, String confirmComponent, File testUserKeyFile) {
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
        mConfirmComponent = confirmComponent;
        mTestUserKeyFile = testUserKeyFile;
        mConnectedKeys = new ArrayList<>();
    }

    class AdbDebuggingThread extends Thread {
        private boolean mStopped;
        private LocalSocket mSocket;
        private OutputStream mOutputStream;
        private InputStream mInputStream;

        AdbDebuggingThread() {
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
                mSocket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
                mSocket.connect(address);

                mOutputStream = mSocket.getOutputStream();
                mInputStream = mSocket.getInputStream();
            } catch (IOException ioe) {
                Slog.e(TAG, "Caught an exception opening the socket: " + ioe);
                closeSocketLocked();
                throw ioe;
            }
        }

        private void listenToSocket() throws IOException {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    int count = mInputStream.read(buffer);
                    // if less than 2 bytes are read the if statements below will throw an
                    // IndexOutOfBoundsException.
                    if (count < 2) {
                        Slog.w(TAG, "Read failed with count " + count);
                        break;
                    }

                    if (buffer[0] == 'P' && buffer[1] == 'K') {
                        String key = new String(Arrays.copyOfRange(buffer, 2, count));
                        Slog.d(TAG, "Received public key: " + key);
                        Message msg = mHandler.obtainMessage(
                                AdbDebuggingHandler.MESSAGE_ADB_CONFIRM);
                        msg.obj = key;
                        mHandler.sendMessage(msg);
                    } else if (buffer[0] == 'D' && buffer[1] == 'C') {
                        String key = new String(Arrays.copyOfRange(buffer, 2, count));
                        Slog.d(TAG, "Received disconnected message: " + key);
                        Message msg = mHandler.obtainMessage(
                                AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT);
                        msg.obj = key;
                        mHandler.sendMessage(msg);
                    } else if (buffer[0] == 'C' && buffer[1] == 'K') {
                        String key = new String(Arrays.copyOfRange(buffer, 2, count));
                        Slog.d(TAG, "Received connected key message: " + key);
                        Message msg = mHandler.obtainMessage(
                                AdbDebuggingHandler.MESSAGE_ADB_CONNECTED_KEY);
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
                    } catch (IOException ex) {
                        Slog.e(TAG, "Failed to write response:", ex);
                    }
                }
            }
        }
    }

    class AdbDebuggingHandler extends Handler {
        // The default time to schedule the job to keep the keystore updated with a currently
        // connected key as well as to removed expired keys.
        static final long UPDATE_KEYSTORE_JOB_INTERVAL = 86400000;
        // The minimum interval at which the job should run to update the keystore. This is intended
        // to prevent the job from running too often if the allowed connection time for adb grants
        // is set to an extremely small value.
        static final long UPDATE_KEYSTORE_MIN_JOB_INTERVAL = 60000;

        static final int MESSAGE_ADB_ENABLED = 1;
        static final int MESSAGE_ADB_DISABLED = 2;
        static final int MESSAGE_ADB_ALLOW = 3;
        static final int MESSAGE_ADB_DENY = 4;
        static final int MESSAGE_ADB_CONFIRM = 5;
        static final int MESSAGE_ADB_CLEAR = 6;
        static final int MESSAGE_ADB_DISCONNECT = 7;
        static final int MESSAGE_ADB_PERSIST_KEYSTORE = 8;
        static final int MESSAGE_ADB_UPDATE_KEYSTORE = 9;
        static final int MESSAGE_ADB_CONNECTED_KEY = 10;

        private AdbKeyStore mAdbKeyStore;

        private ContentObserver mAuthTimeObserver = new ContentObserver(this) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                Slog.d(TAG, "Received notification that uri " + uri
                        + " was modified; rescheduling keystore job");
                scheduleJobToUpdateAdbKeyStore();
            }
        };

        AdbDebuggingHandler(Looper looper) {
            super(looper);
        }

        /**
         * Constructor that accepts the AdbDebuggingThread to which responses should be sent
         * and the AdbKeyStore to be used to store the temporary grants.
         */
        @TestApi
        AdbDebuggingHandler(Looper looper, AdbDebuggingThread thread, AdbKeyStore adbKeyStore) {
            super(looper);
            mThread = thread;
            mAdbKeyStore = adbKeyStore;
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_ADB_ENABLED:
                    if (mAdbEnabled) {
                        break;
                    }
                    registerForAuthTimeChanges();
                    mAdbEnabled = true;

                    mThread = new AdbDebuggingThread();
                    mThread.start();

                    mAdbKeyStore = new AdbKeyStore();
                    mAdbKeyStore.updateKeyStore();
                    scheduleJobToUpdateAdbKeyStore();
                    break;

                case MESSAGE_ADB_DISABLED:
                    if (!mAdbEnabled) {
                        break;
                    }

                    mAdbEnabled = false;

                    if (mThread != null) {
                        mThread.stopListening();
                        mThread = null;
                    }

                    if (!mConnectedKeys.isEmpty()) {
                        for (String connectedKey : mConnectedKeys) {
                            mAdbKeyStore.setLastConnectionTime(connectedKey,
                                    System.currentTimeMillis());
                        }
                        sendPersistKeyStoreMessage();
                        mConnectedKeys.clear();
                    }
                    scheduleJobToUpdateAdbKeyStore();
                    break;

                case MESSAGE_ADB_ALLOW: {
                    String key = (String) msg.obj;
                    String fingerprints = getFingerprints(key);
                    if (!fingerprints.equals(mFingerprints)) {
                        Slog.e(TAG, "Fingerprints do not match. Got "
                                + fingerprints + ", expected " + mFingerprints);
                        break;
                    }

                    boolean alwaysAllow = msg.arg1 == 1;
                    if (mThread != null) {
                        mThread.sendResponse("OK");
                        if (alwaysAllow) {
                            if (!mConnectedKeys.contains(key)) {
                                mConnectedKeys.add(key);
                            }
                            mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
                            sendPersistKeyStoreMessage();
                            scheduleJobToUpdateAdbKeyStore();
                        }
                        logAdbConnectionChanged(key, AdbProtoEnums.USER_ALLOWED, alwaysAllow);
                    }
                    break;
                }

                case MESSAGE_ADB_DENY:
                    if (mThread != null) {
                        mThread.sendResponse("NO");
                        logAdbConnectionChanged(null, AdbProtoEnums.USER_DENIED, false);
                    }
                    break;

                case MESSAGE_ADB_CONFIRM: {
                    String key = (String) msg.obj;
                    if ("trigger_restart_min_framework".equals(
                            SystemProperties.get("vold.decrypt"))) {
                        Slog.d(TAG, "Deferring adb confirmation until after vold decrypt");
                        if (mThread != null) {
                            mThread.sendResponse("NO");
                            logAdbConnectionChanged(key, AdbProtoEnums.DENIED_VOLD_DECRYPT, false);
                        }
                        break;
                    }
                    String fingerprints = getFingerprints(key);
                    if ("".equals(fingerprints)) {
                        if (mThread != null) {
                            mThread.sendResponse("NO");
                            logAdbConnectionChanged(key, AdbProtoEnums.DENIED_INVALID_KEY, false);
                        }
                        break;
                    }
                    logAdbConnectionChanged(key, AdbProtoEnums.AWAITING_USER_APPROVAL, false);
                    mFingerprints = fingerprints;
                    startConfirmation(key, mFingerprints);
                    break;
                }

                case MESSAGE_ADB_CLEAR: {
                    Slog.d(TAG, "Received a request to clear the adb authorizations");
                    mConnectedKeys.clear();
                    // If the key store has not yet been instantiated then do so now; this avoids
                    // the unnecessary creation of the key store when adb is not enabled.
                    if (mAdbKeyStore == null) {
                        mAdbKeyStore = new AdbKeyStore();
                    }
                    mAdbKeyStore.deleteKeyStore();
                    cancelJobToUpdateAdbKeyStore();
                    break;
                }

                case MESSAGE_ADB_DISCONNECT: {
                    String key = (String) msg.obj;
                    boolean alwaysAllow = false;
                    if (key != null && key.length() > 0) {
                        if (mConnectedKeys.contains(key)) {
                            alwaysAllow = true;
                            mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
                            sendPersistKeyStoreMessage();
                            scheduleJobToUpdateAdbKeyStore();
                            mConnectedKeys.remove(key);
                        }
                    } else {
                        Slog.w(TAG, "Received a disconnected key message with an empty key");
                    }
                    logAdbConnectionChanged(key, AdbProtoEnums.DISCONNECTED, alwaysAllow);
                    break;
                }

                case MESSAGE_ADB_PERSIST_KEYSTORE: {
                    if (mAdbKeyStore != null) {
                        mAdbKeyStore.persistKeyStore();
                    }
                    break;
                }

                case MESSAGE_ADB_UPDATE_KEYSTORE: {
                    if (!mConnectedKeys.isEmpty()) {
                        for (String connectedKey : mConnectedKeys) {
                            mAdbKeyStore.setLastConnectionTime(connectedKey,
                                    System.currentTimeMillis());
                        }
                        sendPersistKeyStoreMessage();
                        scheduleJobToUpdateAdbKeyStore();
                    } else if (!mAdbKeyStore.isEmpty()) {
                        mAdbKeyStore.updateKeyStore();
                        scheduleJobToUpdateAdbKeyStore();
                    }
                    break;
                }

                case MESSAGE_ADB_CONNECTED_KEY: {
                    String key = (String) msg.obj;
                    if (key == null || key.length() == 0) {
                        Slog.w(TAG, "Received a connected key message with an empty key");
                    } else {
                        if (!mConnectedKeys.contains(key)) {
                            mConnectedKeys.add(key);
                        }
                        mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
                        sendPersistKeyStoreMessage();
                        scheduleJobToUpdateAdbKeyStore();
                        logAdbConnectionChanged(key, AdbProtoEnums.AUTOMATICALLY_ALLOWED, true);
                    }
                    break;
                }
            }
        }

        void registerForAuthTimeChanges() {
            Uri uri = Settings.Global.getUriFor(Settings.Global.ADB_ALLOWED_CONNECTION_TIME);
            mContext.getContentResolver().registerContentObserver(uri, false, mAuthTimeObserver);
        }

        private void logAdbConnectionChanged(String key, int state, boolean alwaysAllow) {
            long lastConnectionTime = mAdbKeyStore.getLastConnectionTime(key);
            long authWindow = mAdbKeyStore.getAllowedConnectionTime();
            Slog.d(TAG,
                    "Logging key " + key + ", state = " + state + ", alwaysAllow = " + alwaysAllow
                            + ", lastConnectionTime = " + lastConnectionTime + ", authWindow = "
                            + authWindow);
            StatsLog.write(StatsLog.ADB_CONNECTION_CHANGED, lastConnectionTime, authWindow, state,
                    alwaysAllow);
        }


        /**
         * Schedules a job to update the connection time of the currently connected key and filter
         * out any keys that are beyond their expiration time.
         *
         * @return the time in ms when the next job will run or -1 if the job should not be
         * scheduled to run.
         */
        @VisibleForTesting
        long scheduleJobToUpdateAdbKeyStore() {
            cancelJobToUpdateAdbKeyStore();
            long keyExpiration = mAdbKeyStore.getNextExpirationTime();
            // if the keyExpiration time is -1 then either the keys are set to never expire or
            // there are no keys in the keystore, just return for now as a new job will be
            // scheduled on the next connection or when the auth time changes.
            if (keyExpiration == -1) {
                return -1;
            }
            long delay;
            // if the keyExpiration is 0 this indicates a key has already expired; schedule the job
            // to run now to ensure the key is removed immediately from adb_keys.
            if (keyExpiration == 0) {
                delay = 0;
            } else {
                // else the next job should be run either daily or when the next key is set to
                // expire with a min job interval to ensure this job does not run too often if a
                // small value is set for the key expiration.
                delay = Math.max(Math.min(UPDATE_KEYSTORE_JOB_INTERVAL, keyExpiration),
                        UPDATE_KEYSTORE_MIN_JOB_INTERVAL);
            }
            Message message = obtainMessage(MESSAGE_ADB_UPDATE_KEYSTORE);
            sendMessageDelayed(message, delay);
            return delay;
        }

        /**
         * Cancels the scheduled job to update the connection time of the currently connected key
         * and to remove any expired keys.
         */
        private void cancelJobToUpdateAdbKeyStore() {
            removeMessages(AdbDebuggingHandler.MESSAGE_ADB_UPDATE_KEYSTORE);
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
            if (i < digest.length - 1) {
                sb.append(":");
            }
        }
        return sb.toString();
    }

    private void startConfirmation(String key, String fingerprints) {
        int currentUserId = ActivityManager.getCurrentUser();
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(currentUserId);
        String componentString;
        if (userInfo.isAdmin()) {
            componentString = mConfirmComponent != null
                    ? mConfirmComponent : Resources.getSystem().getString(
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
     * @return true if the componentName led to an Activity that was started.
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
     * @return true if the componentName led to a Service that was started.
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

    /**
     * Returns a new File with the specified name in the adb directory.
     */
    private File getAdbFile(String fileName) {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);

        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return null;
        }

        return new File(adbDir, fileName);
    }

    File getAdbTempKeysFile() {
        return getAdbFile(ADB_TEMP_KEYS_FILE);
    }

    File getUserKeyFile() {
        return mTestUserKeyFile == null ? getAdbFile(ADB_KEYS_FILE) : mTestUserKeyFile;
    }

    private void writeKey(String key) {
        try {
            File keyFile = getUserKeyFile();

            if (keyFile == null) {
                return;
            }

            FileOutputStream fo = new FileOutputStream(keyFile, true);
            fo.write(key.getBytes());
            fo.write('\n');
            fo.close();

            FileUtils.setPermissions(keyFile.toString(),
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP, -1, -1);
        } catch (IOException ex) {
            Slog.e(TAG, "Error writing key:" + ex);
        }
    }

    private void writeKeys(Iterable<String> keys) {
        AtomicFile atomicKeyFile = null;
        FileOutputStream fo = null;
        try {
            File keyFile = getUserKeyFile();

            if (keyFile == null) {
                return;
            }

            atomicKeyFile = new AtomicFile(keyFile);
            fo = atomicKeyFile.startWrite();
            for (String key : keys) {
                fo.write(key.getBytes());
                fo.write('\n');
            }
            atomicKeyFile.finishWrite(fo);

            FileUtils.setPermissions(keyFile.toString(),
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP, -1, -1);
        } catch (IOException ex) {
            Slog.e(TAG, "Error writing keys: " + ex);
            if (atomicKeyFile != null) {
                atomicKeyFile.failWrite(fo);
            }
        }
    }

    private void deleteKeyFile() {
        File keyFile = getUserKeyFile();
        if (keyFile != null) {
            keyFile.delete();
        }
    }

    /**
     * When {@code enabled} is {@code true}, this allows ADB debugging and starts the ADB hanler
     * thread. When {@code enabled} is {@code false}, this disallows ADB debugging and shuts
     * down the handler thread.
     */
    public void setAdbEnabled(boolean enabled) {
        mHandler.sendEmptyMessage(enabled ? AdbDebuggingHandler.MESSAGE_ADB_ENABLED
                                          : AdbDebuggingHandler.MESSAGE_ADB_DISABLED);
    }

    /**
     * Allows the debugging from the endpoint identified by {@code publicKey} either once or
     * always if {@code alwaysAllow} is {@code true}.
     */
    public void allowDebugging(boolean alwaysAllow, String publicKey) {
        Message msg = mHandler.obtainMessage(AdbDebuggingHandler.MESSAGE_ADB_ALLOW);
        msg.arg1 = alwaysAllow ? 1 : 0;
        msg.obj = publicKey;
        mHandler.sendMessage(msg);
    }

    /**
     * Denies debugging connection from the device that last requested to connect.
     */
    public void denyDebugging() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MESSAGE_ADB_DENY);
    }

    /**
     * Clears all previously accepted ADB debugging public keys. Any subsequent request will need
     * to pass through {@link #allowUsbDebugging(boolean, String)} again.
     */
    public void clearDebuggingKeys() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MESSAGE_ADB_CLEAR);
    }

    /**
     * Sends a message to the handler to persist the keystore.
     */
    private void sendPersistKeyStoreMessage() {
        Message msg = mHandler.obtainMessage(AdbDebuggingHandler.MESSAGE_ADB_PERSIST_KEYSTORE);
        mHandler.sendMessage(msg);
    }

    /**
     * Dump the USB debugging state.
     */
    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("connected_to_adb", AdbDebuggingManagerProto.CONNECTED_TO_ADB, mThread != null);
        writeStringIfNotNull(dump, "last_key_received", AdbDebuggingManagerProto.LAST_KEY_RECEVIED,
                mFingerprints);

        try {
            dump.write("user_keys", AdbDebuggingManagerProto.USER_KEYS,
                    FileUtils.readTextFile(new File("/data/misc/adb/adb_keys"), 0, null));
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read user keys", e);
        }

        try {
            dump.write("system_keys", AdbDebuggingManagerProto.SYSTEM_KEYS,
                    FileUtils.readTextFile(new File("/adb_keys"), 0, null));
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read system keys", e);
        }

        try {
            dump.write("keystore", AdbDebuggingManagerProto.KEYSTORE,
                    FileUtils.readTextFile(getAdbTempKeysFile(), 0, null));
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read keystore: ", e);
        }

        dump.end(token);
    }

    /**
     * Handles adb keys for which the user has granted the 'always allow' option. This class ensures
     * these grants are revoked after a period of inactivity as specified in the
     * ADB_ALLOWED_CONNECTION_TIME setting.
     */
    class AdbKeyStore {
        private Map<String, Long> mKeyMap;
        private Set<String> mSystemKeys;
        private File mKeyFile;
        private AtomicFile mAtomicKeyFile;

        private static final String XML_TAG_ADB_KEY = "adbKey";
        private static final String XML_ATTRIBUTE_KEY = "key";
        private static final String XML_ATTRIBUTE_LAST_CONNECTION = "lastConnection";
        private static final String SYSTEM_KEY_FILE = "/adb_keys";

        /**
         * Value returned by {@code getLastConnectionTime} when there is no previously saved
         * connection time for the specified key.
         */
        public static final long NO_PREVIOUS_CONNECTION = 0;

        /**
         * Constructor that uses the default location for the persistent adb keystore.
         */
        AdbKeyStore() {
            init();
        }

        /**
         * Constructor that uses the specified file as the location for the persistent adb keystore.
         */
        AdbKeyStore(File keyFile) {
            mKeyFile = keyFile;
            init();
        }

        private void init() {
            initKeyFile();
            mKeyMap = getKeyMap();
            mSystemKeys = getSystemKeysFromFile(SYSTEM_KEY_FILE);
            addUserKeysToKeyStore();
        }

        /**
         * Initializes the key file that will be used to persist the adb grants.
         */
        private void initKeyFile() {
            if (mKeyFile == null) {
                mKeyFile = getAdbTempKeysFile();
            }
            // getAdbTempKeysFile can return null if the adb file cannot be obtained
            if (mKeyFile != null) {
                mAtomicKeyFile = new AtomicFile(mKeyFile);
            }
        }

        private Set<String> getSystemKeysFromFile(String fileName) {
            Set<String> systemKeys = new HashSet<>();
            File systemKeyFile = new File(fileName);
            if (systemKeyFile.exists()) {
                try (BufferedReader in = new BufferedReader(new FileReader(systemKeyFile))) {
                    String key;
                    while ((key = in.readLine()) != null) {
                        key = key.trim();
                        if (key.length() > 0) {
                            systemKeys.add(key);
                        }
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Caught an exception reading " + fileName + ": " + e);
                }
            }
            return systemKeys;
        }

        /**
         * Returns whether there are any 'always allowed' keys in the keystore.
         */
        public boolean isEmpty() {
            return mKeyMap.isEmpty();
        }

        /**
         * Iterates through the keys in the keystore and removes any that are beyond the window
         * within which connections are automatically allowed without user interaction.
         */
        public void updateKeyStore() {
            if (filterOutOldKeys()) {
                sendPersistKeyStoreMessage();
            }
        }

        /**
         * Returns the key map with the keys and last connection times from the key file.
         */
        private Map<String, Long> getKeyMap() {
            Map<String, Long> keyMap = new HashMap<String, Long>();
            // if the AtomicFile could not be instantiated before attempt again; if it still fails
            // return an empty key map.
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(TAG, "Unable to obtain the key file, " + mKeyFile + ", for reading");
                    return keyMap;
                }
            }
            if (!mAtomicKeyFile.exists()) {
                return keyMap;
            }
            try (FileInputStream keyStream = mAtomicKeyFile.openRead()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(keyStream, StandardCharsets.UTF_8.name());
                XmlUtils.beginDocument(parser, XML_TAG_ADB_KEY);
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    String tagName = parser.getName();
                    if (tagName == null) {
                        break;
                    } else if (!tagName.equals(XML_TAG_ADB_KEY)) {
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String key = parser.getAttributeValue(null, XML_ATTRIBUTE_KEY);
                    long connectionTime;
                    try {
                        connectionTime = Long.valueOf(
                                parser.getAttributeValue(null, XML_ATTRIBUTE_LAST_CONNECTION));
                    } catch (NumberFormatException e) {
                        Slog.e(TAG,
                                "Caught a NumberFormatException parsing the last connection time: "
                                        + e);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    keyMap.put(key, connectionTime);
                }
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "Caught an exception parsing the XML key file: ", e);
            }
            return keyMap;
        }

        /**
         * Updates the keystore with keys that were previously set to be always allowed before the
         * connection time of keys was tracked.
         */
        private void addUserKeysToKeyStore() {
            File userKeyFile = getUserKeyFile();
            boolean mapUpdated = false;
            if (userKeyFile != null && userKeyFile.exists()) {
                try (BufferedReader in = new BufferedReader(new FileReader(userKeyFile))) {
                    long time = System.currentTimeMillis();
                    String key;
                    while ((key = in.readLine()) != null) {
                        // if the keystore does not contain the key from the user key file then add
                        // it to the Map with the current system time to prevent it from expiring
                        // immediately if the user is actively using this key.
                        if (!mKeyMap.containsKey(key)) {
                            mKeyMap.put(key, time);
                            mapUpdated = true;
                        }
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Caught an exception reading " + userKeyFile + ": " + e);
                }
            }
            if (mapUpdated) {
                sendPersistKeyStoreMessage();
            }
        }

        /**
         * Writes the key map to the key file.
         */
        public void persistKeyStore() {
            // if there is nothing in the key map then ensure any keys left in the keystore files
            // are deleted as well.
            filterOutOldKeys();
            if (mKeyMap.isEmpty()) {
                deleteKeyStore();
                return;
            }
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(TAG, "Unable to obtain the key file, " + mKeyFile + ", for writing");
                    return;
                }
            }
            FileOutputStream keyStream = null;
            try {
                XmlSerializer serializer = new FastXmlSerializer();
                keyStream = mAtomicKeyFile.startWrite();
                serializer.setOutput(keyStream, StandardCharsets.UTF_8.name());
                serializer.startDocument(null, true);

                for (Map.Entry<String, Long> keyEntry : mKeyMap.entrySet()) {
                    serializer.startTag(null, XML_TAG_ADB_KEY);
                    serializer.attribute(null, XML_ATTRIBUTE_KEY, keyEntry.getKey());
                    serializer.attribute(null, XML_ATTRIBUTE_LAST_CONNECTION,
                            String.valueOf(keyEntry.getValue()));
                    serializer.endTag(null, XML_TAG_ADB_KEY);
                }

                serializer.endDocument();
                mAtomicKeyFile.finishWrite(keyStream);
            } catch (IOException e) {
                Slog.e(TAG, "Caught an exception writing the key map: ", e);
                mAtomicKeyFile.failWrite(keyStream);
            }
        }

        private boolean filterOutOldKeys() {
            boolean keysDeleted = false;
            long allowedTime = getAllowedConnectionTime();
            long systemTime = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> keyMapIterator = mKeyMap.entrySet().iterator();
            while (keyMapIterator.hasNext()) {
                Map.Entry<String, Long> keyEntry = keyMapIterator.next();
                long connectionTime = keyEntry.getValue();
                if (allowedTime != 0 && systemTime > (connectionTime + allowedTime)) {
                    keyMapIterator.remove();
                    keysDeleted = true;
                }
            }
            // if any keys were deleted then the key file should be rewritten with the active keys
            // to prevent authorizing a key that is now beyond the allowed window.
            if (keysDeleted) {
                writeKeys(mKeyMap.keySet());
            }
            return keysDeleted;
        }

        /**
         * Returns the time in ms that the next key will expire or -1 if there are no keys or the
         * keys will not expire.
         */
        public long getNextExpirationTime() {
            long minExpiration = -1;
            long allowedTime = getAllowedConnectionTime();
            // if the allowedTime is 0 then keys never expire; return -1 to indicate this
            if (allowedTime == 0) {
                return minExpiration;
            }
            long systemTime = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> keyMapIterator = mKeyMap.entrySet().iterator();
            while (keyMapIterator.hasNext()) {
                Map.Entry<String, Long> keyEntry = keyMapIterator.next();
                long connectionTime = keyEntry.getValue();
                // if the key has already expired then ensure that the result is set to 0 so that
                // any scheduled jobs to clean up the keystore can run right away.
                long keyExpiration = Math.max(0, (connectionTime + allowedTime) - systemTime);
                if (minExpiration == -1 || keyExpiration < minExpiration) {
                    minExpiration = keyExpiration;
                }
            }
            return minExpiration;
        }

        /**
         * Removes all of the entries in the key map and deletes the key file.
         */
        public void deleteKeyStore() {
            mKeyMap.clear();
            deleteKeyFile();
            if (mAtomicKeyFile == null) {
                return;
            }
            mAtomicKeyFile.delete();
        }

        /**
         * Returns the time of the last connection from the specified key, or {@code
         * NO_PREVIOUS_CONNECTION} if the specified key does not have an active adb grant.
         */
        public long getLastConnectionTime(String key) {
            return mKeyMap.getOrDefault(key, NO_PREVIOUS_CONNECTION);
        }

        /**
         * Sets the time of the last connection for the specified key to the provided time.
         */
        public void setLastConnectionTime(String key, long connectionTime) {
            setLastConnectionTime(key, connectionTime, false);
        }

        /**
         * Sets the time of the last connection for the specified key to the provided time. If force
         * is set to true the time will be set even if it is older than the previously written
         * connection time.
         */
        public void setLastConnectionTime(String key, long connectionTime, boolean force) {
            // Do not set the connection time to a value that is earlier than what was previously
            // stored as the last connection time unless force is set.
            if (mKeyMap.containsKey(key) && mKeyMap.get(key) >= connectionTime && !force) {
                return;
            }
            // System keys are always allowed so there's no need to keep track of their connection
            // time.
            if (mSystemKeys.contains(key)) {
                return;
            }
            // if this is the first time the key is being added then write it to the key file as
            // well.
            if (!mKeyMap.containsKey(key)) {
                writeKey(key);
            }
            mKeyMap.put(key, connectionTime);
        }

        /**
         * Returns the connection time within which a connection from an allowed key is
         * automatically allowed without user interaction.
         */
        public long getAllowedConnectionTime() {
            return Settings.Global.getLong(mContext.getContentResolver(),
                    Settings.Global.ADB_ALLOWED_CONNECTION_TIME,
                    Settings.Global.DEFAULT_ADB_ALLOWED_CONNECTION_TIME);
        }

        /**
         * Returns whether the specified key should be authroized to connect without user
         * interaction. This requires that the user previously connected this device and selected
         * the option to 'Always allow', and the time since the last connection is within the
         * allowed window.
         */
        public boolean isKeyAuthorized(String key) {
            // A system key is always authorized to connect.
            if (mSystemKeys.contains(key)) {
                return true;
            }
            long lastConnectionTime = getLastConnectionTime(key);
            if (lastConnectionTime == NO_PREVIOUS_CONNECTION) {
                return false;
            }
            long allowedConnectionTime = getAllowedConnectionTime();
            // if the allowed connection time is 0 then revert to the previous behavior of always
            // allowing previously granted adb grants.
            if (allowedConnectionTime == 0 || (System.currentTimeMillis() < (lastConnectionTime
                    + allowedConnectionTime))) {
                return true;
            } else {
                return false;
            }
        }
    }
}
