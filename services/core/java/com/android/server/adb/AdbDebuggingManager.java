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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.debug.AdbProtoEnums;
import android.debug.AdbTransportType;
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
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.adb.AdbDebuggingManagerProto;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FrameworkStatsLog;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides communication to the Android Debug Bridge daemon to allow, deny, or clear public keysi
 * that are authorized to connect to the ADB service itself.
 */
public class AdbDebuggingManager {
    private static final String TAG = "AdbDebuggingManager";
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

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private AdbDebuggingThread mThread;
    private boolean mAdbUsbEnabled = false;
    private boolean mAdbWifiEnabled = false;
    private String mFingerprints;
    // A key can be used more than once (e.g. USB, wifi), so need to keep a refcount
    private final Map<String, Integer> mConnectedKeys;
    private String mConfirmComponent;
    private final File mTestUserKeyFile;

    private static final String WIFI_PERSISTENT_CONFIG_PROPERTY =
            "persist.adb.tls_server.enable";
    private static final String WIFI_PERSISTENT_GUID =
            "persist.adb.wifi.guid";
    private static final int PAIRING_CODE_LENGTH = 6;
    private PairingThread mPairingThread = null;
    // A list of keys connected via wifi
    private final Set<String> mWifiConnectedKeys;
    // The current info of the adbwifi connection.
    private AdbConnectionInfo mAdbConnectionInfo;
    // Polls for a tls port property when adb wifi is enabled
    private AdbConnectionPortPoller mConnectionPortPoller;
    private final PortListenerImpl mPortListener = new PortListenerImpl();

    public AdbDebuggingManager(Context context) {
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mTestUserKeyFile = null;
        mConnectedKeys = new HashMap<String, Integer>();
        mWifiConnectedKeys = new HashSet<String>();
        mAdbConnectionInfo = new AdbConnectionInfo();
    }

    /**
     * Constructor that accepts the component to be invoked to confirm if the user wants to allow
     * an adb connection from the key.
     */
    @TestApi
    protected AdbDebuggingManager(Context context, String confirmComponent, File testUserKeyFile) {
        mHandler = new AdbDebuggingHandler(FgThread.get().getLooper());
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mConfirmComponent = confirmComponent;
        mTestUserKeyFile = testUserKeyFile;
        mConnectedKeys = new HashMap<String, Integer>();
        mWifiConnectedKeys = new HashSet<String>();
        mAdbConnectionInfo = new AdbConnectionInfo();
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
        @VisibleForTesting
        static final String SERVICE_PROTOCOL = "adb-tls-pairing";
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
            if (mGuid.isEmpty()) {
                Slog.e(TAG, "adbwifi guid was not set");
                return;
            }
            mPort = native_pairing_start(mGuid, mPairingCode);
            if (mPort <= 0 || mPort > 65535) {
                Slog.e(TAG, "Unable to start pairing server");
                return;
            }

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
            Message msg = mHandler.obtainMessage(port > 0
                     ? AdbDebuggingHandler.MSG_SERVER_CONNECTED
                     : AdbDebuggingHandler.MSG_SERVER_DISCONNECTED);
            msg.obj = port;
            mHandler.sendMessage(msg);
        }
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

