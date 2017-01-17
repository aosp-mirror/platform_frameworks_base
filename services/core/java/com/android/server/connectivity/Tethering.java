/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.hardware.usb.UsbManager.USB_CONNECTED;
import static android.hardware.usb.UsbManager.USB_FUNCTION_RNDIS;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.connectivity.tethering.IControlsTethering;
import com.android.server.connectivity.tethering.IPv6TetheringCoordinator;
import com.android.server.connectivity.tethering.IPv6TetheringInterfaceServices;
import com.android.server.connectivity.tethering.TetherInterfaceStateMachine;
import com.android.server.connectivity.tethering.UpstreamNetworkMonitor;
import com.android.server.net.BaseNetworkObserver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * @hide
 *
 * This class holds much of the business logic to allow Android devices
 * to act as IP gateways via USB, BT, and WiFi interfaces.
 */
public class Tethering extends BaseNetworkObserver implements IControlsTethering {

    private final Context mContext;
    private final static String TAG = "Tethering";
    private final static boolean DBG = false;
    private final static boolean VDBG = false;

    protected static final String DISABLE_PROVISIONING_SYSPROP_KEY = "net.tethering.noprovisioning";

    private static final Class[] messageClasses = {
            Tethering.class, TetherMasterSM.class, TetherInterfaceStateMachine.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(messageClasses);

    private volatile TetheringConfiguration mConfig;

    // used to synchronize public access to members
    private final Object mPublicSync;

    private static final Integer MOBILE_TYPE = new Integer(ConnectivityManager.TYPE_MOBILE);
    private static final Integer HIPRI_TYPE = new Integer(ConnectivityManager.TYPE_MOBILE_HIPRI);
    private static final Integer DUN_TYPE = new Integer(ConnectivityManager.TYPE_MOBILE_DUN);

    private final INetworkManagementService mNMService;
    private final INetworkStatsService mStatsService;
    private final INetworkPolicyManager mPolicyManager;
    private final Looper mLooper;
    private final MockableSystemProperties mSystemProperties;

    private static class TetherState {
        public final TetherInterfaceStateMachine mStateMachine;
        public int mLastState;
        public int mLastError;
        public TetherState(TetherInterfaceStateMachine sm) {
            mStateMachine = sm;
            // Assume all state machines start out available and with no errors.
            mLastState = IControlsTethering.STATE_AVAILABLE;
            mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
        }
    }
    private final ArrayMap<String, TetherState> mTetherStates;

    private final BroadcastReceiver mStateReceiver;

    // {@link ComponentName} of the Service used to run tether provisioning.
    private static final ComponentName TETHER_SERVICE = ComponentName.unflattenFromString(Resources
            .getSystem().getString(com.android.internal.R.string.config_wifi_tether_enable));

    private final StateMachine mTetherMasterSM;
    private final UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    private String mCurrentUpstreamIface;

    private Notification.Builder mTetheredNotificationBuilder;
    private int mLastNotificationId;

    private boolean mRndisEnabled;       // track the RNDIS function enabled state
    private boolean mUsbTetherRequested; // true if USB tethering should be started
                                         // when RNDIS is enabled

    // True iff WiFi tethering should be started when soft AP is ready.
    private boolean mWifiTetherRequested;

    public Tethering(Context context, INetworkManagementService nmService,
            INetworkStatsService statsService, INetworkPolicyManager policyManager,
            Looper looper, MockableSystemProperties systemProperties) {
        mContext = context;
        mNMService = nmService;
        mStatsService = statsService;
        mPolicyManager = policyManager;
        mLooper = looper;
        mSystemProperties = systemProperties;

        mPublicSync = new Object();

        mTetherStates = new ArrayMap<>();

        mTetherMasterSM = new TetherMasterSM("TetherMaster", mLooper);
        mTetherMasterSM.start();

        mUpstreamNetworkMonitor = new UpstreamNetworkMonitor(
                mContext, mTetherMasterSM, TetherMasterSM.EVENT_UPSTREAM_CALLBACK);

        mStateReceiver = new StateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_STATE);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiver(mStateReceiver, filter, null, mTetherMasterSM.getHandler());

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        filter.addDataScheme("file");
        mContext.registerReceiver(mStateReceiver, filter, null, mTetherMasterSM.getHandler());

        // load device config info
        updateConfiguration();
    }

