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

import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

import static com.android.internal.util.dump.DumpUtils.writeStringIfNotNull;
import static com.android.server.adb.AdbService.ADBD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.debug.AdbManager;
import android.debug.AdbNotifications;
import android.debug.AdbProtoEnums;
import android.debug.AdbTransportType;
import android.debug.IAdbTransport;
import android.debug.PairDevice;
import android.net.ConnectivityManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.adb.AdbDebuggingManagerProto;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.FgThread;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides communication to the Android Debug Bridge daemon to allow, deny, or clear public keys
 * that are authorized to connect to the ADB service itself.
 *
 * <p>The AdbDebuggingManager controls two files:
 * <ol>
 *     <li>adb_keys
 *     <li>adb_temp_keys.xml
 * </ol>
 *
 * <p>The ADB Daemon (adbd) reads <em>only</em> the adb_keys file for authorization. Public keys
 * from registered hosts are stored in adb_keys, one entry per line.
 *
 * <p>AdbDebuggingManager also keeps adb_temp_keys.xml, which is used for two things
 * <ol>
 *     <li>Removing unused keys from the adb_keys file
 *     <li>Managing authorized WiFi access points for ADB over WiFi
 * </ol>
 */
public class AdbDebuggingManager {
    private static final String TAG = AdbDebuggingManager.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final boolean MDNS_DEBUG = false;

    private static final String ADBD_SOCKET = "adbd";
    private static final String ADB_DIRECTORY = "misc/adb";
    // This file contains keys that will always be allowed to connect to the device via adb.
    private static final String ADB_KEYS_FILE = "adb_keys";
    // This file contains keys that will be allowed to connect without user interaction as long
    // as a subsequent connection occurs within the allowed duration.
    private static final String ADB_TEMP_KEYS_FILE = "adb_temp_keys.xml";
    private static final int BUFFER_SIZE = 65536;
    private static final Ticker SYSTEM_TICKER = () -> System.currentTimeMillis();

    private final Context mContext;
    private final ContentResolver mContentResolver;
    @VisibleForTesting final AdbDebuggingHandler mHandler;
    @Nullable private AdbDebuggingThread mThread;
    private boolean mAdbUsbEnabled = false;
    private boolean mAdbWifiEnabled = false;
    private String mFingerprints;
    // A key can be used more than once (e.g. USB, wifi), so need to keep a refcount
    private final Map<String, Integer> mConnectedKeys = new HashMap<>();
    private final String mConfirmComponent;
    @Nullable private final File mUserKeyFile;
    @Nullable private final File mTempKeysFile;

    private static final String WIFI_PERSISTENT_CONFIG_PROPERTY =
            "persist.adb.tls_server.enable";
    private static final String WIFI_PERSISTENT_GUID =
            "persist.adb.wifi.guid";
    private static final int PAIRING_CODE_LENGTH = 6;
    /**
     * The maximum time to wait for the adbd service to change state when toggling.
     */
    private static final long ADBD_STATE_CHANGE_TIMEOUT = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
    private PairingThread mPairingThread = null;
    // A list of keys connected via wifi
    private final Set<String> mWifiConnectedKeys = new HashSet<>();
    // The current info of the adbwifi connection.
    private AdbConnectionInfo mAdbConnectionInfo = new AdbConnectionInfo();
    // Polls for a tls port property when adb wifi is enabled
    private AdbConnectionPortPoller mConnectionPortPoller;
    private final PortListenerImpl mPortListener = new PortListenerImpl();
    private final Ticker mTicker;

    public AdbDebuggingManager(Context context) {
        this(
                context,
                /* confirmComponent= */ null,
                getAdbFile(ADB_KEYS_FILE),
                getAdbFile(ADB_TEMP_KEYS_FILE),
                /* adbDebuggingThread= */ null,
                SYSTEM_TICKER);
    }