    class AdbConnectionInfo {
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
                            WifiManager.EXTRA_NETWORK_INFO);
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
                        }

                        // Check for network change
                        String bssid = wifiInfo.getBSSID();
                        if (bssid == null || bssid.isEmpty()) {
                            Slog.e(TAG, "Unable to get the wifi ap's BSSID. Disabling adbwifi.");
                            Settings.Global.putInt(mContentResolver,
                                    Settings.Global.ADB_WIFI_ENABLED, 0);
                        }
                        synchronized (mAdbConnectionInfo) {
                            if (!bssid.equals(mAdbConnectionInfo.getBSSID())) {
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

        // === Messages we can send to adbd ===========
        static final String MSG_DISCONNECT_DEVICE = "DD";
        static final String MSG_DISABLE_ADBDWIFI = "DA";

        private AdbKeyStore mAdbKeyStore;

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

        // Show when at least one device is connected.
        public void showAdbConnectedNotification(boolean show) {
            final int id = SystemMessage.NOTE_ADB_WIFI_ACTIVE;
            final int titleRes = com.android.internal.R.string.adbwifi_active_notification_title;
            if (show == mAdbNotificationShown) {
                return;
            }
            setupNotifications();
            if (!mAdbNotificationShown) {
                Resources r = mContext.getResources();
                CharSequence title = r.getText(titleRes);
                CharSequence message = r.getText(
                        com.android.internal.R.string.adbwifi_active_notification_message);

                Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0,
                        intent, 0, null, UserHandle.CURRENT);

                Notification notification =
                        new Notification.Builder(mContext, SystemNotificationChannels.DEVELOPER)
                                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                                .setWhen(0)
                                .setOngoing(true)
                                .setTicker(title)
                                .setDefaults(0)  // please be quiet
                                .setColor(mContext.getColor(
                                        com.android.internal.R.color
                                                .system_notification_accent_color))
                                .setContentTitle(title)
                                .setContentText(message)
                                .setContentIntent(pi)
                                .setVisibility(Notification.VISIBILITY_PUBLIC)
                                .extend(new Notification.TvExtender()
                                        .setChannelId(ADB_NOTIFICATION_CHANNEL_ID_TV))
                                .build();
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
                    mAdbKeyStore.setLastConnectionTime(entry.getKey(),
                            System.currentTimeMillis());
                }
                sendPersistKeyStoreMessage();
                mConnectedKeys.clear();
                mWifiConnectedKeys.clear();
            }
            scheduleJobToUpdateAdbKeyStore();
        }

        public void handleMessage(Message msg) {
            if (mAdbKeyStore == null) {
                mAdbKeyStore = new AdbKeyStore();
            }

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
                    startConfirmationForKey(key, mFingerprints);
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
                    mWifiConnectedKeys.clear();
                    mAdbKeyStore.deleteKeyStore();
                    cancelJobToUpdateAdbKeyStore();
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
                                mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
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
                        if (!mConnectedKeys.containsKey(key)) {
                            mConnectedKeys.put(key, 1);
                        } else {
                            mConnectedKeys.put(key, mConnectedKeys.get(key) + 1);
                        }
                        mAdbKeyStore.setLastConnectionTime(key, System.currentTimeMillis());
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
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
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
            if (bssid == null || bssid.isEmpty()) {
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
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
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
                PairDevice device = new PairDevice(fingerprints, hostname, false);
                intent.putExtra(AdbManager.WIRELESS_PAIR_DEVICE_EXTRA, device);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                // Add the key into the keystore
                mAdbKeyStore.setLastConnectionTime(publicKey,
                        System.currentTimeMillis());
                sendPersistKeyStoreMessage();
                scheduleJobToUpdateAdbKeyStore();
            }
        }

        private void sendPairingPortToUI(int port) {
            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
            intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA,
                    AdbManager.WIRELESS_STATUS_CONNECTED);
            intent.putExtra(AdbManager.WIRELESS_DEBUG_PORT_EXTRA, port);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void sendPairedDevicesToUI(Map<String, PairDevice> devices) {
            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRED_DEVICES_ACTION);
            // Map is not serializable, so need to downcast
            intent.putExtra(AdbManager.WIRELESS_DEVICES_EXTRA, (HashMap) devices);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void updateUIPairCode(String code) {
            if (DEBUG) Slog.i(TAG, "updateUIPairCode: " + code);

            Intent intent = new Intent(AdbManager.WIRELESS_DEBUG_PAIRING_RESULT_ACTION);
            intent.putExtra(AdbManager.WIRELESS_PAIRING_CODE_EXTRA, code);
            intent.putExtra(AdbManager.WIRELESS_STATUS_EXTRA,
                    AdbManager.WIRELESS_STATUS_PAIRING_CODE);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
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
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(currentUserId);
        String componentString;
        if (userInfo.isAdmin()) {
            componentString = Resources.getSystem().getString(
                    com.android.internal.R.string.config_customAdbWifiNetworkConfirmationComponent);
        } else {
            componentString = Resources.getSystem().getString(
                    com.android.internal.R.string.config_customAdbWifiNetworkConfirmationComponent);
        }
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        if (startConfirmationActivity(componentName, userInfo.getUserHandle(), extras)
                || startConfirmationService(componentName, userInfo.getUserHandle(),
                        extras)) {
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

        private List<String> mTrustedNetworks;
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
            mTrustedNetworks = getTrustedNetworks();
            mSystemKeys = getSystemKeysFromFile(SYSTEM_KEY_FILE);
            addUserKeysToKeyStore();
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
                pairedDevices.put(keyEntry.getKey(), new PairDevice(
                        hostname, fingerprints, mWifiConnectedKeys.contains(keyEntry.getKey())));
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
                writeKeys(mKeyMap.keySet());
                sendPersistKeyStoreMessage();
            }
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
                // Check for supported keystore version.
                XmlUtils.beginDocument(parser, XML_KEYSTORE_START_TAG);
                if (parser.next() != XmlPullParser.END_DOCUMENT) {
                    String tagName = parser.getName();
                    if (tagName == null || !XML_KEYSTORE_START_TAG.equals(tagName)) {
                        Slog.e(TAG, "Expected " + XML_KEYSTORE_START_TAG + ", but got tag="
                                + tagName);
                        return keyMap;
                    }
                    int keystoreVersion = Integer.parseInt(
                            parser.getAttributeValue(null, XML_ATTRIBUTE_VERSION));
                    if (keystoreVersion > MAX_SUPPORTED_KEYSTORE_VERSION) {
                        Slog.e(TAG, "Keystore version=" + keystoreVersion
                                + " not supported (max_supported="
                                + MAX_SUPPORTED_KEYSTORE_VERSION + ")");
                        return keyMap;
                    }
                }
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
            } catch (IOException e) {
                Slog.e(TAG, "Caught an IOException parsing the XML key file: ", e);
            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Caught XmlPullParserException parsing the XML key file: ", e);
                // The file could be written in a format prior to introducing keystore tag.
                return getKeyMapBeforeKeystoreVersion();
            }
            return keyMap;
        }


        /**
         * Returns the key map with the keys and last connection times from the key file.
         * This implementation was prior to adding the XML_KEYSTORE_START_TAG.
         */
        private Map<String, Long> getKeyMapBeforeKeystoreVersion() {
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
         * Returns the map of trusted networks from the keystore file.
         *
         * This was implemented in keystore version 1.
         */
        private List<String> getTrustedNetworks() {
            List<String> trustedNetworks = new ArrayList<String>();
            // if the AtomicFile could not be instantiated before attempt again; if it still fails
            // return an empty key map.
            if (mAtomicKeyFile == null) {
                initKeyFile();
                if (mAtomicKeyFile == null) {
                    Slog.e(TAG, "Unable to obtain the key file, " + mKeyFile + ", for reading");
                    return trustedNetworks;
                }
            }
            if (!mAtomicKeyFile.exists()) {
                return trustedNetworks;
            }
            try (FileInputStream keyStream = mAtomicKeyFile.openRead()) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(keyStream, StandardCharsets.UTF_8.name());
                // Check for supported keystore version.
                XmlUtils.beginDocument(parser, XML_KEYSTORE_START_TAG);
                if (parser.next() != XmlPullParser.END_DOCUMENT) {
                    String tagName = parser.getName();
                    if (tagName == null || !XML_KEYSTORE_START_TAG.equals(tagName)) {
                        Slog.e(TAG, "Expected " + XML_KEYSTORE_START_TAG + ", but got tag="
                                + tagName);
                        return trustedNetworks;
                    }
                    int keystoreVersion = Integer.parseInt(
                            parser.getAttributeValue(null, XML_ATTRIBUTE_VERSION));
                    if (keystoreVersion > MAX_SUPPORTED_KEYSTORE_VERSION) {
                        Slog.e(TAG, "Keystore version=" + keystoreVersion
                                + " not supported (max_supported="
                                + MAX_SUPPORTED_KEYSTORE_VERSION);
                        return trustedNetworks;
                    }
                }
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    String tagName = parser.getName();
                    if (tagName == null) {
                        break;
                    } else if (!tagName.equals(XML_TAG_WIFI_ACCESS_POINT)) {
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String bssid = parser.getAttributeValue(null, XML_ATTRIBUTE_WIFI_BSSID);
                    trustedNetworks.add(bssid);
                }
            } catch (IOException | XmlPullParserException | NumberFormatException e) {
                Slog.e(TAG, "Caught an exception parsing the XML key file: ", e);
            }
            return trustedNetworks;
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
            if (mKeyMap.isEmpty() && mTrustedNetworks.isEmpty()) {
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

                serializer.startTag(null, XML_KEYSTORE_START_TAG);
                serializer.attribute(null, XML_ATTRIBUTE_VERSION, String.valueOf(KEYSTORE_VERSION));
                for (Map.Entry<String, Long> keyEntry : mKeyMap.entrySet()) {
                    serializer.startTag(null, XML_TAG_ADB_KEY);
                    serializer.attribute(null, XML_ATTRIBUTE_KEY, keyEntry.getKey());
                    serializer.attribute(null, XML_ATTRIBUTE_LAST_CONNECTION,
                            String.valueOf(keyEntry.getValue()));
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
            mTrustedNetworks.clear();
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

        /**
         * Returns whether the specified bssid is in the list of trusted networks. This requires
         * that the user previously allowed wireless debugging on this network and selected the
         * option to 'Always allow'.
         */
        public boolean isTrustedNetwork(String bssid) {
            return mTrustedNetworks.contains(bssid);
        }
    }
}