    // We can't do this once in the Tethering() constructor and cache the value, because the
    // CONNECTIVITY_SERVICE is registered only after the Tethering() constructor has completed.
    private ConnectivityManager getConnectivityManager() {
        return (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void updateConfiguration() {
        mConfig = new TetheringConfiguration(mContext);
    }

    @Override
    public void interfaceStatusChanged(String iface, boolean up) {
        // Never called directly: only called from interfaceLinkStateChanged.
        // See NetlinkHandler.cpp:71.
        if (VDBG) Log.d(TAG, "interfaceStatusChanged " + iface + ", " + up);
        synchronized (mPublicSync) {
            int interfaceType = ifaceNameToType(iface);
            if (interfaceType == ConnectivityManager.TETHERING_INVALID) {
                return;
            }

            TetherState tetherState = mTetherStates.get(iface);
            if (up) {
                if (tetherState == null) {
                    trackNewTetherableInterface(iface, interfaceType);
                }
            } else {
                if (interfaceType == ConnectivityManager.TETHERING_BLUETOOTH) {
                    tetherState.mStateMachine.sendMessage(
                            TetherInterfaceStateMachine.CMD_INTERFACE_DOWN);
                    mTetherStates.remove(iface);
                } else {
                    // Ignore usb0 down after enabling RNDIS.
                    // We will handle disconnect in interfaceRemoved.
                    // Similarly, ignore interface down for WiFi.  We monitor WiFi AP status
                    // through the WifiManager.WIFI_AP_STATE_CHANGED_ACTION intent.
                    if (VDBG) Log.d(TAG, "ignore interface down for " + iface);
                }
            }
        }
    }

    @Override
    public void interfaceLinkStateChanged(String iface, boolean up) {
        interfaceStatusChanged(iface, up);
    }

    private int ifaceNameToType(String iface) {
        final TetheringConfiguration cfg = mConfig;

        if (cfg.isWifi(iface)) {
            return ConnectivityManager.TETHERING_WIFI;
        } else if (cfg.isUsb(iface)) {
            return ConnectivityManager.TETHERING_USB;
        } else if (cfg.isBluetooth(iface)) {
            return ConnectivityManager.TETHERING_BLUETOOTH;
        }
        return ConnectivityManager.TETHERING_INVALID;
    }

    @Override
    public void interfaceAdded(String iface) {
        if (VDBG) Log.d(TAG, "interfaceAdded " + iface);
        synchronized (mPublicSync) {
            int interfaceType = ifaceNameToType(iface);
            if (interfaceType == ConnectivityManager.TETHERING_INVALID) {
                if (VDBG) Log.d(TAG, iface + " is not a tetherable iface, ignoring");
                return;
            }

            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                trackNewTetherableInterface(iface, interfaceType);
            } else {
                if (VDBG) Log.d(TAG, "active iface (" + iface + ") reported as added, ignoring");
            }
        }
    }

    @Override
    public void interfaceRemoved(String iface) {
        if (VDBG) Log.d(TAG, "interfaceRemoved " + iface);
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                if (VDBG) {
                    Log.e(TAG, "attempting to remove unknown iface (" + iface + "), ignoring");
                }
                return;
            }
            tetherState.mStateMachine.sendMessage(TetherInterfaceStateMachine.CMD_INTERFACE_DOWN);
            mTetherStates.remove(iface);
        }
    }

    public void startTethering(int type, ResultReceiver receiver,
            boolean showProvisioningUi) {
        if (!isTetherProvisioningRequired()) {
            enableTetheringInternal(type, true, receiver);
            return;
        }

        if (showProvisioningUi) {
            runUiTetherProvisioningAndEnable(type, receiver);
        } else {
            runSilentTetherProvisioningAndEnable(type, receiver);
        }
    }

    public void stopTethering(int type) {
        enableTetheringInternal(type, false, null);
        if (isTetherProvisioningRequired()) {
            cancelTetherProvisioningRechecks(type);
        }
    }

    /**
     * Check if the device requires a provisioning check in order to enable tethering.
     *
     * @return a boolean - {@code true} indicating tether provisioning is required by the carrier.
     */
    @VisibleForTesting
    protected boolean isTetherProvisioningRequired() {
        String[] provisionApp = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
        if (mSystemProperties.getBoolean(DISABLE_PROVISIONING_SYSPROP_KEY, false)
                || provisionApp == null) {
            return false;
        }

        // Check carrier config for entitlement checks
        final CarrierConfigManager configManager = (CarrierConfigManager) mContext
             .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null && configManager.getConfig() != null) {
            // we do have a CarrierConfigManager and it has a config.
            boolean isEntitlementCheckRequired = configManager.getConfig().getBoolean(
                    CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL);
            if (!isEntitlementCheckRequired) {
                return false;
            }
        }
        return (provisionApp.length == 2);
    }

    /**
     * Enables or disables tethering for the given type. This should only be called once
     * provisioning has succeeded or is not necessary. It will also schedule provisioning rechecks
     * for the specified interface.
     */
    private void enableTetheringInternal(int type, boolean enable, ResultReceiver receiver) {
        boolean isProvisioningRequired = enable && isTetherProvisioningRequired();
        int result;
        switch (type) {
            case ConnectivityManager.TETHERING_WIFI:
                result = setWifiTethering(enable);
                if (isProvisioningRequired && result == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    scheduleProvisioningRechecks(type);
                }
                sendTetherResult(receiver, result);
                break;
            case ConnectivityManager.TETHERING_USB:
                result = setUsbTethering(enable);
                if (isProvisioningRequired && result == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    scheduleProvisioningRechecks(type);
                }
                sendTetherResult(receiver, result);
                break;
            case ConnectivityManager.TETHERING_BLUETOOTH:
                setBluetoothTethering(enable, receiver);
                break;
            default:
                Log.w(TAG, "Invalid tether type.");
                sendTetherResult(receiver, ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE);
        }
    }

    private void sendTetherResult(ResultReceiver receiver, int result) {
        if (receiver != null) {
            receiver.send(result, null);
        }
    }

    private int setWifiTethering(final boolean enable) {
        synchronized (mPublicSync) {
            mWifiTetherRequested = enable;
            final WifiManager wifiManager =
                    (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager.setWifiApEnabled(null /* use existing wifi config */, enable)) {
                return ConnectivityManager.TETHER_ERROR_NO_ERROR;
            }
            return ConnectivityManager.TETHER_ERROR_MASTER_ERROR;
        }
    }

    private void setBluetoothTethering(final boolean enable, final ResultReceiver receiver) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Tried to enable bluetooth tethering with null or disabled adapter. null: " +
                    (adapter == null));
            sendTetherResult(receiver, ConnectivityManager.TETHER_ERROR_SERVICE_UNAVAIL);
            return;
        }

        adapter.getProfileProxy(mContext, new ServiceListener() {
            @Override
            public void onServiceDisconnected(int profile) { }

            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                ((BluetoothPan) proxy).setBluetoothTethering(enable);
                // TODO: Enabling bluetooth tethering can fail asynchronously here.
                // We should figure out a way to bubble up that failure instead of sending success.
                int result = ((BluetoothPan) proxy).isTetheringOn() == enable ?
                        ConnectivityManager.TETHER_ERROR_NO_ERROR :
                        ConnectivityManager.TETHER_ERROR_MASTER_ERROR;
                sendTetherResult(receiver, result);
                if (enable && isTetherProvisioningRequired()) {
                    scheduleProvisioningRechecks(ConnectivityManager.TETHERING_BLUETOOTH);
                }
                adapter.closeProfileProxy(BluetoothProfile.PAN, proxy);
            }
        }, BluetoothProfile.PAN);
    }

    private void runUiTetherProvisioningAndEnable(int type, ResultReceiver receiver) {
        ResultReceiver proxyReceiver = getProxyReceiver(type, receiver);
        sendUiTetherProvisionIntent(type, proxyReceiver);
    }

    private void sendUiTetherProvisionIntent(int type, ResultReceiver receiver) {
        Intent intent = new Intent(Settings.ACTION_TETHER_PROVISIONING);
        intent.putExtra(ConnectivityManager.EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(ConnectivityManager.EXTRA_PROVISION_CALLBACK, receiver);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Creates a proxy {@link ResultReceiver} which enables tethering if the provisioning result
     * is successful before firing back up to the wrapped receiver.
     *
     * @param type The type of tethering being enabled.
     * @param receiver A ResultReceiver which will be called back with an int resultCode.
     * @return The proxy receiver.
     */
    private ResultReceiver getProxyReceiver(final int type, final ResultReceiver receiver) {
        ResultReceiver rr = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                // If provisioning is successful, enable tethering, otherwise just send the error.
                if (resultCode == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    enableTetheringInternal(type, true, receiver);
                } else {
                    sendTetherResult(receiver, resultCode);
                }
            }
        };

        // The following is necessary to avoid unmarshalling issues when sending the receiver
        // across processes.
        Parcel parcel = Parcel.obtain();
        rr.writeToParcel(parcel,0);
        parcel.setDataPosition(0);
        ResultReceiver receiverForSending = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return receiverForSending;
    }

    private void scheduleProvisioningRechecks(int type) {
        Intent intent = new Intent();
        intent.putExtra(ConnectivityManager.EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(ConnectivityManager.EXTRA_SET_ALARM, true);
        intent.setComponent(TETHER_SERVICE);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void runSilentTetherProvisioningAndEnable(int type, ResultReceiver receiver) {
        ResultReceiver proxyReceiver = getProxyReceiver(type, receiver);
        sendSilentTetherProvisionIntent(type, proxyReceiver);
    }

    private void sendSilentTetherProvisionIntent(int type, ResultReceiver receiver) {
        Intent intent = new Intent();
        intent.putExtra(ConnectivityManager.EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(ConnectivityManager.EXTRA_RUN_PROVISION, true);
        intent.putExtra(ConnectivityManager.EXTRA_PROVISION_CALLBACK, receiver);
        intent.setComponent(TETHER_SERVICE);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void cancelTetherProvisioningRechecks(int type) {
        if (getConnectivityManager().isTetheringSupported()) {
            Intent intent = new Intent();
            intent.putExtra(ConnectivityManager.EXTRA_REM_TETHER_TYPE, type);
            intent.setComponent(TETHER_SERVICE);
            final long ident = Binder.clearCallingIdentity();
            try {
                mContext.startServiceAsUser(intent, UserHandle.CURRENT);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public int tether(String iface) {
        if (DBG) Log.d(TAG, "Tethering " + iface);
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                Log.e(TAG, "Tried to Tether an unknown iface: " + iface + ", ignoring");
                return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
            }
            // Ignore the error status of the interface.  If the interface is available,
            // the errors are referring to past tethering attempts anyway.
            if (tetherState.mLastState != IControlsTethering.STATE_AVAILABLE) {
                Log.e(TAG, "Tried to Tether an unavailable iface: " + iface + ", ignoring");
                return ConnectivityManager.TETHER_ERROR_UNAVAIL_IFACE;
            }
            tetherState.mStateMachine.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_REQUESTED);
            return ConnectivityManager.TETHER_ERROR_NO_ERROR;
        }
    }

    public int untether(String iface) {
        if (DBG) Log.d(TAG, "Untethering " + iface);
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                Log.e(TAG, "Tried to Untether an unknown iface :" + iface + ", ignoring");
                return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
            }
            if (tetherState.mLastState != IControlsTethering.STATE_TETHERED) {
                Log.e(TAG, "Tried to untether an untethered iface :" + iface + ", ignoring");
                return ConnectivityManager.TETHER_ERROR_UNAVAIL_IFACE;
            }
            tetherState.mStateMachine.sendMessage(
                    TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
            return ConnectivityManager.TETHER_ERROR_NO_ERROR;
        }
    }

    public void untetherAll() {
        stopTethering(ConnectivityManager.TETHERING_WIFI);
        stopTethering(ConnectivityManager.TETHERING_USB);
        stopTethering(ConnectivityManager.TETHERING_BLUETOOTH);
    }

    public int getLastTetherError(String iface) {
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                Log.e(TAG, "Tried to getLastTetherError on an unknown iface :" + iface +
                        ", ignoring");
                return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
            }
            return tetherState.mLastError;
        }
    }

    private void sendTetherStateChangedBroadcast() {
        if (!getConnectivityManager().isTetheringSupported()) return;

        ArrayList<String> availableList = new ArrayList<String>();
        ArrayList<String> activeList = new ArrayList<String>();
        ArrayList<String> erroredList = new ArrayList<String>();

        boolean wifiTethered = false;
        boolean usbTethered = false;
        boolean bluetoothTethered = false;

        final TetheringConfiguration cfg = mConfig;

        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                String iface = mTetherStates.keyAt(i);
                if (tetherState.mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    erroredList.add(iface);
                } else if (tetherState.mLastState == IControlsTethering.STATE_AVAILABLE) {
                    availableList.add(iface);
                } else if (tetherState.mLastState == IControlsTethering.STATE_TETHERED) {
                    if (cfg.isUsb(iface)) {
                        usbTethered = true;
                    } else if (cfg.isWifi(iface)) {
                        wifiTethered = true;
                    } else if (cfg.isBluetooth(iface)) {
                        bluetoothTethered = true;
                    }
                    activeList.add(iface);
                }
            }
        }
        Intent broadcast = new Intent(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        broadcast.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        broadcast.putStringArrayListExtra(ConnectivityManager.EXTRA_AVAILABLE_TETHER,
                availableList);
        broadcast.putStringArrayListExtra(ConnectivityManager.EXTRA_ACTIVE_TETHER, activeList);
        broadcast.putStringArrayListExtra(ConnectivityManager.EXTRA_ERRORED_TETHER,
                erroredList);
        mContext.sendStickyBroadcastAsUser(broadcast, UserHandle.ALL);
        if (DBG) {
            Log.d(TAG, String.format(
                    "sendTetherStateChangedBroadcast avail=[%s] active=[%s] error=[%s]",
                    TextUtils.join(",", availableList),
                    TextUtils.join(",", activeList),
                    TextUtils.join(",", erroredList)));
        }

        if (usbTethered) {
            if (wifiTethered || bluetoothTethered) {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_general);
            } else {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_usb);
            }
        } else if (wifiTethered) {
            if (bluetoothTethered) {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_general);
            } else {
                /* We now have a status bar icon for WifiTethering, so drop the notification */
                clearTetheredNotification();
            }
        } else if (bluetoothTethered) {
            showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_bluetooth);
        } else {
            clearTetheredNotification();
        }
    }

    private void showTetheredNotification(int icon) {
        NotificationManager notificationManager =
                (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        if (mLastNotificationId != 0) {
            if (mLastNotificationId == icon) {
                return;
            }
            notificationManager.cancelAsUser(null, mLastNotificationId,
                    UserHandle.ALL);
            mLastNotificationId = 0;
        }

        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0, intent, 0,
                null, UserHandle.CURRENT);

        Resources r = Resources.getSystem();
        CharSequence title = r.getText(com.android.internal.R.string.tethered_notification_title);
        CharSequence message = r.getText(com.android.internal.R.string.
                tethered_notification_message);

        if (mTetheredNotificationBuilder == null) {
            mTetheredNotificationBuilder = new Notification.Builder(mContext);
            mTetheredNotificationBuilder.setWhen(0)
                    .setOngoing(true)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_STATUS);
        }
        mTetheredNotificationBuilder.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pi);
        mLastNotificationId = icon;

        notificationManager.notifyAsUser(null, mLastNotificationId,
                mTetheredNotificationBuilder.build(), UserHandle.ALL);
    }

    private void clearTetheredNotification() {
        NotificationManager notificationManager =
            (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && mLastNotificationId != 0) {
            notificationManager.cancelAsUser(null, mLastNotificationId,
                    UserHandle.ALL);
            mLastNotificationId = 0;
        }
    }

    private class StateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context content, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (action.equals(UsbManager.ACTION_USB_STATE)) {
                handleUsbAction(intent);
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                handleConnectivityAction(intent);
            } else if (action.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                handleWifiApAction(intent);
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                updateConfiguration();
            }
        }

        private void handleConnectivityAction(Intent intent) {
            final NetworkInfo networkInfo = (NetworkInfo)intent.getParcelableExtra(
                    ConnectivityManager.EXTRA_NETWORK_INFO);
            if (networkInfo == null ||
                    networkInfo.getDetailedState() == NetworkInfo.DetailedState.FAILED) {
                return;
            }

            if (VDBG) Log.d(TAG, "Tethering got CONNECTIVITY_ACTION: " + networkInfo.toString());
            mTetherMasterSM.sendMessage(TetherMasterSM.CMD_UPSTREAM_CHANGED);
        }

        private void handleUsbAction(Intent intent) {
            final boolean usbConnected = intent.getBooleanExtra(USB_CONNECTED, false);
            final boolean rndisEnabled = intent.getBooleanExtra(USB_FUNCTION_RNDIS, false);
            synchronized (Tethering.this.mPublicSync) {
                mRndisEnabled = rndisEnabled;
                // start tethering if we have a request pending
                if (usbConnected && mRndisEnabled && mUsbTetherRequested) {
                    tetherMatchingInterfaces(true, ConnectivityManager.TETHERING_USB);
                }
                mUsbTetherRequested = false;
            }
        }

        private void handleWifiApAction(Intent intent) {
            final int curState =  intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED);
            synchronized (Tethering.this.mPublicSync) {
                switch (curState) {
                    case WifiManager.WIFI_AP_STATE_ENABLING:
                        // We can see this state on the way to both enabled and failure states.
                        break;
                    case WifiManager.WIFI_AP_STATE_ENABLED:
                        // When the AP comes up and we've been requested to tether it, do so.
                        if (mWifiTetherRequested) {
                            tetherMatchingInterfaces(true, ConnectivityManager.TETHERING_WIFI);
                        }
                        break;
                    case WifiManager.WIFI_AP_STATE_DISABLED:
                    case WifiManager.WIFI_AP_STATE_DISABLING:
                    case WifiManager.WIFI_AP_STATE_FAILED:
                    default:
                        if (DBG) {
                            Log.d(TAG, "Canceling WiFi tethering request - AP_STATE=" +
                                curState);
                        }
                        // Tell appropriate interface state machines that they should tear
                        // themselves down.
                        for (int i = 0; i < mTetherStates.size(); i++) {
                            TetherInterfaceStateMachine tism =
                                    mTetherStates.valueAt(i).mStateMachine;
                            if (tism.interfaceType() == ConnectivityManager.TETHERING_WIFI) {
                                tism.sendMessage(
                                        TetherInterfaceStateMachine.CMD_TETHER_UNREQUESTED);
                                break;  // There should be at most one of these.
                            }
                        }
                        // Regardless of whether we requested this transition, the AP has gone
                        // down.  Don't try to tether again unless we're requested to do so.
                        mWifiTetherRequested = false;
                    break;
                }
            }
        }
    }

    private void tetherMatchingInterfaces(boolean enable, int interfaceType) {
        if (VDBG) Log.d(TAG, "tetherMatchingInterfaces(" + enable + ", " + interfaceType + ")");

        String[] ifaces = null;
        try {
            ifaces = mNMService.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces", e);
            return;
        }
        String chosenIface = null;
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (ifaceNameToType(iface) == interfaceType) {
                    chosenIface = iface;
                    break;
                }
            }
        }
        if (chosenIface == null) {
            Log.e(TAG, "could not find iface of type " + interfaceType);
            return;
        }

        int result = (enable ? tether(chosenIface) : untether(chosenIface));
        if (result != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            Log.e(TAG, "unable start or stop tethering on iface " + chosenIface);
            return;
        }
    }

    public TetheringConfiguration getTetheringConfiguration() {
        return mConfig;
    }

    // TODO - update callers to use getTetheringConfiguration(),
    // which has only final members.
    public String[] getTetherableUsbRegexs() {
        return copy(mConfig.tetherableUsbRegexs);
    }

    public String[] getTetherableWifiRegexs() {
        return copy(mConfig.tetherableWifiRegexs);
    }

    public String[] getTetherableBluetoothRegexs() {
        return copy(mConfig.tetherableBluetoothRegexs);
    }

    public int setUsbTethering(boolean enable) {
        if (VDBG) Log.d(TAG, "setUsbTethering(" + enable + ")");
        UsbManager usbManager = mContext.getSystemService(UsbManager.class);

        synchronized (mPublicSync) {
            if (enable) {
                if (mRndisEnabled) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        tetherMatchingInterfaces(true, ConnectivityManager.TETHERING_USB);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                } else {
                    mUsbTetherRequested = true;
                    usbManager.setCurrentFunction(UsbManager.USB_FUNCTION_RNDIS, false);
                }
            } else {
                final long ident = Binder.clearCallingIdentity();
                try {
                    tetherMatchingInterfaces(false, ConnectivityManager.TETHERING_USB);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                if (mRndisEnabled) {
                    usbManager.setCurrentFunction(null, false);
                }
                mUsbTetherRequested = false;
            }
        }
        return ConnectivityManager.TETHER_ERROR_NO_ERROR;
    }

    public int[] getUpstreamIfaceTypes() {
        updateConfiguration();  // TODO - remove?
        final Collection<Integer> upstreams = mConfig.preferredUpstreamIfaceTypes;
        final int[] values = new int[upstreams.size()];
        int i = 0;
        for (Integer u : upstreams) {
            values[i++] = u.intValue();
        }
        return values;
    }

    // TODO review API - maybe return ArrayList<String> here and below?
    public String[] getTetheredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                if (tetherState.mLastState == IControlsTethering.STATE_TETHERED) {
                    list.add(mTetherStates.keyAt(i));
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }

    public String[] getTetherableIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                if (tetherState.mLastState == IControlsTethering.STATE_AVAILABLE) {
                    list.add(mTetherStates.keyAt(i));
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }

    public String[] getTetheredDhcpRanges() {
        return mConfig.dhcpRanges;
    }

    public String[] getErroredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                if (tetherState.mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    list.add(mTetherStates.keyAt(i));
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }

    private void maybeLogMessage(State state, int what) {
        if (DBG) {
            Log.d(TAG, state.getName() + " got " +
                    sMagicDecoderRing.get(what, Integer.toString(what)));
        }
    }

    // Needed because the canonical source of upstream truth is just the
    // upstream interface name, |mCurrentUpstreamIface|.  This is ripe for
    // future simplification, once the upstream Network is canonical.
    boolean pertainsToCurrentUpstream(NetworkState ns) {
        if (ns != null && ns.linkProperties != null && mCurrentUpstreamIface != null) {
            for (String ifname : ns.linkProperties.getAllInterfaceNames()) {
                if (mCurrentUpstreamIface.equals(ifname)) {
                    return true;
                }
            }
        }
        return false;
    }

    class TetherMasterSM extends StateMachine {
        private static final int BASE_MASTER                    = Protocol.BASE_TETHERING;
        // an interface SM has requested Tethering
        static final int CMD_TETHER_MODE_REQUESTED              = BASE_MASTER + 1;
        // an interface SM has unrequested Tethering
        static final int CMD_TETHER_MODE_UNREQUESTED            = BASE_MASTER + 2;
        // upstream connection change - do the right thing
        static final int CMD_UPSTREAM_CHANGED                   = BASE_MASTER + 3;
        // we don't have a valid upstream conn, check again after a delay
        static final int CMD_RETRY_UPSTREAM                     = BASE_MASTER + 4;
        // Events from NetworkCallbacks that we process on the master state
        // machine thread on behalf of the UpstreamNetworkMonitor.
        static final int EVENT_UPSTREAM_CALLBACK                = BASE_MASTER + 5;

        private State mInitialState;
        private State mTetherModeAliveState;

        private State mSetIpForwardingEnabledErrorState;
        private State mSetIpForwardingDisabledErrorState;
        private State mStartTetheringErrorState;
        private State mStopTetheringErrorState;
        private State mSetDnsForwardersErrorState;

        // This list is a little subtle.  It contains all the interfaces that currently are
        // requesting tethering, regardless of whether these interfaces are still members of
        // mTetherStates.  This allows us to maintain the following predicates:
        //
        // 1) mTetherStates contains the set of all currently existing, tetherable, link state up
        //    interfaces.
        // 2) mNotifyList contains all state machines that may have outstanding tethering state
        //    that needs to be torn down.
        //
        // Because we excise interfaces immediately from mTetherStates, we must maintain mNotifyList
        // so that the garbage collector does not clean up the state machine before it has a chance
        // to tear itself down.
        private final ArrayList<TetherInterfaceStateMachine> mNotifyList;
        private final IPv6TetheringCoordinator mIPv6TetheringCoordinator;

        private int mPreviousMobileType = ConnectivityManager.TYPE_NONE;

        private static final int UPSTREAM_SETTLE_TIME_MS     = 10000;

        TetherMasterSM(String name, Looper looper) {
            super(name, looper);

            //Add states
            mInitialState = new InitialState();
            addState(mInitialState);
            mTetherModeAliveState = new TetherModeAliveState();
            addState(mTetherModeAliveState);

            mSetIpForwardingEnabledErrorState = new SetIpForwardingEnabledErrorState();
            addState(mSetIpForwardingEnabledErrorState);
            mSetIpForwardingDisabledErrorState = new SetIpForwardingDisabledErrorState();
            addState(mSetIpForwardingDisabledErrorState);
            mStartTetheringErrorState = new StartTetheringErrorState();
            addState(mStartTetheringErrorState);
            mStopTetheringErrorState = new StopTetheringErrorState();
            addState(mStopTetheringErrorState);
            mSetDnsForwardersErrorState = new SetDnsForwardersErrorState();
            addState(mSetDnsForwardersErrorState);

            mNotifyList = new ArrayList<>();
            mIPv6TetheringCoordinator = new IPv6TetheringCoordinator(mNotifyList);
            setInitialState(mInitialState);
        }

        class TetherMasterUtilState extends State {
            @Override
            public boolean processMessage(Message m) {
                return false;
            }

            protected boolean requestUpstreamMobileConnection(int apnType) {
                if (apnType == ConnectivityManager.TYPE_NONE) { return false; }

                if (apnType != mPreviousMobileType) {
                    // Unregister any previous mobile upstream callback because
                    // this request, if any, will be different.
                    unrequestUpstreamMobileConnection();
                }

                if (mUpstreamNetworkMonitor.mobileNetworkRequested()) {
                    // Looks like we already filed a request for this apnType.
                    return true;
                }

                switch (apnType) {
                    case ConnectivityManager.TYPE_MOBILE_DUN:
                    case ConnectivityManager.TYPE_MOBILE:
                    case ConnectivityManager.TYPE_MOBILE_HIPRI:
                        mPreviousMobileType = apnType;
                        break;
                    default:
                        return false;
                }

                // TODO: Replace this with a call to pass the current tethering
                // configuration to mUpstreamNetworkMonitor and let it handle
                // choosing APN type accordingly.
                mUpstreamNetworkMonitor.updateMobileRequiresDun(
                        apnType == ConnectivityManager.TYPE_MOBILE_DUN);

                mUpstreamNetworkMonitor.registerMobileNetworkRequest();
                return true;
            }

            protected void unrequestUpstreamMobileConnection() {
                mUpstreamNetworkMonitor.releaseMobileNetworkRequest();
                mPreviousMobileType = ConnectivityManager.TYPE_NONE;
            }

            protected boolean turnOnMasterTetherSettings() {
                final TetheringConfiguration cfg = mConfig;
                try {
                    mNMService.setIpForwardingEnabled(true);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingEnabledErrorState);
                    return false;
                }
                try {
                    mNMService.startTethering(cfg.dhcpRanges);
                } catch (Exception e) {
                    try {
                        mNMService.stopTethering();
                        mNMService.startTethering(cfg.dhcpRanges);
                    } catch (Exception ee) {
                        transitionTo(mStartTetheringErrorState);
                        return false;
                    }
                }
                return true;
            }

            protected boolean turnOffMasterTetherSettings() {
                try {
                    mNMService.stopTethering();
                } catch (Exception e) {
                    transitionTo(mStopTetheringErrorState);
                    return false;
                }
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingDisabledErrorState);
                    return false;
                }
                transitionTo(mInitialState);
                return true;
            }

            protected void chooseUpstreamType(boolean tryCell) {
                final ConnectivityManager cm = getConnectivityManager();
                int upType = ConnectivityManager.TYPE_NONE;
                String iface = null;

                updateConfiguration(); // TODO - remove?

                final TetheringConfiguration cfg = mConfig;
                if (VDBG) {
                    Log.d(TAG, "chooseUpstreamType has upstream iface types:");
                    for (Integer netType : cfg.preferredUpstreamIfaceTypes) {
                        Log.d(TAG, " " + netType);
                    }
                }

                for (Integer netType : cfg.preferredUpstreamIfaceTypes) {
                    NetworkInfo info = cm.getNetworkInfo(netType.intValue());
                    // TODO: if the network is suspended we should consider
                    // that to be the same as connected here.
                    if ((info != null) && info.isConnected()) {
                        upType = netType.intValue();
                        break;
                    }
                }

                final int preferredUpstreamMobileApn = cfg.isDunRequired
                        ? ConnectivityManager.TYPE_MOBILE_DUN
                        : ConnectivityManager.TYPE_MOBILE_HIPRI;
                if (DBG) {
                    Log.d(TAG, "chooseUpstreamType(" + tryCell + "),"
                            + " preferredApn="
                            + ConnectivityManager.getNetworkTypeName(preferredUpstreamMobileApn)
                            + ", got type="
                            + ConnectivityManager.getNetworkTypeName(upType));
                }

                switch (upType) {
                    case ConnectivityManager.TYPE_MOBILE_DUN:
                    case ConnectivityManager.TYPE_MOBILE_HIPRI:
                        // If we're on DUN, put our own grab on it.
                        requestUpstreamMobileConnection(upType);
                        break;
                    case ConnectivityManager.TYPE_NONE:
                        if (tryCell &&
                                requestUpstreamMobileConnection(preferredUpstreamMobileApn)) {
                            // We think mobile should be coming up; don't set a retry.
                        } else {
                            sendMessageDelayed(CMD_RETRY_UPSTREAM, UPSTREAM_SETTLE_TIME_MS);
                        }
                        break;
                    default:
                        /* If we've found an active upstream connection that's not DUN/HIPRI
                         * we should stop any outstanding DUN/HIPRI start requests.
                         *
                         * If we found NONE we don't want to do this as we want any previous
                         * requests to keep trying to bring up something we can use.
                         */
                        unrequestUpstreamMobileConnection();
                        break;
                }

                Network network = null;
                if (upType != ConnectivityManager.TYPE_NONE) {
                    LinkProperties linkProperties = cm.getLinkProperties(upType);
                    if (linkProperties != null) {
                        // Find the interface with the default IPv4 route. It may be the
                        // interface described by linkProperties, or one of the interfaces
                        // stacked on top of it.
                        Log.i(TAG, "Finding IPv4 upstream interface on: " + linkProperties);
                        RouteInfo ipv4Default = RouteInfo.selectBestRoute(
                            linkProperties.getAllRoutes(), Inet4Address.ANY);
                        if (ipv4Default != null) {
                            iface = ipv4Default.getInterface();
                            Log.i(TAG, "Found interface " + ipv4Default.getInterface());
                        } else {
                            Log.i(TAG, "No IPv4 upstream interface, giving up.");
                        }
                    }

                    if (iface != null) {
                        network = cm.getNetworkForType(upType);
                        if (network == null) {
                            Log.e(TAG, "No Network for upstream type " + upType + "!");
                        }
                        setDnsForwarders(network, linkProperties);
                    }
                }
                notifyTetheredOfNewUpstreamIface(iface);
                NetworkState ns = mUpstreamNetworkMonitor.lookup(network);
                if (ns != null && pertainsToCurrentUpstream(ns)) {
                    // If we already have NetworkState for this network examine
                    // it immediately, because there likely will be no second
                    // EVENT_ON_AVAILABLE (it was already received).
                    handleNewUpstreamNetworkState(ns);
                } else if (mCurrentUpstreamIface == null) {
                    // There are no available upstream networks, or none that
                    // have an IPv4 default route (current metric for success).
                    handleNewUpstreamNetworkState(null);
                }
            }

            protected void setDnsForwarders(final Network network, final LinkProperties lp) {
                // TODO: Set v4 and/or v6 DNS per available connectivity.
                String[] dnsServers = mConfig.defaultIPv4DNS;
                final Collection<InetAddress> dnses = lp.getDnsServers();
                // TODO: Properly support the absence of DNS servers.
                if (dnses != null && !dnses.isEmpty()) {
                    // TODO: remove this invocation of NetworkUtils.makeStrings().
                    dnsServers = NetworkUtils.makeStrings(dnses);
                }
                if (VDBG) {
                    Log.d(TAG, "Setting DNS forwarders: Network=" + network +
                           ", dnsServers=" + Arrays.toString(dnsServers));
                }
                try {
                    mNMService.setDnsForwarders(network, dnsServers);
                } catch (Exception e) {
                    // TODO: Investigate how this can fail and what exactly
                    // happens if/when such failures occur.
                    Log.e(TAG, "Setting DNS forwarders failed!");
                    transitionTo(mSetDnsForwardersErrorState);
                }
            }

            protected void notifyTetheredOfNewUpstreamIface(String ifaceName) {
                if (DBG) Log.d(TAG, "Notifying tethered with upstream=" + ifaceName);
                mCurrentUpstreamIface = ifaceName;
                for (TetherInterfaceStateMachine sm : mNotifyList) {
                    sm.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED,
                            ifaceName);
                }
            }

            protected void handleNewUpstreamNetworkState(NetworkState ns) {
                mIPv6TetheringCoordinator.updateUpstreamNetworkState(ns);
            }
        }

        private class SimChangeListener {
            private final Context mContext;
            private final AtomicInteger mSimBcastGenerationNumber;
            private BroadcastReceiver mBroadcastReceiver;

            SimChangeListener(Context ctx) {
                mContext = ctx;
                mSimBcastGenerationNumber = new AtomicInteger(0);
            }

            public int generationNumber() {
                return mSimBcastGenerationNumber.get();
            }

            public void startListening() {
                if (DBG) Log.d(TAG, "startListening for SIM changes");

                if (mBroadcastReceiver != null) return;

                mBroadcastReceiver = new SimChangeBroadcastReceiver(
                        mSimBcastGenerationNumber.incrementAndGet());
                final IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

                mContext.registerReceiver(mBroadcastReceiver, filter, null,
                        mTetherMasterSM.getHandler());
            }

            public void stopListening() {
                if (DBG) Log.d(TAG, "stopListening for SIM changes");

                if (mBroadcastReceiver == null) return;

                mSimBcastGenerationNumber.incrementAndGet();
                mContext.unregisterReceiver(mBroadcastReceiver);
                mBroadcastReceiver = null;
            }

            public boolean hasMobileHotspotProvisionApp() {
                try {
                    if (!mContext.getResources().getString(com.android.internal.R.string.
                            config_mobile_hotspot_provision_app_no_ui).isEmpty()) {
                        Log.d(TAG, "re-evaluate provisioning");
                        return true;
                    }
                } catch (Resources.NotFoundException e) {}
                Log.d(TAG, "no prov-check needed for new SIM");
                return false;
            }

            private boolean isSimCardAbsent(String state) {
                return IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(state);
            }

            private boolean isSimCardLoaded(String state) {
                return IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(state);
            }

            private void startProvisionIntent(int tetherType) {
                final Intent startProvIntent = new Intent();
                startProvIntent.putExtra(ConnectivityManager.EXTRA_ADD_TETHER_TYPE, tetherType);
                startProvIntent.putExtra(ConnectivityManager.EXTRA_RUN_PROVISION, true);
                startProvIntent.setComponent(TETHER_SERVICE);
                mContext.startServiceAsUser(startProvIntent, UserHandle.CURRENT);
            }

            private class SimChangeBroadcastReceiver extends BroadcastReceiver {
                // used to verify this receiver is still current
                final private int mGenerationNumber;

                // we're interested in edge-triggered LOADED notifications, so
                // ignore LOADED unless we saw an ABSENT state first
                private boolean mSimAbsentSeen = false;

                public SimChangeBroadcastReceiver(int generationNumber) {
                    mGenerationNumber = generationNumber;
                }

                @Override
                public void onReceive(Context context, Intent intent) {
                    final int currentGenerationNumber = mSimBcastGenerationNumber.get();

                    if (DBG) {
                        Log.d(TAG, "simchange mGenerationNumber=" + mGenerationNumber +
                                ", current generationNumber=" + currentGenerationNumber);
                    }
                    if (mGenerationNumber != currentGenerationNumber) return;

                    final String state = intent.getStringExtra(
                            IccCardConstants.INTENT_KEY_ICC_STATE);
                    Log.d(TAG, "got Sim changed to state " + state + ", mSimAbsentSeen=" +
                            mSimAbsentSeen);

                    if (isSimCardAbsent(state)) {
                        if (!mSimAbsentSeen) mSimAbsentSeen = true;
                        return;
                    }

                    if (isSimCardLoaded(state) && mSimAbsentSeen) {
                        mSimAbsentSeen = false;

                        if (!hasMobileHotspotProvisionApp()) return;

                        ArrayList<Integer> tethered = new ArrayList<Integer>();
                        synchronized (mPublicSync) {
                            for (int i = 0; i < mTetherStates.size(); i++) {
                                TetherState tetherState = mTetherStates.valueAt(i);
                                if (tetherState.mLastState != IControlsTethering.STATE_TETHERED) {
                                    continue;  // Skip interfaces that aren't tethered.
                                }
                                String iface = mTetherStates.keyAt(i);
                                int interfaceType = ifaceNameToType(iface);
                                if (interfaceType != ConnectivityManager.TETHERING_INVALID) {
                                    tethered.add(new Integer(interfaceType));
                                }
                            }
                        }

                        for (int tetherType : tethered) {
                            startProvisionIntent(tetherType);
                        }
                    }
                }
            }
        }

        class InitialState extends TetherMasterUtilState {
            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceStateMachine who = (TetherInterfaceStateMachine)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode requested by " + who);
                        if (mNotifyList.indexOf(who) < 0) {
                            mNotifyList.add(who);
                            mIPv6TetheringCoordinator.addActiveDownstream(who);
                        }
                        transitionTo(mTetherModeAliveState);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceStateMachine)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode unrequested by " + who);
                        mNotifyList.remove(who);
                        mIPv6TetheringCoordinator.removeActiveDownstream(who);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class TetherModeAliveState extends TetherMasterUtilState {
            final SimChangeListener simChange = new SimChangeListener(mContext);
            boolean mTryCell = true;
            @Override
            public void enter() {
                // TODO: examine if we should check the return value.
                turnOnMasterTetherSettings(); // may transition us out
                simChange.startListening();
                mUpstreamNetworkMonitor.start();

                mTryCell = true;  // better try something first pass or crazy tests cases will fail
                chooseUpstreamType(mTryCell);
                mTryCell = !mTryCell;
            }

            @Override
            public void exit() {
                unrequestUpstreamMobileConnection();
                mUpstreamNetworkMonitor.stop();
                simChange.stopListening();
                notifyTetheredOfNewUpstreamIface(null);
                handleNewUpstreamNetworkState(null);
            }

            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED: {
                        TetherInterfaceStateMachine who = (TetherInterfaceStateMachine)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode requested by " + who);
                        if (mNotifyList.indexOf(who) < 0) {
                            mNotifyList.add(who);
                            mIPv6TetheringCoordinator.addActiveDownstream(who);
                        }
                        who.sendMessage(TetherInterfaceStateMachine.CMD_TETHER_CONNECTION_CHANGED,
                                mCurrentUpstreamIface);
                        break;
                    }
                    case CMD_TETHER_MODE_UNREQUESTED: {
                        TetherInterfaceStateMachine who = (TetherInterfaceStateMachine)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode unrequested by " + who);
                        if (mNotifyList.remove(who)) {
                            if (DBG) Log.d(TAG, "TetherModeAlive removing notifyee " + who);
                            if (mNotifyList.isEmpty()) {
                                turnOffMasterTetherSettings(); // transitions appropriately
                            } else {
                                if (DBG) {
                                    Log.d(TAG, "TetherModeAlive still has " + mNotifyList.size() +
                                            " live requests:");
                                    for (TetherInterfaceStateMachine o : mNotifyList) {
                                        Log.d(TAG, "  " + o);
                                    }
                                }
                            }
                        } else {
                           Log.e(TAG, "TetherModeAliveState UNREQUESTED has unknown who: " + who);
                        }
                        mIPv6TetheringCoordinator.removeActiveDownstream(who);
                        break;
                    }
                    case CMD_UPSTREAM_CHANGED:
                        // need to try DUN immediately if Wifi goes down
                        mTryCell = true;
                        chooseUpstreamType(mTryCell);
                        mTryCell = !mTryCell;
                        break;
                    case CMD_RETRY_UPSTREAM:
                        chooseUpstreamType(mTryCell);
                        mTryCell = !mTryCell;
                        break;
                    case EVENT_UPSTREAM_CALLBACK: {
                        final NetworkState ns = (NetworkState) message.obj;

                        if (ns == null || !pertainsToCurrentUpstream(ns)) {
                            // TODO: In future, this is where upstream evaluation and selection
                            // could be handled for notifications which include sufficient data.
                            // For example, after CONNECTIVITY_ACTION listening is removed, here
                            // is where we could observe a Wi-Fi network becoming available and
                            // passing validation.
                            if (mCurrentUpstreamIface == null) {
                                // If we have no upstream interface, try to run through upstream
                                // selection again.  If, for example, IPv4 connectivity has shown up
                                // after IPv6 (e.g., 464xlat became available) we want the chance to
                                // notice and act accordingly.
                                chooseUpstreamType(false);
                            }
                            break;
                        }

                        switch (message.arg1) {
                            case UpstreamNetworkMonitor.EVENT_ON_AVAILABLE:
                                // The default network changed, or DUN connected
                                // before this callback was processed. Updates
                                // for the current NetworkCapabilities and
                                // LinkProperties have been requested (default
                                // request) or are being sent shortly (DUN). Do
                                // nothing until they arrive; if no updates
                                // arrive there's nothing to do.
                                break;
                            case UpstreamNetworkMonitor.EVENT_ON_CAPABILITIES:
                                handleNewUpstreamNetworkState(ns);
                                break;
                            case UpstreamNetworkMonitor.EVENT_ON_LINKPROPERTIES:
                                setDnsForwarders(ns.network, ns.linkProperties);
                                handleNewUpstreamNetworkState(ns);
                                break;
                            case UpstreamNetworkMonitor.EVENT_ON_LOST:
                                // TODO: Re-evaluate possible upstreams. Currently upstream
                                // reevaluation is triggered via received CONNECTIVITY_ACTION
                                // broadcasts that result in being passed a
                                // TetherMasterSM.CMD_UPSTREAM_CHANGED.
                                handleNewUpstreamNetworkState(null);
                                break;
                            default:
                                break;
                        }
                        break;
                    }
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class ErrorState extends State {
            int mErrorNotification;
            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceStateMachine who = (TetherInterfaceStateMachine)message.obj;
                        who.sendMessage(mErrorNotification);
                        break;
                    default:
                       retValue = false;
                }
                return retValue;
            }
            void notify(int msgType) {
                mErrorNotification = msgType;
                for (TetherInterfaceStateMachine sm : mNotifyList) {
                    sm.sendMessage(msgType);
                }
            }

        }
        class SetIpForwardingEnabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingEnabled");
                notify(TetherInterfaceStateMachine.CMD_IP_FORWARDING_ENABLE_ERROR);
            }
        }

        class SetIpForwardingDisabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingDisabled");
                notify(TetherInterfaceStateMachine.CMD_IP_FORWARDING_DISABLE_ERROR);
            }
        }

        class StartTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in startTethering");
                notify(TetherInterfaceStateMachine.CMD_START_TETHERING_ERROR);
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class StopTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in stopTethering");
                notify(TetherInterfaceStateMachine.CMD_STOP_TETHERING_ERROR);
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class SetDnsForwardersErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setDnsForwarders");
                notify(TetherInterfaceStateMachine.CMD_SET_DNS_FORWARDERS_ERROR);
                try {
                    mNMService.stopTethering();
                } catch (Exception e) {}
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        // Binder.java closes the resource for us.
        @SuppressWarnings("resource")
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService.Tether " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
                    return;
        }

        pw.println("Tethering:");
        pw.increaseIndent();
        final TetheringConfiguration cfg = mConfig;
        pw.print("preferredUpstreamIfaceTypes:");
        synchronized (mPublicSync) {
            for (Integer netType : cfg.preferredUpstreamIfaceTypes) {
                pw.print(" " + ConnectivityManager.getNetworkTypeName(netType));
            }
            pw.println();

            pw.println("Tether state:");
            pw.increaseIndent();
            for (int i = 0; i < mTetherStates.size(); i++) {
                final String iface = mTetherStates.keyAt(i);
                final TetherState tetherState = mTetherStates.valueAt(i);
                pw.print(iface + " - ");

                switch (tetherState.mLastState) {
                    case IControlsTethering.STATE_UNAVAILABLE:
                        pw.print("UnavailableState");
                        break;
                    case IControlsTethering.STATE_AVAILABLE:
                        pw.print("AvailableState");
                        break;
                    case IControlsTethering.STATE_TETHERED:
                        pw.print("TetheredState");
                        break;
                    default:
                        pw.print("UnknownState");
                        break;
                }
                pw.println(" - lastError = " + tetherState.mLastError);
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    @Override
    public void notifyInterfaceStateChange(String iface, TetherInterfaceStateMachine who,
                                           int state, int error) {
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState != null && tetherState.mStateMachine.equals(who)) {
                tetherState.mLastState = state;
                tetherState.mLastError = error;
            } else {
                if (DBG) Log.d(TAG, "got notification from stale iface " + iface);
            }
        }

        if (DBG) {
            Log.d(TAG, "iface " + iface + " notified that it was in state " + state +
                    " with error " + error);
        }

        try {
            // Notify that we're tethering (or not) this interface.
            // This is how data saver for instance knows if the user explicitly
            // turned on tethering (thus keeping us from being in data saver mode).
            mPolicyManager.onTetheringChanged(iface, state == IControlsTethering.STATE_TETHERED);
        } catch (RemoteException e) {
            // Not really very much we can do here.
        }

        switch (state) {
            case IControlsTethering.STATE_UNAVAILABLE:
            case IControlsTethering.STATE_AVAILABLE:
                mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED, who);
                break;
            case IControlsTethering.STATE_TETHERED:
                mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_REQUESTED, who);
                break;
        }
        sendTetherStateChangedBroadcast();
    }

    private void trackNewTetherableInterface(String iface, int interfaceType) {
        TetherState tetherState;
        tetherState = new TetherState(new TetherInterfaceStateMachine(iface, mLooper,
                interfaceType, mNMService, mStatsService, this,
                new IPv6TetheringInterfaceServices(iface, mNMService)));
        mTetherStates.put(iface, tetherState);
        tetherState.mStateMachine.start();
    }

    private static String[] copy(String[] strarray) {
        return Arrays.copyOf(strarray, strarray.length);
    }

    private static class TetheringConfiguration {
        private static final int DUN_NOT_REQUIRED = 0;
        private static final int DUN_REQUIRED = 1;
        private static final int DUN_UNSPECIFIED = 2;

        // USB is  192.168.42.1 and 255.255.255.0
        // Wifi is 192.168.43.1 and 255.255.255.0
        // BT is limited to max default of 5 connections. 192.168.44.1 to 192.168.48.1
        // with 255.255.255.0
        // P2P is 192.168.49.1 and 255.255.255.0
        private static final String[] DHCP_DEFAULT_RANGE = {
            "192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254",
            "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254",
            "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254",
            "192.168.48.2", "192.168.48.254", "192.168.49.2", "192.168.49.254",
        };

        private final String[] DEFAULT_IPV4_DNS = {"8.8.4.4", "8.8.8.8"};

        public final String[] tetherableUsbRegexs;
        public final String[] tetherableWifiRegexs;
        public final String[] tetherableBluetoothRegexs;
        public final boolean isDunRequired;
        public final Collection<Integer> preferredUpstreamIfaceTypes;
        public final String[] dhcpRanges;
        public final String[] defaultIPv4DNS;

        public TetheringConfiguration(Context ctx) {
            tetherableUsbRegexs = ctx.getResources().getStringArray(
                    com.android.internal.R.array.config_tether_usb_regexs);
            tetherableWifiRegexs = ctx.getResources().getStringArray(
                    com.android.internal.R.array.config_tether_wifi_regexs);
            tetherableBluetoothRegexs = ctx.getResources().getStringArray(
                    com.android.internal.R.array.config_tether_bluetooth_regexs);

            isDunRequired = checkDunRequired(ctx);
            preferredUpstreamIfaceTypes = getUpstreamIfaceTypes(ctx, isDunRequired);

            dhcpRanges = getDhcpRanges(ctx);
            defaultIPv4DNS = copy(DEFAULT_IPV4_DNS);
        }

        public boolean isUsb(String iface) {
            for (String regex : tetherableUsbRegexs) {
                if (iface.matches(regex)) return true;
            }
            return false;
        }

        public boolean isWifi(String iface) {
            for (String regex : tetherableWifiRegexs) {
                if (iface.matches(regex)) return true;
            }
            return false;
        }

        public boolean isBluetooth(String iface) {
            for (String regex : tetherableBluetoothRegexs) {
                if (iface.matches(regex)) return true;
            }
            return false;
        }

        private static boolean checkDunRequired(Context ctx) {
            final TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            final int secureSetting =
                    (tm != null) ? tm.getTetherApnRequired() : DUN_UNSPECIFIED;
            return (secureSetting == DUN_REQUIRED);
        }

        private static Collection<Integer> getUpstreamIfaceTypes(Context ctx, boolean requiresDun) {
            final int ifaceTypes[] = ctx.getResources().getIntArray(
                    com.android.internal.R.array.config_tether_upstream_types);
            final ArrayList<Integer> upstreamIfaceTypes = new ArrayList<>(ifaceTypes.length);
            for (int i : ifaceTypes) {
                upstreamIfaceTypes.add(i);
            }

            // Fix up upstream interface types for DUN or mobile.
            // TODO: Perform this filtering step in the above for loop.
            if (requiresDun) {
                while (upstreamIfaceTypes.contains(MOBILE_TYPE)) {
                    upstreamIfaceTypes.remove(MOBILE_TYPE);
                }
                while (upstreamIfaceTypes.contains(HIPRI_TYPE)) {
                    upstreamIfaceTypes.remove(HIPRI_TYPE);
                }
                if (!upstreamIfaceTypes.contains(DUN_TYPE)) {
                    upstreamIfaceTypes.add(DUN_TYPE);
                }
            } else {
                while (upstreamIfaceTypes.contains(DUN_TYPE)) {
                    upstreamIfaceTypes.remove(DUN_TYPE);
                }
                if (!upstreamIfaceTypes.contains(MOBILE_TYPE)) {
                    upstreamIfaceTypes.add(MOBILE_TYPE);
                }
                if (!upstreamIfaceTypes.contains(HIPRI_TYPE)) {
                    upstreamIfaceTypes.add(HIPRI_TYPE);
                }
            }

            return upstreamIfaceTypes;
        }

        private static String[] getDhcpRanges(Context ctx) {
            final String[] fromResource = ctx.getResources().getStringArray(
                    com.android.internal.R.array.config_tether_dhcp_range);
            if ((fromResource.length > 0) && (fromResource.length % 2 == 0)) {
                return fromResource;
            }
            return copy(DHCP_DEFAULT_RANGE);
        }
    }
}