    /**
     * Constructor that accepts the component to be invoked to confirm if the user wants to allow
     * an adb connection from the key.
     */
    @VisibleForTesting
    AdbDebuggingManager(
            Context context,
            String confirmComponent,
            File testUserKeyFile,
            File tempKeysFile,
            AdbDebuggingThread adbDebuggingThread,
            Ticker ticker) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mConfirmComponent = confirmComponent;
        mUserKeyFile = testUserKeyFile;
        mTempKeysFile = tempKeysFile;
        mThread = adbDebuggingThread;
        mTicker = ticker;
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper(), mThread);
    }

    static void sendBroadcastWithDebugPermission(@NonNull Context context, @NonNull Intent intent,
            @NonNull UserHandle userHandle) {
        context.sendBroadcastAsUser(intent, userHandle,
                android.Manifest.permission.MANAGE_DEBUGGING);
    }

    class PairingThread extends Thread implements NsdManager.RegistrationListener {
        private NsdManager mNsdManager;
        private String mPublicKey;
        private String mPairingCode;
        private String mGuid;
        private String mServiceName;
        // From RFC6763 (https://tools.ietf.org/html/rfc6763#section-7.2),
        // The rules for Service Names [RFC6335] state that they may be no more
        // than fifteen characters long (not counting the mandatory underscore),
        // consisting of only letters, digits, and hyphens, must begin and end
        // with a letter or digit, must not contain consecutive hyphens, and
        // must contain at least one letter.
        @VisibleForTesting static final String SERVICE_PROTOCOL = "adb-tls-pairing";
        private final String mServiceType = String.format("_%s._tcp.", SERVICE_PROTOCOL);
        private int mPort;

        private native int native_pairing_start(String guid, String password);
        private native void native_pairing_cancel();
        private native boolean native_pairing_wait();

        PairingThread(String pairingCode, String serviceName) {
            super(TAG);
            mPairingCode = pairingCode;
            mGuid = SystemProperties.get(WIFI_PERSISTENT_GUID);
            mServiceName = serviceName;
            if (serviceName == null || serviceName.isEmpty()) {
                mServiceName = mGuid;
            }
            mPort = -1;
            mNsdManager = (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);
        }

        @Override
        public void run() {
            // Register the mdns service
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(mServiceName);
            serviceInfo.setServiceType(mServiceType);
            serviceInfo.setPort(mPort);
            mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, this);

            // Send pairing port to UI
            Message msg = mHandler.obtainMessage(
                    AdbDebuggingHandler.MSG_RESPONSE_PAIRING_PORT);
            msg.obj = mPort;
            mHandler.sendMessage(msg);

            boolean paired = native_pairing_wait();
            if (DEBUG) {
                if (mPublicKey != null) {
                    Slog.i(TAG, "Pairing succeeded key=" + mPublicKey);
                } else {
                    Slog.i(TAG, "Pairing failed");
                }
            }

            mNsdManager.unregisterService(this);

            Bundle bundle = new Bundle();
            bundle.putString("publicKey", paired ? mPublicKey : null);
            Message message = Message.obtain(mHandler,
                                             AdbDebuggingHandler.MSG_RESPONSE_PAIRING_RESULT,
                                             bundle);
            mHandler.sendMessage(message);
        }

        @Override
        public void start() {
            /*
             * If a user is fast enough to click cancel, native_pairing_cancel can be invoked
             * while native_pairing_start is running which run the destruction of the object
             * while it is being constructed. Here we start the pairing server on foreground
             * Thread so native_pairing_cancel can never be called concurrently. Then we let
             * the pairing server run on a background Thread.
             */
            if (mGuid.isEmpty()) {
                Slog.e(TAG, "adbwifi guid was not set");
                return;
            }
            mPort = native_pairing_start(mGuid, mPairingCode);
            if (mPort <= 0) {
                Slog.e(TAG, "Unable to start pairing server");
                return;
            }

            super.start();
        }

        public void cancelPairing() {
            native_pairing_cancel();
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            if (MDNS_DEBUG) Slog.i(TAG, "Registered pairing service: " + serviceInfo);
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Slog.e(TAG, "Failed to register pairing service(err=" + errorCode
                    + "): " + serviceInfo);
            cancelPairing();
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            if (MDNS_DEBUG) Slog.i(TAG, "Unregistered pairing service: " + serviceInfo);
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Slog.w(TAG, "Failed to unregister pairing service(err=" + errorCode
                    + "): " + serviceInfo);
        }
    }

    interface AdbConnectionPortListener {
        void onPortReceived(int port);
    }

    /**
     * This class will poll for a period of time for adbd to write the port
     * it connected to.
     *
     * TODO(joshuaduong): The port is being sent via system property because the adbd socket
     * (AdbDebuggingManager) is not created when ro.adb.secure=0. Thus, we must communicate the
     * port through different means. A better fix would be to always start AdbDebuggingManager, but
     * it needs to adjust accordingly on whether ro.adb.secure is set.
     */
    static class AdbConnectionPortPoller extends Thread {
        private final String mAdbPortProp = "service.adb.tls.port";
        private AdbConnectionPortListener mListener;
        private final int mDurationSecs = 10;
        private AtomicBoolean mCanceled = new AtomicBoolean(false);

        AdbConnectionPortPoller(AdbConnectionPortListener listener) {
            mListener = listener;
        }

        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "Starting adb port property poller");
            // Once adbwifi is enabled, we poll the service.adb.tls.port
            // system property until we get the port, or -1 on failure.
            // Let's also limit the polling to 10 seconds, just in case
            // something went wrong.
            for (int i = 0; i < mDurationSecs; ++i) {
                if (mCanceled.get()) {
                    return;
                }

                // If the property is set to -1, then that means adbd has failed
                // to start the server. Otherwise we should have a valid port.
                int port = SystemProperties.getInt(mAdbPortProp, Integer.MAX_VALUE);
                if (port == -1 || (port > 0 && port <= 65535)) {
                    mListener.onPortReceived(port);
                    return;
                }
                SystemClock.sleep(1000);
            }
            Slog.w(TAG, "Failed to receive adb connection port");
            mListener.onPortReceived(-1);
        }

        public void cancelAndWait() {
            mCanceled.set(true);
            if (this.isAlive()) {
                try {
                    this.join();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    class PortListenerImpl implements AdbConnectionPortListener {
        public void onPortReceived(int port) {
            if (DEBUG) Slog.d(TAG, "Received tls port=" + port);
            Message msg = mHandler.obtainMessage(port > 0
                     ? AdbDebuggingHandler.MSG_SERVER_CONNECTED
                     : AdbDebuggingHandler.MSG_SERVER_DISCONNECTED);
            msg.obj = port;
            mHandler.sendMessage(msg);
        }
    }

    @VisibleForTesting
    static class AdbDebuggingThread extends Thread {
        private boolean mStopped;
        private LocalSocket mSocket;
        private OutputStream mOutputStream;
        private InputStream mInputStream;
        private Handler mHandler;

        @VisibleForTesting
        AdbDebuggingThread() {
            super(TAG);
        }

        @VisibleForTesting
        void setHandler(Handler handler) {
            mHandler = handler;
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
                mHandler.sendEmptyMessage(AdbDebuggingHandler.MSG_ADBD_SOCKET_CONNECTED);
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
                    } else if (buffer[0] == 'W' && buffer[1] == 'E') {
                        // adbd_auth.h and AdbTransportType.aidl need to be kept in
                        // sync.
                        byte transportType = buffer[2];
                        String key = new String(Arrays.copyOfRange(buffer, 3, count));
                        if (transportType == AdbTransportType.USB) {
                            Slog.d(TAG, "Received USB TLS connected key message: " + key);
                            Message msg = mHandler.obtainMessage(
                                    AdbDebuggingHandler.MESSAGE_ADB_CONNECTED_KEY);
                            msg.obj = key;
                            mHandler.sendMessage(msg);
                        } else if (transportType == AdbTransportType.WIFI) {
                            Slog.d(TAG, "Received WIFI TLS connected key message: " + key);
                            Message msg = mHandler.obtainMessage(
                                    AdbDebuggingHandler.MSG_WIFI_DEVICE_CONNECTED);
                            msg.obj = key;
                            mHandler.sendMessage(msg);
                        } else {
                            Slog.e(TAG, "Got unknown transport type from adbd (" + transportType
                                    + ")");
                        }
                    } else if (buffer[0] == 'W' && buffer[1] == 'F') {
                        byte transportType = buffer[2];
                        String key = new String(Arrays.copyOfRange(buffer, 3, count));
                        if (transportType == AdbTransportType.USB) {
                            Slog.d(TAG, "Received USB TLS disconnect message: " + key);
                            Message msg = mHandler.obtainMessage(
                                    AdbDebuggingHandler.MESSAGE_ADB_DISCONNECT);
                            msg.obj = key;
                            mHandler.sendMessage(msg);
                        } else if (transportType == AdbTransportType.WIFI) {
                            Slog.d(TAG, "Received WIFI TLS disconnect key message: " + key);
                            Message msg = mHandler.obtainMessage(
                                    AdbDebuggingHandler.MSG_WIFI_DEVICE_DISCONNECTED);
                            msg.obj = key;
                            mHandler.sendMessage(msg);
                        } else {
                            Slog.e(TAG, "Got unknown transport type from adbd (" + transportType
                                    + ")");
                        }
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
            mHandler.sendEmptyMessage(AdbDebuggingHandler.MSG_ADBD_SOCKET_DISCONNECTED);
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

    private static class AdbConnectionInfo {
        private String mBssid;
        private String mSsid;
        private int mPort;

        AdbConnectionInfo() {
            mBssid = "";
            mSsid = "";
            mPort = -1;
        }

        AdbConnectionInfo(String bssid, String ssid) {
            mBssid = bssid;
            mSsid = ssid;
        }

        AdbConnectionInfo(AdbConnectionInfo other) {
            mBssid = other.mBssid;
            mSsid = other.mSsid;
            mPort = other.mPort;
        }

        public String getBSSID() {
            return mBssid;
        }

        public String getSSID() {
            return mSsid;
        }

        public int getPort() {
            return mPort;
        }

        public void setPort(int port) {
            mPort = port;
        }

        public void clear() {
            mBssid = "";
            mSsid = "";
            mPort = -1;
        }
    }

    private void setAdbConnectionInfo(AdbConnectionInfo info) {
        synchronized (mAdbConnectionInfo) {
            if (info == null) {
                mAdbConnectionInfo.clear();
                return;
            }
            mAdbConnectionInfo = info;
        }
    }

    private AdbConnectionInfo getAdbConnectionInfo() {
        synchronized (mAdbConnectionInfo) {
            return new AdbConnectionInfo(mAdbConnectionInfo);
        }
    }

    class AdbDebuggingHandler extends Handler {
        private NotificationManager mNotificationManager;
        private boolean mAdbNotificationShown;

        private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                // We only care about when wifi is disabled, and when there is a wifi network
                // change.
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED);
                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        Slog.i(TAG, "Wifi disabled. Disabling adbwifi.");
                        Settings.Global.putInt(mContentResolver,
                                Settings.Global.ADB_WIFI_ENABLED, 0);
                    }
                } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                    // We only care about wifi type connections
                    NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(
                            WifiManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo.class);
                    if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                        // Check for network disconnect
                        if (!networkInfo.isConnected()) {
                            Slog.i(TAG, "Network disconnected. Disabling adbwifi.");
                            Settings.Global.putInt(mContentResolver,
                                    Settings.Global.ADB_WIFI_ENABLED, 0);
                            return;
                        }

                        WifiManager wifiManager =
                                (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                            Slog.i(TAG, "Not connected to any wireless network."
                                    + " Not enabling adbwifi.");
                            Settings.Global.putInt(mContentResolver,
                                    Settings.Global.ADB_WIFI_ENABLED, 0);
                            return;
                        }

                        synchronized (mAdbConnectionInfo) {
                            // Check for network change
                            final String bssid = wifiInfo.getBSSID();
                            if (TextUtils.isEmpty(bssid)) {
                                Slog.e(TAG,
                                        "Unable to get the wifi ap's BSSID. Disabling adbwifi.");
                                Settings.Global.putInt(mContentResolver,
                                        Settings.Global.ADB_WIFI_ENABLED, 0);
                                return;
                            }
                            if (!TextUtils.equals(bssid, mAdbConnectionInfo.getBSSID())) {
                                Slog.i(TAG, "Detected wifi network change. Disabling adbwifi.");
                                Settings.Global.putInt(mContentResolver,
                                        Settings.Global.ADB_WIFI_ENABLED, 0);
                            }
                        }
                    }
                }
            }
        };

        private static final String ADB_NOTIFICATION_CHANNEL_ID_TV = "usbdevicemanager.adb.tv";

        private boolean isTv() {
            return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        }

        private void setupNotifications() {
            if (mNotificationManager != null) {
                return;
            }
            mNotificationManager = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (mNotificationManager == null) {
                Slog.e(TAG, "Unable to setup notifications for wireless debugging");
                return;
            }

            // Ensure that the notification channels are set up
            if (isTv()) {
                // TV-specific notification channel
                mNotificationManager.createNotificationChannel(
                        new NotificationChannel(ADB_NOTIFICATION_CHANNEL_ID_TV,
                                mContext.getString(
                                        com.android.internal.R.string
                                                .adb_debugging_notification_channel_tv),
                                NotificationManager.IMPORTANCE_HIGH));
            }
        }

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

        // === Messages from the UI ==============
        // UI asks adbd to enable adbdwifi
        static final int MSG_ADBDWIFI_ENABLE = 11;
        // UI asks adbd to disable adbdwifi
        static final int MSG_ADBDWIFI_DISABLE = 12;
        // Cancel pairing
        static final int MSG_PAIRING_CANCEL = 14;
        // Enable pairing by pairing code
        static final int MSG_PAIR_PAIRING_CODE = 15;
        // Enable pairing by QR code
        static final int MSG_PAIR_QR_CODE = 16;
        // UI asks to unpair (forget) a device.
        static final int MSG_REQ_UNPAIR = 17;
        // User allows debugging on the current network
        static final int MSG_ADBWIFI_ALLOW = 18;
        // User denies debugging on the current network
        static final int MSG_ADBWIFI_DENY = 19;

        // === Messages from the PairingThread ===========
        // Result of the pairing
        static final int MSG_RESPONSE_PAIRING_RESULT = 20;
        // The port opened for pairing
        static final int MSG_RESPONSE_PAIRING_PORT = 21;

        // === Messages from adbd ================
        // Notifies us a wifi device connected.
        static final int MSG_WIFI_DEVICE_CONNECTED = 22;
        // Notifies us a wifi device disconnected.
        static final int MSG_WIFI_DEVICE_DISCONNECTED = 23;
        // Notifies us the TLS server is connected and listening
        static final int MSG_SERVER_CONNECTED = 24;
        // Notifies us the TLS server is disconnected
        static final int MSG_SERVER_DISCONNECTED = 25;
        // Notification when adbd socket successfully connects.
        static final int MSG_ADBD_SOCKET_CONNECTED = 26;
        // Notification when adbd socket is disconnected.
        static final int MSG_ADBD_SOCKET_DISCONNECTED = 27;

        // === Messages from other parts of the system
        private static final int MESSAGE_KEY_FILES_UPDATED = 28;

        // === Messages we can send to adbd ===========
        static final String MSG_DISCONNECT_DEVICE = "DD";
        static final String MSG_DISABLE_ADBDWIFI = "DA";

        @Nullable @VisibleForTesting AdbKeyStore mAdbKeyStore;

        // Usb, Wi-Fi transports can be enabled together or separately, so don't break the framework
        // connection unless all transport types are disconnected.
        private int mAdbEnabledRefCount = 0;

        private ContentObserver mAuthTimeObserver = new ContentObserver(this) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                Slog.d(TAG, "Received notification that uri " + uri
                        + " was modified; rescheduling keystore job");
                scheduleJobToUpdateAdbKeyStore();
            }
        };

        /** Constructor that accepts the AdbDebuggingThread to which responses should be sent. */
        @VisibleForTesting
        AdbDebuggingHandler(Looper looper, AdbDebuggingThread thread) {
            super(looper);
            mThread = thread;
        }

        /** Initialize the AdbKeyStore so tests can grab mAdbKeyStore immediately. */
        @VisibleForTesting
        void initKeyStore() {
            if (mAdbKeyStore == null) {
                mAdbKeyStore = new AdbKeyStore();
            }
        }

        // Show when at least one device is connected.
        public void showAdbConnectedNotification(boolean show) {
            final int id = SystemMessage.NOTE_ADB_WIFI_ACTIVE;
            if (show == mAdbNotificationShown) {
                return;
            }
            setupNotifications();
            if (!mAdbNotificationShown) {
                Notification notification = AdbNotifications.createNotification(mContext,
                        AdbTransportType.WIFI);
                mAdbNotificationShown = true;
                mNotificationManager.notifyAsUser(null, id, notification,
                        UserHandle.ALL);
            } else {
                mAdbNotificationShown = false;
                mNotificationManager.cancelAsUser(null, id, UserHandle.ALL);
            }
        }

        private void startAdbDebuggingThread() {
            ++mAdbEnabledRefCount;
            if (DEBUG) Slog.i(TAG, "startAdbDebuggingThread ref=" + mAdbEnabledRefCount);
            if (mAdbEnabledRefCount > 1) {
                return;
            }

            registerForAuthTimeChanges();
            mThread = new AdbDebuggingThread();
            mThread.setHandler(mHandler);
            mThread.start();

            mAdbKeyStore.updateKeyStore();
            scheduleJobToUpdateAdbKeyStore();
        }

        private void stopAdbDebuggingThread() {
            --mAdbEnabledRefCount;
            if (DEBUG) Slog.i(TAG, "stopAdbDebuggingThread ref=" + mAdbEnabledRefCount);
            if (mAdbEnabledRefCount > 0) {
                return;
            }

            if (mThread != null) {
                mThread.stopListening();
                mThread = null;
            }

            if (!mConnectedKeys.isEmpty()) {
                for (Map.Entry<String, Integer> entry : mConnectedKeys.entrySet()) {
                    mAdbKeyStore.setLastConnectionTime(entry.getKey(), mTicker.currentTimeMillis());
                }
                sendPersistKeyStoreMessage();
                mConnectedKeys.clear();
                mWifiConnectedKeys.clear();
            }
            scheduleJobToUpdateAdbKeyStore();
        }

        public void handleMessage(Message msg) {
            initKeyStore();

            switch (msg.what) {
                case MESSAGE_ADB_ENABLED:
                    if (mAdbUsbEnabled) {
                        break;
                    }
                    startAdbDebuggingThread();
                    mAdbUsbEnabled = true;
                    break;

                case MESSAGE_ADB_DISABLED:
                    if (!mAdbUsbEnabled) {
                        break;
                    }
                    stopAdbDebuggingThread();
                    mAdbUsbEnabled = false;
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
                            if (!mConnectedKeys.containsKey(key)) {
                                mConnectedKeys.put(key, 1);
                            }
                            mAdbKeyStore.setLastConnectionTime(key, mTicker.currentTimeMillis());
                            sendPersistKeyStoreMessage();
                            scheduleJobToUpdateAdbKeyStore();
                        }
                        logAdbConnectionChanged(key, AdbProtoEnums.USER_ALLOWED, alwaysAllow);
                    }
                    break;
                }

                case MESSAGE_ADB_DENY:
                    if (mThread != null) {
                        Slog.w(TAG, "Denying adb confirmation");
                        mThread.sendResponse("NO");
                        logAdbConnectionChanged(null, AdbProtoEnums.USER_DENIED, false);
                    }
                    break;

                case MESSAGE_ADB_CONFIRM: {
                    String key = (String) msg.obj;
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
                    startConfirmationForKey(key, mFingerprints);
                    break;
                }

                case MESSAGE_ADB_CLEAR: {
                    Slog.d(TAG, "Received a request to clear the adb authorizations");
                    mConnectedKeys.clear();
                    // If the key store has not yet been instantiated then do so now; this avoids
                    // the unnecessary creation of the key store when adb is not enabled.
                    initKeyStore();
                    mWifiConnectedKeys.clear();
                    mAdbKeyStore.deleteKeyStore();
                    cancelJobToUpdateAdbKeyStore();
                    // Disconnect all active sessions unless the user opted out through Settings.
                    if (Settings.Global.getInt(mContentResolver,
                            Settings.Global.ADB_DISCONNECT_SESSIONS_ON_REVOKE, 1) == 1) {
                        // If adb is currently enabled, then toggle it off and back on to disconnect
                        // any existing sessions.
                        if (mAdbUsbEnabled) {
                            try {
                                SystemService.stop(ADBD);
                                SystemService.waitForState(ADBD, SystemService.State.STOPPED,
                                        ADBD_STATE_CHANGE_TIMEOUT);
                                SystemService.start(ADBD);
                                SystemService.waitForState(ADBD, SystemService.State.RUNNING,
                                        ADBD_STATE_CHANGE_TIMEOUT);
                            } catch (TimeoutException e) {
                                Slog.e(TAG, "Timeout occurred waiting for adbd to cycle: ", e);
                                // TODO(b/281758086): Display a dialog to the user to warn them
                                // of this state and direct them to manually toggle adb.
                                // If adbd fails to toggle within the timeout window, set adb to
                                // disabled to alert the user that further action is required if
                                // they want to continue using adb after revoking the grants.
                                Settings.Global.putInt(mContentResolver,
                                        Settings.Global.ADB_ENABLED, 0);
                            }
                        }
                    }
                    break;
                }

                case MESSAGE_ADB_DISCONNECT: {
                    String key = (String) msg.obj;
                    boolean alwaysAllow = false;
                    if (key != null && key.length() > 0) {
                        if (mConnectedKeys.containsKey(key)) {
                            alwaysAllow = true;
                            int refcount = mConnectedKeys.get(key) - 1;
                            if (refcount == 0) {
                                mAdbKeyStore.setLastConnectionTime(
                                        key, mTicker.currentTimeMillis());
                                sendPersistKeyStoreMessage();
                                scheduleJobToUpdateAdbKeyStore();
                                mConnectedKeys.remove(key);
                            } else {
                                mConnectedKeys.put(key, refcount);
                            }
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
                        for (Map.Entry<String, Integer> entry : mConnectedKeys.entrySet()) {
                            mAdbKeyStore.setLastConnectionTime(entry.getKey(),
                                    mTicker.currentTimeMillis());
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
                        if (!mConnectedKeys.containsKey(key)) {
                            mConnectedKeys.put(key, 1);
                        } else {
                            mConnectedKeys.put(key, mConnectedKeys.get(key) + 1);
                        }
                        mAdbKeyStore.setLastConnectionTime(key, mTicker.currentTimeMillis());
                        sendPersistKeyStoreMessage();
                        scheduleJobToUpdateAdbKeyStore();
                        logAdbConnectionChanged(key, AdbProtoEnums.AUTOMATICALLY_ALLOWED, true);
                    }
                    break;
                }
                case MSG_ADBDWIFI_ENABLE: {
                    if (mAdbWifiEnabled) {
                        break;
                    }

                    AdbConnectionInfo currentInfo = getCurrentWifiApInfo();
                    if (currentInfo == null) {
                        Settings.Global.putInt(mContentResolver,
                                Settings.Global.ADB_WIFI_ENABLED, 0);
                        break;
                    }

                    if (!verifyWifiNetwork(currentInfo.getBSSID(),
                            currentInfo.getSSID())) {
                        // This means that the network is not in the list of trusted networks.
                        // We'll give user a prompt on whether to allow wireless debugging on
                        // the current wifi network.
                        Settings.Global.putInt(mContentResolver,
                                Settings.Global.ADB_WIFI_ENABLED, 0);
                        break;
                    }

                    setAdbConnectionInfo(currentInfo);
                    IntentFilter intentFilter =
                            new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
                    intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                    mContext.registerReceiver(mBroadcastReceiver, intentFilter);

                    SystemProperties.set(WIFI_PERSISTENT_CONFIG_PROPERTY, "1");
                    mConnectionPortPoller =
                            new AdbDebuggingManager.AdbConnectionPortPoller(mPortListener);
                    mConnectionPortPoller.start();

                    startAdbDebuggingThread();
                    mAdbWifiEnabled = true;

                    if (DEBUG) Slog.i(TAG, "adb start wireless adb");
                    break;
                }
                case MSG_ADBDWIFI_DISABLE:
                    if (!mAdbWifiEnabled) {
                        break;
                    }
                    mAdbWifiEnabled = false;
                    setAdbConnectionInfo(null);
                    mContext.unregisterReceiver(mBroadcastReceiver);

                    if (mThread != null) {
                        mThread.sendResponse(MSG_DISABLE_ADBDWIFI);
                    }
                    onAdbdWifiServerDisconnected(-1);
                    stopAdbDebuggingThread();
                    break;
                case MSG_ADBWIFI_ALLOW:
                    if (mAdbWifiEnabled) {
                        break;
                    }
                    String bssid = (String) msg.obj;
                    boolean alwaysAllow = msg.arg1 == 1;
                    if (alwaysAllow) {
                        mAdbKeyStore.addTrustedNetwork(bssid);
                    }

                    // Let's check again to make sure we didn't switch networks while verifying
                    // the wifi bssid.
                    AdbConnectionInfo newInfo = getCurrentWifiApInfo();
                    if (newInfo == null || !bssid.equals(newInfo.getBSSID())) {
                        break;
                    }

                    setAdbConnectionInfo(newInfo);
                    Settings.Global.putInt(mContentResolver,
                            Settings.Global.ADB_WIFI_ENABLED, 1);
                    IntentFilter intentFilter =
                            new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
                    intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                    mContext.registerReceiver(mBroadcastReceiver, intentFilter);

                    SystemProperties.set(WIFI_PERSISTENT_CONFIG_PROPERTY, "1");
                    mConnectionPortPoller =
                            new AdbDebuggingManager.AdbConnectionPortPoller(mPortListener);
                    mConnectionPortPoller.start();

                    startAdbDebuggingThread();
                    mAdbWifiEnabled = true;

                    if (DEBUG) Slog.i(TAG, "adb start wireless adb");
                    break;
                case MSG_ADBWIFI_DENY:
                    Settings.Global.putInt(mContentResolver,
                            Settings.Global.ADB_WIFI_ENABLED, 0);
                    sendServerConnectionState(false, -1);
                    break;
                case MSG_REQ_UNPAIR: {
                    String fingerprint = (String) msg.obj;
                    // Tell adbd to disconnect the device if connected.
                    String publicKey = mAdbKeyStore.findKeyFromFingerprint(fingerprint);
                    if (publicKey == null || publicKey.isEmpty()) {
                        Slog.e(TAG, "Not a known fingerprint [" + fingerprint + "]");
                        break;
                    }
                    String cmdStr = MSG_DISCONNECT_DEVICE + publicKey;
                    if (mThread != null) {
                        mThread.sendResponse(cmdStr);
                    }
                    mAdbKeyStore.removeKey(publicKey);
                    // Send the updated paired devices list to the UI.
                    sendPairedDevicesToUI(mAdbKeyStore.getPairedDevices());
                    break;
                }
                case MSG_RESPONSE_PAIRING_RESULT: {
                    Bundle bundle = (Bundle) msg.obj;
                    String publicKey = bundle.getString("publicKey");
                    onPairingResult(publicKey);
                    // Send the updated paired devices list to the UI.
                    sendPairedDevicesToUI(mAdbKeyStore.getPairedDevices());
                    break;
                }
                case MSG_RESPONSE_PAIRING_PORT: {
                    int port = (int) msg.obj;
                    sendPairingPortToUI(port);
                    break;
                }
                case MSG_PAIR_PAIRING_CODE: {
                    String pairingCode = createPairingCode(PAIRING_CODE_LENGTH);
                    updateUIPairCode(pairingCode);
                    mPairingThread = new PairingThread(pairingCode, null);
                    mPairingThread.start();
                    break;
                }
                case MSG_PAIR_QR_CODE: {
                    Bundle bundle = (Bundle) msg.obj;
                    String serviceName = bundle.getString("serviceName");
                    String password = bundle.getString("password");
                    mPairingThread = new PairingThread(password, serviceName);
                    mPairingThread.start();
                    break;
                }
                case MSG_PAIRING_CANCEL:
                    if (mPairingThread != null) {
                        mPairingThread.cancelPairing();
                        try {
                            mPairingThread.join();
                        } catch (InterruptedException e) {
                            Slog.w(TAG, "Error while waiting for pairing thread to quit.");
                            e.printStackTrace();
                        }
                        mPairingThread = null;
                    }
                    break;
                case MSG_WIFI_DEVICE_CONNECTED: {
                    String key = (String) msg.obj;
                    if (mWifiConnectedKeys.add(key)) {
                        sendPairedDevicesToUI(mAdbKeyStore.getPairedDevices());
                        showAdbConnectedNotification(true);
                    }
                    break;
                }
                case MSG_WIFI_DEVICE_DISCONNECTED: {
                    String key = (String) msg.obj;
                    if (mWifiConnectedKeys.remove(key)) {
                        sendPairedDevicesToUI(mAdbKeyStore.getPairedDevices());
                        if (mWifiConnectedKeys.isEmpty()) {
                            showAdbConnectedNotification(false);
                        }
                    }
                    break;
                }
                case MSG_SERVER_CONNECTED: {
                    int port = (int) msg.obj;
                    onAdbdWifiServerConnected(port);
                    synchronized (mAdbConnectionInfo) {
                        mAdbConnectionInfo.setPort(port);
                    }
                    Settings.Global.putInt(mContentResolver,
                            Settings.Global.ADB_WIFI_ENABLED, 1);
                    break;
                }
                case MSG_SERVER_DISCONNECTED: {
                    if (!mAdbWifiEnabled) {
                        break;
                    }
                    int port = (int) msg.obj;
                    onAdbdWifiServerDisconnected(port);
                    Settings.Global.putInt(mContentResolver,
                            Settings.Global.ADB_WIFI_ENABLED, 0);
                    stopAdbDebuggingThread();
                    if (mConnectionPortPoller != null) {
                        mConnectionPortPoller.cancelAndWait();
                        mConnectionPortPoller = null;
                    }
                    break;
                }
                case MSG_ADBD_SOCKET_CONNECTED: {
                    if (DEBUG) Slog.d(TAG, "adbd socket connected");
                    if (mAdbWifiEnabled) {
                        // In scenarios where adbd is restarted, the tls port may change.
                        mConnectionPortPoller =
                                new AdbDebuggingManager.AdbConnectionPortPoller(mPortListener);
                        mConnectionPortPoller.start();
                    }
                    break;
                }
                case MSG_ADBD_SOCKET_DISCONNECTED: {
                    if (DEBUG) Slog.d(TAG, "adbd socket disconnected");
                    if (mConnectionPortPoller != null) {
                        mConnectionPortPoller.cancelAndWait();
                        mConnectionPortPoller = null;
                    }
                    if (mAdbWifiEnabled) {
                        // In scenarios where adbd is restarted, the tls port may change.
                        onAdbdWifiServerDisconnected(-1);
                    }
                    break;
                }
                case MESSAGE_KEY_FILES_UPDATED: {
                    mAdbKeyStore.reloadKeyMap();
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
            FrameworkStatsLog.write(FrameworkStatsLog.ADB_CONNECTION_CHANGED, lastConnectionTime,
                    authWindow, state, alwaysAllow);
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

        // Generates a random string of digits with size |size|.
        private String createPairingCode(int size) {
            String res = "";
            SecureRandom rand = new SecureRandom();
            for (int i = 0; i < size; ++i) {
                res += rand.nextInt(10);
            }

            return res;
        }

        private void sendServerConnectionState(boolean connected, int port) {
            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_STATE_CHANGED_ACTION);
            intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA, connected
                    ? AdbManager.WIRELESS_STATUS_CONNECTED
                    : AdbManager.WIRELESS_STATUS_DISCONNECTED);
            intent.putExtra(AdbManager.WIRELESS_DEBUG_PORT_EXTRA, port);
            AdbDebuggingManager.sendBroadcastWithDebugPermission(mContext, intent, UserHandle.ALL);
        }

        private void onAdbdWifiServerConnected(int port) {
            // Send the paired devices list to the UI
            sendPairedDevicesToUI(mAdbKeyStore.getPairedDevices());
            sendServerConnectionState(true, port);
        }

        private void onAdbdWifiServerDisconnected(int port) {
            // The TLS server disconnected while we had wireless debugging enabled.
            // Let's disable it.
            mWifiConnectedKeys.clear();
            showAdbConnectedNotification(false);
            sendServerConnectionState(false, port);
        }

        /**
         * Returns the [bssid, ssid] of the current access point.
         */
        private AdbConnectionInfo getCurrentWifiApInfo() {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                Slog.i(TAG, "Not connected to any wireless network. Not enabling adbwifi.");
                return null;
            }

            String ssid = null;
            if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
                ssid = wifiInfo.getPasspointProviderFriendlyName();
            } else {
                ssid = wifiInfo.getSSID();
                if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) {
                    // OK, it's not in the connectionInfo; we have to go hunting for it
                    List<WifiConfiguration> networks = wifiManager.getConfiguredNetworks();
                    int length = networks.size();
                    for (int i = 0; i < length; i++) {
                        if (networks.get(i).networkId == wifiInfo.getNetworkId()) {
                            ssid = networks.get(i).SSID;
                        }
                    }
                    if (ssid == null) {
                        Slog.e(TAG, "Unable to get ssid of the wifi AP.");
                        return null;
                    }
                }
            }

            String bssid = wifiInfo.getBSSID();
            if (TextUtils.isEmpty(bssid)) {
                Slog.e(TAG, "Unable to get the wifi ap's BSSID.");
                return null;
            }
            return new AdbConnectionInfo(bssid, ssid);
        }

        private boolean verifyWifiNetwork(String bssid, String ssid) {
            // Check against a list of user-trusted networks.
            if (mAdbKeyStore.isTrustedNetwork(bssid)) {
                return true;
            }

            // Ask user to confirm using wireless debugging on this network.
            startConfirmationForNetwork(ssid, bssid);
            return false;
        }

        private void onPairingResult(String publicKey) {
            if (publicKey == null) {
                Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
                intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA, AdbManager.WIRELESS_STATUS_FAIL);
                AdbDebuggingManager.sendBroadcastWithDebugPermission(mContext, intent,
                        UserHandle.ALL);
            } else {
                Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
                intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA,
                        AdbManager.WIRELESS_STATUS_SUCCESS);
                String fingerprints = getFingerprints(publicKey);
                String hostname = "nouser@nohostname";
                String[] args = publicKey.split("\\s+");
                if (args.length > 1) {
                    hostname = args[1];
                }
                PairDevice device = new PairDevice();
                device.name = fingerprints;
                device.guid = hostname;
                device.connected = false;
                intent.putExtra(AdbManager.WIRELESS_PAIR_DEVICE_EXTRA, device);
                AdbDebuggingManager.sendBroadcastWithDebugPermission(mContext, intent,
                        UserHandle.ALL);
                // Add the key into the keystore
                mAdbKeyStore.setLastConnectionTime(publicKey, mTicker.currentTimeMillis());
                sendPersistKeyStoreMessage();
                scheduleJobToUpdateAdbKeyStore();
            }
        }

        private void sendPairingPortToUI(int port) {
            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
            intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA,
                    AdbManager.WIRELESS_STATUS_CONNECTED);
            intent.putExtra(AdbManager.WIRELESS_DEBUG_PORT_EXTRA, port);
            AdbDebuggingManager.sendBroadcastWithDebugPermission(mContext, intent, UserHandle.ALL);
        }

        private void sendPairedDevicesToUI(Map<String, PairDevice> devices) {
            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRED_DEVICES_ACTION);
            // Map is not serializable, so need to downcast
            intent.putExtra(AdbManager.WIRELESS_DEVICES_EXTRA, (HashMap) devices);
            AdbDebuggingManager.sendBroadcastWithDebugPermission(mContext, intent, UserHandle.ALL);
        }

        private void updateUIPairCode(String code) {
            if (DEBUG) Slog.i(TAG, "updateUIPairCode: " + code);

            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
            intent.putExtra(AdbManager.WIRELESS_PAIRING_CODE_EXTRA, code);
            intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA,
                    AdbManager.WIRELESS_STATUS_PAIRING_CODE);
            AdbDebuggingManager.sendBroadcastWithDebugPermission(mContext, intent, UserHandle.ALL);
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

    private void startConfirmationForNetwork(String ssid, String bssid) {
        List<Map.Entry<String, String>> extras = new ArrayList<Map.Entry<String, String>>();
        extras.add(new AbstractMap.SimpleEntry<String, String>("ssid", ssid));
        extras.add(new AbstractMap.SimpleEntry<String, String>("bssid", bssid));
        int currentUserId = ActivityManager.getCurrentUser();
        String componentString =
                Resources.getSystem().getString(
                        R.string.config_customAdbWifiNetworkConfirmationComponent);
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(currentUserId);
        if (startConfirmationActivity(componentName, userInfo.getUserHandle(), extras)
                || startConfirmationService(componentName, userInfo.getUserHandle(), extras)) {
            return;
        }
        Slog.e(TAG, "Unable to start customAdbWifiNetworkConfirmation[SecondaryUser]Component "
                + componentString + " as an Activity or a Service");
    }

    private void startConfirmationForKey(String key, String fingerprints) {
        List<Map.Entry<String, String>> extras = new ArrayList<Map.Entry<String, String>>();
        extras.add(new AbstractMap.SimpleEntry<String, String>("key", key));
        extras.add(new AbstractMap.SimpleEntry<String, String>("fingerprints", fingerprints));
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
        if (startConfirmationActivity(componentName, userInfo.getUserHandle(), extras)
                || startConfirmationService(componentName, userInfo.getUserHandle(),
                        extras)) {
            return;
        }
        Slog.e(TAG, "unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component "
                + componentString + " as an Activity or a Service");
    }

    /**
     * @return true if the componentName led to an Activity that was started.
     */
    private boolean startConfirmationActivity(ComponentName componentName, UserHandle userHandle,
            List<Map.Entry<String, String>> extras) {
        PackageManager packageManager = mContext.getPackageManager();
        Intent intent = createConfirmationIntent(componentName, extras);
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
            List<Map.Entry<String, String>> extras) {
        Intent intent = createConfirmationIntent(componentName, extras);
        try {
            if (mContext.startServiceAsUser(intent, userHandle) != null) {
                return true;
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "unable to start adb whitelist service: " + componentName, e);
        }
        return false;
    }

    private Intent createConfirmationIntent(ComponentName componentName,
            List<Map.Entry<String, String>> extras) {
        Intent intent = new Intent();
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        for (Map.Entry<String, String> entry : extras) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        return intent;
    }

    /**
     * Returns a new File with the specified name in the adb directory.
     */
    private static File getAdbFile(String fileName) {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);

        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return null;
        }

        return new File(adbDir, fileName);
    }

    File getAdbTempKeysFile() {
        return mTempKeysFile;
    }

    File getUserKeyFile() {
        return mUserKeyFile;
    }

    private void writeKeys(Iterable<String> keys) {
        if (mUserKeyFile == null) {
            return;
        }

        AtomicFile atomicKeyFile = new AtomicFile(mUserKeyFile);
        // Note: Do not use a try-with-resources with the FileOutputStream, because AtomicFile
        // requires that it's cleaned up with AtomicFile.failWrite();
        FileOutputStream fo = null;
        try {
            fo = atomicKeyFile.startWrite();
            for (String key : keys) {
                fo.write(key.getBytes());
                fo.write('\n');
            }
            atomicKeyFile.finishWrite(fo);
        } catch (IOException ex) {
            Slog.e(TAG, "Error writing keys: " + ex);
            atomicKeyFile.failWrite(fo);
            return;
        }

        FileUtils.setPermissions(
                mUserKeyFile.toString(),
                FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP, -1, -1);
    }

    /**
     * When {@code enabled} is {@code true}, this allows ADB debugging and starts the ADB handler
     * thread. When {@code enabled} is {@code false}, this disallows ADB debugging for the given
     * @{code transportType}. See {@link IAdbTransport} for all available transport types.
     * If all transport types are disabled, the ADB handler thread will shut down.
     */
    public void setAdbEnabled(boolean enabled, byte transportType) {
        if (transportType == AdbTransportType.USB) {
            mHandler.sendEmptyMessage(enabled ? AdbDebuggingHandler.MESSAGE_ADB_ENABLED
                                              : AdbDebuggingHandler.MESSAGE_ADB_DISABLED);
        } else if (transportType == AdbTransportType.WIFI) {
            mHandler.sendEmptyMessage(enabled ? AdbDebuggingHandler.MSG_ADBDWIFI_ENABLE
                                              : AdbDebuggingHandler.MSG_ADBDWIFI_DISABLE);
        } else {
            throw new IllegalArgumentException(
                    "setAdbEnabled called with unimplemented transport type=" + transportType);
        }
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
     * Allows wireless debugging on the network identified by {@code bssid} either once
     * or always if {@code alwaysAllow} is {@code true}.
     */
    public void allowWirelessDebugging(boolean alwaysAllow, String bssid) {
        Message msg = mHandler.obtainMessage(AdbDebuggingHandler.MSG_ADBWIFI_ALLOW);
        msg.arg1 = alwaysAllow ? 1 : 0;
        msg.obj = bssid;
        mHandler.sendMessage(msg);
    }

    /**
     * Denies wireless debugging connection on the last requested network.
     */
    public void denyWirelessDebugging() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MSG_ADBWIFI_DENY);
    }

    /**
     * Returns the port adbwifi is currently opened on.
     */
    public int getAdbWirelessPort() {
        AdbConnectionInfo info = getAdbConnectionInfo();
        if (info == null) {
            return 0;
        }
        return info.getPort();
    }

    /**
     * Returns the list of paired devices.
     */
    public Map<String, PairDevice> getPairedDevices() {
        AdbKeyStore keystore = new AdbKeyStore();
        return keystore.getPairedDevices();
    }

    /**
     * Unpair with device
     */
    public void unpairDevice(String fingerprint) {
        Message message = Message.obtain(mHandler,
                                         AdbDebuggingHandler.MSG_REQ_UNPAIR,
                                         fingerprint);
        mHandler.sendMessage(message);
    }

    /**
     * Enable pairing by pairing code
     */
    public void enablePairingByPairingCode() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MSG_PAIR_PAIRING_CODE);
    }

    /**
     * Enable pairing by pairing code
     */
    public void enablePairingByQrCode(String serviceName, String password) {
        Bundle bundle = new Bundle();
        bundle.putString("serviceName", serviceName);
        bundle.putString("password", password);
        Message message = Message.obtain(mHandler,
                                         AdbDebuggingHandler.MSG_PAIR_QR_CODE,
                                         bundle);
        mHandler.sendMessage(message);
    }

    /**
     * Disables pairing
     */
    public void disablePairing() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MSG_PAIRING_CANCEL);
    }

    /**
     * Status enabled/disabled check
     */
    public boolean isAdbWifiEnabled() {
        return mAdbWifiEnabled;
    }

    /**
     * Notify that they key files were updated so the AdbKeyManager reloads the keys.
     */
    public void notifyKeyFilesUpdated() {
        mHandler.sendEmptyMessage(AdbDebuggingHandler.MESSAGE_KEY_FILES_UPDATED);
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
            File userKeys = new File("/data/misc/adb/adb_keys");
            if (userKeys.exists()) {
                dump.write("user_keys", AdbDebuggingManagerProto.USER_KEYS,
                           FileUtils.readTextFile(userKeys, 0, null));
            } else {
                Slog.i(TAG, "No user keys on this device");
            }
        } catch (IOException e) {
            Slog.i(TAG, "Cannot read user keys", e);
        }

        try {
            dump.write("system_keys", AdbDebuggingManagerProto.SYSTEM_KEYS,
                    FileUtils.readTextFile(new File("/adb_keys"), 0, null));
        } catch (IOException e) {
            Slog.i(TAG, "Cannot read system keys", e);
        }

        try {
            dump.write("keystore", AdbDebuggingManagerProto.KEYSTORE,
                    FileUtils.readTextFile(mTempKeysFile, 0, null));
        } catch (IOException e) {
            Slog.i(TAG, "Cannot read keystore: ", e);
        }

        dump.end(token);
    }

    /**
     * Handles adb keys for which the user has granted the 'always allow' option. This class ensures
     * these grants are revoked after a period of inactivity as specified in the
     * ADB_ALLOWED_CONNECTION_TIME setting.
     */
    class AdbKeyStore {
        private AtomicFile mAtomicKeyFile;

        private final Set<String> mSystemKeys;
        private final Map<String, Long> mKeyMap = new HashMap<>();
        private final List<String> mTrustedNetworks = new ArrayList<>();

        private static final int KEYSTORE_VERSION = 1;
        private static final int MAX_SUPPORTED_KEYSTORE_VERSION = 1;
        private static final String XML_KEYSTORE_START_TAG = "keyStore";
        private static final String XML_ATTRIBUTE_VERSION = "version";
        private static final String XML_TAG_ADB_KEY = "adbKey";
        private static final String XML_ATTRIBUTE_KEY = "key";
        private static final String XML_ATTRIBUTE_LAST_CONNECTION = "lastConnection";
        // A list of trusted networks a device can always wirelessly debug on (always allow).
        // TODO: Move trusted networks list into a different file?
        private static final String XML_TAG_WIFI_ACCESS_POINT = "wifiAP";
        private static final String XML_ATTRIBUTE_WIFI_BSSID = "bssid";

        private static final String SYSTEM_KEY_FILE = "/adb_keys";

        /**
         * Value returned by {@code getLastConnectionTime} when there is no previously saved
         * connection time for the specified key.
         */
        public static final long NO_PREVIOUS_CONNECTION = 0;

        /**
         * Create an AdbKeyStore instance.
         *
         * <p>Upon creation, we parse {@link #mTempKeysFile} to determine authorized WiFi APs and
         * retrieve the map of stored ADB keys and their last connected times. After that, we read
         * the {@link #mUserKeyFile}, and any keys that exist in that file that do not exist in the
         * map are added to the map (for backwards compatibility).
         */
        AdbKeyStore() {
            initKeyFile();
            readTempKeysFile();
            mSystemKeys = getSystemKeysFromFile(SYSTEM_KEY_FILE);
            addExistingUserKeysToKeyStore();
        }

        public void reloadKeyMap() {
            readTempKeysFile();
        }

        public void addTrustedNetwork(String bssid) {
            mTrustedNetworks.add(bssid);
            sendPersistKeyStoreMessage();
        }

        public Map<String, PairDevice> getPairedDevices() {
            Map<String, PairDevice> pairedDevices = new HashMap<String, PairDevice>();
            for (Map.Entry<String, Long> keyEntry : mKeyMap.entrySet()) {
                String fingerprints = getFingerprints(keyEntry.getKey());
                String hostname = "nouser@nohostname";
                String[] args = keyEntry.getKey().split("\\s+");
                if (args.length > 1) {
                    hostname = args[1];
                }
                PairDevice pairDevice = new PairDevice();
                pairDevice.name = hostname;
                pairDevice.guid = fingerprints;
                pairDevice.connected = mWifiConnectedKeys.contains(keyEntry.getKey());
                pairedDevices.put(keyEntry.getKey(), pairDevice);
            }
            return pairedDevices;
        }

        public String findKeyFromFingerprint(String fingerprint) {
            for (Map.Entry<String, Long> entry : mKeyMap.entrySet()) {
                String f = getFingerprints(entry.getKey());
                if (fingerprint.equals(f)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        public void removeKey(String key) {
            if (mKeyMap.containsKey(key)) {
                mKeyMap.remove(key);
                sendPersistKeyStoreMessage();
            }
        }

        /**
         * Initializes the key file that will be used to persist the adb grants.
         */
        private void initKeyFile() {
            // mTempKeysFile can be null if the adb file cannot be obtained
            if (mTempKeysFile != null) {
                mAtomicKeyFile = new AtomicFile(mTempKeysFile);
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
         * Update the key map and the trusted networks list with values parsed from the temp keys
         * file.
         */
        private void readTempKeysFile() {
            mKeyMap.clear();
            mTrustedNetworks.clear();
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(
                            TAG,
                            "Unable to obtain the key file, " + mTempKeysFile + ", for reading");
                    return;
                }
            }
            if (!mAtomicKeyFile.exists()) {
                return;
            }
            try (FileInputStream keyStream = mAtomicKeyFile.openRead()) {
                TypedXmlPullParser parser;
                try {
                    parser = Xml.resolvePullParser(keyStream);
                    XmlUtils.beginDocument(parser, XML_KEYSTORE_START_TAG);

                    int keystoreVersion = parser.getAttributeInt(null, XML_ATTRIBUTE_VERSION);
                    if (keystoreVersion > MAX_SUPPORTED_KEYSTORE_VERSION) {
                        Slog.e(TAG, "Keystore version=" + keystoreVersion
                                + " not supported (max_supported="
                                + MAX_SUPPORTED_KEYSTORE_VERSION + ")");
                        return;
                    }
                } catch (XmlPullParserException e) {
                    // This could be because the XML document doesn't start with
                    // XML_KEYSTORE_START_TAG. Try again, instead just starting the document with
                    // the adbKey tag (the old format).
                    parser = Xml.resolvePullParser(keyStream);
                }
                readKeyStoreContents(parser);
            } catch (IOException e) {
                Slog.e(TAG, "Caught an IOException parsing the XML key file: ", e);
            } catch (XmlPullParserException e) {
                Slog.e(TAG, "Caught XmlPullParserException parsing the XML key file: ", e);
            }
        }

        private void readKeyStoreContents(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            // This parser is very forgiving. For backwards-compatibility, we simply iterate through
            // all the tags in the file, skipping over anything that's not an <adbKey> tag or a
            // <wifiAP> tag. Invalid tags (such as ones that don't have a valid "lastConnection"
            // attribute) are simply ignored.
            while ((parser.next()) != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (XML_TAG_ADB_KEY.equals(tagName)) {
                    addAdbKeyToKeyMap(parser);
                } else if (XML_TAG_WIFI_ACCESS_POINT.equals(tagName)) {
                    addTrustedNetworkToTrustedNetworks(parser);
                } else {
                    Slog.w(TAG, "Ignoring tag '" + tagName + "'. Not recognized.");
                }
                XmlUtils.skipCurrentTag(parser);
            }
        }

        private void addAdbKeyToKeyMap(TypedXmlPullParser parser) {
            String key = parser.getAttributeValue(null, XML_ATTRIBUTE_KEY);
            try {
                long connectionTime =
                        parser.getAttributeLong(null, XML_ATTRIBUTE_LAST_CONNECTION);
                mKeyMap.put(key, connectionTime);
            } catch (XmlPullParserException e) {
                Slog.e(TAG, "Error reading adbKey attributes", e);
            }
        }

        private void addTrustedNetworkToTrustedNetworks(TypedXmlPullParser parser) {
            String bssid = parser.getAttributeValue(null, XML_ATTRIBUTE_WIFI_BSSID);
            mTrustedNetworks.add(bssid);
        }

        /**
         * Updates the keystore with keys that were previously set to be always allowed before the
         * connection time of keys was tracked.
         */
        private void addExistingUserKeysToKeyStore() {
            if (mUserKeyFile == null || !mUserKeyFile.exists()) {
                return;
            }
            boolean mapUpdated = false;
            try (BufferedReader in = new BufferedReader(new FileReader(mUserKeyFile))) {
                String key;
                while ((key = in.readLine()) != null) {
                    // if the keystore does not contain the key from the user key file then add
                    // it to the Map with the current system time to prevent it from expiring
                    // immediately if the user is actively using this key.
                    if (!mKeyMap.containsKey(key)) {
                        mKeyMap.put(key, mTicker.currentTimeMillis());
                        mapUpdated = true;
                    }
                }
            } catch (IOException e) {
                Slog.e(TAG, "Caught an exception reading " + mUserKeyFile + ": " + e);
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
            if (mKeyMap.isEmpty() && mTrustedNetworks.isEmpty()) {
                deleteKeyStore();
                return;
            }
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(
                            TAG,
                            "Unable to obtain the key file, " + mTempKeysFile + ", for writing");
                    return;
                }
            }
            FileOutputStream keyStream = null;
            try {
                keyStream = mAtomicKeyFile.startWrite();
                TypedXmlSerializer serializer = Xml.resolveSerializer(keyStream);
                serializer.startDocument(null, true);

                serializer.startTag(null, XML_KEYSTORE_START_TAG);
                serializer.attributeInt(null, XML_ATTRIBUTE_VERSION, KEYSTORE_VERSION);
                for (Map.Entry<String, Long> keyEntry : mKeyMap.entrySet()) {
                    serializer.startTag(null, XML_TAG_ADB_KEY);
                    serializer.attribute(null, XML_ATTRIBUTE_KEY, keyEntry.getKey());
                    serializer.attributeLong(null, XML_ATTRIBUTE_LAST_CONNECTION,
                            keyEntry.getValue());
                    serializer.endTag(null, XML_TAG_ADB_KEY);
                }
                for (String bssid : mTrustedNetworks) {
                    serializer.startTag(null, XML_TAG_WIFI_ACCESS_POINT);
                    serializer.attribute(null, XML_ATTRIBUTE_WIFI_BSSID, bssid);
                    serializer.endTag(null, XML_TAG_WIFI_ACCESS_POINT);
                }
                serializer.endTag(null, XML_KEYSTORE_START_TAG);
                serializer.endDocument();
                mAtomicKeyFile.finishWrite(keyStream);
            } catch (IOException e) {
                Slog.e(TAG, "Caught an exception writing the key map: ", e);
                mAtomicKeyFile.failWrite(keyStream);
            }
            writeKeys(mKeyMap.keySet());
        }

        private boolean filterOutOldKeys() {
            long allowedTime = getAllowedConnectionTime();
            if (allowedTime == 0) {
                return false;
            }
            boolean keysDeleted = false;
            long systemTime = mTicker.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> keyMapIterator = mKeyMap.entrySet().iterator();
            while (keyMapIterator.hasNext()) {
                Map.Entry<String, Long> keyEntry = keyMapIterator.next();
                long connectionTime = keyEntry.getValue();
                if (systemTime > (connectionTime + allowedTime)) {
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
            long systemTime = mTicker.currentTimeMillis();
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
            mTrustedNetworks.clear();
            if (mUserKeyFile != null) {
                mUserKeyFile.delete();
            }
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
        @VisibleForTesting
        void setLastConnectionTime(String key, long connectionTime, boolean force) {
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
            return allowedConnectionTime == 0
                    || (mTicker.currentTimeMillis() < (lastConnectionTime + allowedConnectionTime));
        }

        /**
         * Returns whether the specified bssid is in the list of trusted networks. This requires
         * that the user previously allowed wireless debugging on this network and selected the
         * option to 'Always allow'.
         */
        public boolean isTrustedNetwork(String bssid) {
            return mTrustedNetworks.contains(bssid);
        }
    }

    /**
     * A Guava-like interface for getting the current system time.
     *
     * This allows us to swap a fake ticker in for testing to reduce "Thread.sleep()" calls and test
     * for exact expected times instead of random ones.
     */
    @VisibleForTesting
    interface Ticker {
        long currentTimeMillis();
    }
}
