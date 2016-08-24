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
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
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
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.IState;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.IoThread;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * @hide
 *
 * Timeout
 *
 * TODO - look for parent classes and code sharing
 */
public class Tethering extends BaseNetworkObserver {

    private final Context mContext;
    private final static String TAG = "Tethering";
    private final static boolean DBG = false;
    private final static boolean VDBG = false;

    private static final Class[] messageClasses = {
            Tethering.class, TetherMasterSM.class, TetherInterfaceSM.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(messageClasses);

    // TODO - remove both of these - should be part of interface inspection/selection stuff
    private String[] mTetherableUsbRegexs;
    private String[] mTetherableWifiRegexs;
    private String[] mTetherableBluetoothRegexs;
    private Collection<Integer> mUpstreamIfaceTypes;

    // used to synchronize public access to members
    private final Object mPublicSync;

    private static final Integer MOBILE_TYPE = new Integer(ConnectivityManager.TYPE_MOBILE);
    private static final Integer HIPRI_TYPE = new Integer(ConnectivityManager.TYPE_MOBILE_HIPRI);
    private static final Integer DUN_TYPE = new Integer(ConnectivityManager.TYPE_MOBILE_DUN);

    // if we have to connect to mobile, what APN type should we use?  Calculated by examining the
    // upstream type list and the DUN_REQUIRED secure-setting
    private int mPreferredUpstreamMobileApn = ConnectivityManager.TYPE_NONE;

    private final INetworkManagementService mNMService;
    private final INetworkStatsService mStatsService;
    private final Looper mLooper;

    private HashMap<String, TetherInterfaceSM> mIfaces; // all tethered/tetherable ifaces

    private BroadcastReceiver mStateReceiver;

    // {@link ComponentName} of the Service used to run tether provisioning.
    private static final ComponentName TETHER_SERVICE = ComponentName.unflattenFromString(Resources
            .getSystem().getString(com.android.internal.R.string.config_wifi_tether_enable));

    private static final String USB_NEAR_IFACE_ADDR      = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH        = 24;

    // USB is  192.168.42.1 and 255.255.255.0
    // Wifi is 192.168.43.1 and 255.255.255.0
    // BT is limited to max default of 5 connections. 192.168.44.1 to 192.168.48.1
    // with 255.255.255.0
    // P2P is 192.168.49.1 and 255.255.255.0

    private String[] mDhcpRange;
    private static final String[] DHCP_DEFAULT_RANGE = {
        "192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254",
        "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254",
        "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254",
        "192.168.48.2", "192.168.48.254", "192.168.49.2", "192.168.49.254",
    };

    private String[] mDefaultDnsServers;
    private static final String DNS_DEFAULT_SERVER1 = "8.8.8.8";
    private static final String DNS_DEFAULT_SERVER2 = "8.8.4.4";

    private final StateMachine mTetherMasterSM;
    private final UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    private String mCurrentUpstreamIface;

    private Notification.Builder mTetheredNotificationBuilder;
    private int mLastNotificationId;

    private boolean mRndisEnabled;       // track the RNDIS function enabled state
    private boolean mUsbTetherRequested; // true if USB tethering should be started
                                         // when RNDIS is enabled

    public Tethering(Context context, INetworkManagementService nmService,
            INetworkStatsService statsService) {
        mContext = context;
        mNMService = nmService;
        mStatsService = statsService;

        mPublicSync = new Object();

        mIfaces = new HashMap<String, TetherInterfaceSM>();

        // make our own thread so we don't anr the system
        mLooper = IoThread.get().getLooper();
        mTetherMasterSM = new TetherMasterSM("TetherMaster", mLooper);
        mTetherMasterSM.start();

        mUpstreamNetworkMonitor = new UpstreamNetworkMonitor();

        mStateReceiver = new StateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_STATE);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiver(mStateReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        filter.addDataScheme("file");
        mContext.registerReceiver(mStateReceiver, filter);

        mDhcpRange = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_dhcp_range);
        if ((mDhcpRange.length == 0) || (mDhcpRange.length % 2 ==1)) {
            mDhcpRange = DHCP_DEFAULT_RANGE;
        }

        // load device config info
        updateConfiguration();

        // TODO - remove and rely on real notifications of the current iface
        mDefaultDnsServers = new String[2];
        mDefaultDnsServers[0] = DNS_DEFAULT_SERVER1;
        mDefaultDnsServers[1] = DNS_DEFAULT_SERVER2;
    }

    // We can't do this once in the Tethering() constructor and cache the value, because the
    // CONNECTIVITY_SERVICE is registered only after the Tethering() constructor has completed.
    private ConnectivityManager getConnectivityManager() {
        return (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void updateConfiguration() {
        String[] tetherableUsbRegexs = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_tether_usb_regexs);
        String[] tetherableWifiRegexs = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_tether_wifi_regexs);
        String[] tetherableBluetoothRegexs = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_tether_bluetooth_regexs);

        int ifaceTypes[] = mContext.getResources().getIntArray(
                com.android.internal.R.array.config_tether_upstream_types);
        Collection<Integer> upstreamIfaceTypes = new ArrayList();
        for (int i : ifaceTypes) {
            upstreamIfaceTypes.add(new Integer(i));
        }

        synchronized (mPublicSync) {
            mTetherableUsbRegexs = tetherableUsbRegexs;
            mTetherableWifiRegexs = tetherableWifiRegexs;
            mTetherableBluetoothRegexs = tetherableBluetoothRegexs;
            mUpstreamIfaceTypes = upstreamIfaceTypes;
        }

        // check if the upstream type list needs to be modified due to secure-settings
        checkDunRequired();
    }

    @Override
    public void interfaceStatusChanged(String iface, boolean up) {
        // Never called directly: only called from interfaceLinkStateChanged.
        // See NetlinkHandler.cpp:71.
        if (VDBG) Log.d(TAG, "interfaceStatusChanged " + iface + ", " + up);
        boolean found = false;
        boolean usb = false;
        synchronized (mPublicSync) {
            if (isWifi(iface)) {
                found = true;
            } else if (isUsb(iface)) {
                found = true;
                usb = true;
            } else if (isBluetooth(iface)) {
                found = true;
            }
            if (found == false) return;

            TetherInterfaceSM sm = mIfaces.get(iface);
            if (up) {
                if (sm == null) {
                    sm = new TetherInterfaceSM(iface, mLooper, usb);
                    mIfaces.put(iface, sm);
                    sm.start();
                }
            } else {
                if (isUsb(iface)) {
                    // ignore usb0 down after enabling RNDIS
                    // we will handle disconnect in interfaceRemoved instead
                    if (VDBG) Log.d(TAG, "ignore interface down for " + iface);
                } else if (sm != null) {
                    sm.sendMessage(TetherInterfaceSM.CMD_INTERFACE_DOWN);
                    mIfaces.remove(iface);
                }
            }
        }
    }

    @Override
    public void interfaceLinkStateChanged(String iface, boolean up) {
        interfaceStatusChanged(iface, up);
    }

    private boolean isUsb(String iface) {
        synchronized (mPublicSync) {
            for (String regex : mTetherableUsbRegexs) {
                if (iface.matches(regex)) return true;
            }
            return false;
        }
    }

    public boolean isWifi(String iface) {
        synchronized (mPublicSync) {
            for (String regex : mTetherableWifiRegexs) {
                if (iface.matches(regex)) return true;
            }
            return false;
        }
    }

    public boolean isBluetooth(String iface) {
        synchronized (mPublicSync) {
            for (String regex : mTetherableBluetoothRegexs) {
                if (iface.matches(regex)) return true;
            }
            return false;
        }
    }

    @Override
    public void interfaceAdded(String iface) {
        if (VDBG) Log.d(TAG, "interfaceAdded " + iface);
        boolean found = false;
        boolean usb = false;
        synchronized (mPublicSync) {
            if (isWifi(iface)) {
                found = true;
            }
            if (isUsb(iface)) {
                found = true;
                usb = true;
            }
            if (isBluetooth(iface)) {
                found = true;
            }
            if (found == false) {
                if (VDBG) Log.d(TAG, iface + " is not a tetherable iface, ignoring");
                return;
            }

            TetherInterfaceSM sm = mIfaces.get(iface);
            if (sm != null) {
                if (VDBG) Log.d(TAG, "active iface (" + iface + ") reported as added, ignoring");
                return;
            }
            sm = new TetherInterfaceSM(iface, mLooper, usb);
            mIfaces.put(iface, sm);
            sm.start();
        }
    }

    @Override
    public void interfaceRemoved(String iface) {
        if (VDBG) Log.d(TAG, "interfaceRemoved " + iface);
        synchronized (mPublicSync) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (sm == null) {
                if (VDBG) {
                    Log.e(TAG, "attempting to remove unknown iface (" + iface + "), ignoring");
                }
                return;
            }
            sm.sendMessage(TetherInterfaceSM.CMD_INTERFACE_DOWN);
            mIfaces.remove(iface);
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
    private boolean isTetherProvisioningRequired() {
        String[] provisionApp = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
        if (SystemProperties.getBoolean("net.tethering.noprovisioning", false)
                || provisionApp == null) {
            return false;
        }

        // Check carrier config for entitlement checks
        final CarrierConfigManager configManager = (CarrierConfigManager) mContext
             .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        boolean isEntitlementCheckRequired = configManager.getConfig().getBoolean(
             CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL);

        if (!isEntitlementCheckRequired) {
            return false;
        }
        return (provisionApp.length == 2);
    }

    /**
     * Enables or disables tethering for the given type. This should only be called once
     * provisioning has succeeded or is not necessary. It will also schedule provisioning rechecks
     * for the specified interface.
     */
    private void enableTetheringInternal(int type, boolean enable, ResultReceiver receiver) {
        boolean isProvisioningRequired = isTetherProvisioningRequired();
        switch (type) {
            case ConnectivityManager.TETHERING_WIFI:
                final WifiManager wifiManager =
                        (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager.setWifiApEnabled(null, enable)) {
                    sendTetherResult(receiver, ConnectivityManager.TETHER_ERROR_NO_ERROR);
                    if (enable && isProvisioningRequired) {
                        scheduleProvisioningRechecks(type);
                    }
                } else{
                    sendTetherResult(receiver, ConnectivityManager.TETHER_ERROR_MASTER_ERROR);
                }
                break;
            case ConnectivityManager.TETHERING_USB:
                int result = setUsbTethering(enable);
                if (enable && isProvisioningRequired &&
                        result == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
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
     * Creates a proxy {@link ResultReceiver} which enables tethering if the provsioning result is
     * successful before firing back up to the wrapped receiver.
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
        TetherInterfaceSM sm = null;
        synchronized (mPublicSync) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to Tether an unknown iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
        }
        if (!sm.isAvailable() && !sm.isErrored()) {
            Log.e(TAG, "Tried to Tether an unavailable iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNAVAIL_IFACE;
        }
        sm.sendMessage(TetherInterfaceSM.CMD_TETHER_REQUESTED);
        return ConnectivityManager.TETHER_ERROR_NO_ERROR;
    }

    public int untether(String iface) {
        if (DBG) Log.d(TAG, "Untethering " + iface);
        TetherInterfaceSM sm = null;
        synchronized (mPublicSync) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to Untether an unknown iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
        }
        if (sm.isErrored()) {
            Log.e(TAG, "Tried to Untethered an errored iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNAVAIL_IFACE;
        }
        sm.sendMessage(TetherInterfaceSM.CMD_TETHER_UNREQUESTED);
        return ConnectivityManager.TETHER_ERROR_NO_ERROR;
    }

    public void untetherAll() {
        if (DBG) Log.d(TAG, "Untethering " + mIfaces);
        for (String iface : mIfaces.keySet()) {
            untether(iface);
        }
    }

    public int getLastTetherError(String iface) {
        TetherInterfaceSM sm = null;
        synchronized (mPublicSync) {
            sm = mIfaces.get(iface);
            if (sm == null) {
                Log.e(TAG, "Tried to getLastTetherError on an unknown iface :" + iface +
                        ", ignoring");
                return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
            }
            return sm.getLastError();
        }
    }

    // TODO - move all private methods used only by the state machine into the state machine
    // to clarify what needs synchronized protection.
    private void sendTetherStateChangedBroadcast() {
        if (!getConnectivityManager().isTetheringSupported()) return;

        ArrayList<String> availableList = new ArrayList<String>();
        ArrayList<String> activeList = new ArrayList<String>();
        ArrayList<String> erroredList = new ArrayList<String>();

        boolean wifiTethered = false;
        boolean usbTethered = false;
        boolean bluetoothTethered = false;

        synchronized (mPublicSync) {
            Set ifaces = mIfaces.keySet();
            for (Object iface : ifaces) {
                TetherInterfaceSM sm = mIfaces.get(iface);
                if (sm != null) {
                    if (sm.isErrored()) {
                        erroredList.add((String)iface);
                    } else if (sm.isAvailable()) {
                        availableList.add((String)iface);
                    } else if (sm.isTethered()) {
                        if (isUsb((String)iface)) {
                            usbTethered = true;
                        } else if (isWifi((String)iface)) {
                            wifiTethered = true;
                      } else if (isBluetooth((String)iface)) {
                            bluetoothTethered = true;
                        }
                        activeList.add((String)iface);
                    }
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
            String action = intent.getAction();
            if (action == null) { return; }
            if (action.equals(UsbManager.ACTION_USB_STATE)) {
                synchronized (Tethering.this.mPublicSync) {
                    boolean usbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                    mRndisEnabled = intent.getBooleanExtra(UsbManager.USB_FUNCTION_RNDIS, false);
                    // start tethering if we have a request pending
                    if (usbConnected && mRndisEnabled && mUsbTetherRequested) {
                        tetherUsb(true);
                    }
                    mUsbTetherRequested = false;
                }
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                NetworkInfo networkInfo = (NetworkInfo)intent.getParcelableExtra(
                        ConnectivityManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null &&
                        networkInfo.getDetailedState() != NetworkInfo.DetailedState.FAILED) {
                    if (VDBG) Log.d(TAG, "Tethering got CONNECTIVITY_ACTION");
                    mTetherMasterSM.sendMessage(TetherMasterSM.CMD_UPSTREAM_CHANGED);
                }
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                updateConfiguration();
            }
        }
    }

    private void tetherUsb(boolean enable) {
        if (VDBG) Log.d(TAG, "tetherUsb " + enable);

        String[] ifaces = new String[0];
        try {
            ifaces = mNMService.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces", e);
            return;
        }
        for (String iface : ifaces) {
            if (isUsb(iface)) {
                int result = (enable ? tether(iface) : untether(iface));
                if (result == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    return;
                }
            }
        }
        Log.e(TAG, "unable start or stop USB tethering");
    }

    // configured when we start tethering and unconfig'd on error or conclusion
    private boolean configureUsbIface(boolean enabled) {
        if (VDBG) Log.d(TAG, "configureUsbIface(" + enabled + ")");

        // toggle the USB interfaces
        String[] ifaces = new String[0];
        try {
            ifaces = mNMService.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces", e);
            return false;
        }
        for (String iface : ifaces) {
            if (isUsb(iface)) {
                InterfaceConfiguration ifcg = null;
                try {
                    ifcg = mNMService.getInterfaceConfig(iface);
                    if (ifcg != null) {
                        InetAddress addr = NetworkUtils.numericToInetAddress(USB_NEAR_IFACE_ADDR);
                        ifcg.setLinkAddress(new LinkAddress(addr, USB_PREFIX_LENGTH));
                        if (enabled) {
                            ifcg.setInterfaceUp();
                        } else {
                            ifcg.setInterfaceDown();
                        }
                        ifcg.clearFlag("running");
                        mNMService.setInterfaceConfig(iface, ifcg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error configuring interface " + iface, e);
                    return false;
                }
            }
         }

        return true;
    }

    // TODO - return copies so people can't tamper
    public String[] getTetherableUsbRegexs() {
        return mTetherableUsbRegexs;
    }

    public String[] getTetherableWifiRegexs() {
        return mTetherableWifiRegexs;
    }

    public String[] getTetherableBluetoothRegexs() {
        return mTetherableBluetoothRegexs;
    }

    public int setUsbTethering(boolean enable) {
        if (VDBG) Log.d(TAG, "setUsbTethering(" + enable + ")");
        UsbManager usbManager = (UsbManager)mContext.getSystemService(Context.USB_SERVICE);

        synchronized (mPublicSync) {
            if (enable) {
                if (mRndisEnabled) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        tetherUsb(true);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                } else {
                    mUsbTetherRequested = true;
                    usbManager.setCurrentFunction(UsbManager.USB_FUNCTION_RNDIS);
                }
            } else {
                final long ident = Binder.clearCallingIdentity();
                try {
                    tetherUsb(false);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                if (mRndisEnabled) {
                    usbManager.setCurrentFunction(null);
                }
                mUsbTetherRequested = false;
            }
        }
        return ConnectivityManager.TETHER_ERROR_NO_ERROR;
    }

    public int[] getUpstreamIfaceTypes() {
        int values[];
        synchronized (mPublicSync) {
            updateConfiguration();  // TODO - remove?
            values = new int[mUpstreamIfaceTypes.size()];
            Iterator<Integer> iterator = mUpstreamIfaceTypes.iterator();
            for (int i=0; i < mUpstreamIfaceTypes.size(); i++) {
                values[i] = iterator.next();
            }
        }
        return values;
    }

    private void checkDunRequired() {
        int secureSetting = 2;
        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            secureSetting = tm.getTetherApnRequired();
        }
        synchronized (mPublicSync) {
            // 2 = not set, 0 = DUN not required, 1 = DUN required
            if (secureSetting != 2) {
                int requiredApn = (secureSetting == 1 ?
                        ConnectivityManager.TYPE_MOBILE_DUN :
                        ConnectivityManager.TYPE_MOBILE_HIPRI);
                if (requiredApn == ConnectivityManager.TYPE_MOBILE_DUN) {
                    while (mUpstreamIfaceTypes.contains(MOBILE_TYPE)) {
                        mUpstreamIfaceTypes.remove(MOBILE_TYPE);
                    }
                    while (mUpstreamIfaceTypes.contains(HIPRI_TYPE)) {
                        mUpstreamIfaceTypes.remove(HIPRI_TYPE);
                    }
                    if (mUpstreamIfaceTypes.contains(DUN_TYPE) == false) {
                        mUpstreamIfaceTypes.add(DUN_TYPE);
                    }
                } else {
                    while (mUpstreamIfaceTypes.contains(DUN_TYPE)) {
                        mUpstreamIfaceTypes.remove(DUN_TYPE);
                    }
                    if (mUpstreamIfaceTypes.contains(MOBILE_TYPE) == false) {
                        mUpstreamIfaceTypes.add(MOBILE_TYPE);
                    }
                    if (mUpstreamIfaceTypes.contains(HIPRI_TYPE) == false) {
                        mUpstreamIfaceTypes.add(HIPRI_TYPE);
                    }
                }
            }
            if (mUpstreamIfaceTypes.contains(DUN_TYPE)) {
                mPreferredUpstreamMobileApn = ConnectivityManager.TYPE_MOBILE_DUN;
            } else {
                mPreferredUpstreamMobileApn = ConnectivityManager.TYPE_MOBILE_HIPRI;
            }
        }
    }

    // TODO review API - maybe return ArrayList<String> here and below?
    public String[] getTetheredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            Set keys = mIfaces.keySet();
            for (Object key : keys) {
                TetherInterfaceSM sm = mIfaces.get(key);
                if (sm.isTethered()) {
                    list.add((String)key);
                }
            }
        }
        String[] retVal = new String[list.size()];
        for (int i=0; i < list.size(); i++) {
            retVal[i] = list.get(i);
        }
        return retVal;
    }

    public String[] getTetherableIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            Set keys = mIfaces.keySet();
            for (Object key : keys) {
                TetherInterfaceSM sm = mIfaces.get(key);
                if (sm.isAvailable()) {
                    list.add((String)key);
                }
            }
        }
        String[] retVal = new String[list.size()];
        for (int i=0; i < list.size(); i++) {
            retVal[i] = list.get(i);
        }
        return retVal;
    }

    public String[] getTetheredDhcpRanges() {
        return mDhcpRange;
    }

    public String[] getErroredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            Set keys = mIfaces.keySet();
            for (Object key : keys) {
                TetherInterfaceSM sm = mIfaces.get(key);
                if (sm.isErrored()) {
                    list.add((String)key);
                }
            }
        }
        String[] retVal = new String[list.size()];
        for (int i= 0; i< list.size(); i++) {
            retVal[i] = list.get(i);
        }
        return retVal;
    }

    private void maybeLogMessage(State state, int what) {
        if (DBG) {
            Log.d(TAG, state.getName() + " got " +
                    sMagicDecoderRing.get(what, Integer.toString(what)));
        }
    }

    class TetherInterfaceSM extends StateMachine {
        private static final int BASE_IFACE              = Protocol.BASE_TETHERING + 100;
        // notification from the master SM that it's not in tether mode
        static final int CMD_TETHER_MODE_DEAD            = BASE_IFACE + 1;
        // request from the user that it wants to tether
        static final int CMD_TETHER_REQUESTED            = BASE_IFACE + 2;
        // request from the user that it wants to untether
        static final int CMD_TETHER_UNREQUESTED          = BASE_IFACE + 3;
        // notification that this interface is down
        static final int CMD_INTERFACE_DOWN              = BASE_IFACE + 4;
        // notification that this interface is up
        static final int CMD_INTERFACE_UP                = BASE_IFACE + 5;
        // notification from the master SM that it had an error turning on cellular dun
        static final int CMD_CELL_DUN_ERROR              = BASE_IFACE + 6;
        // notification from the master SM that it had trouble enabling IP Forwarding
        static final int CMD_IP_FORWARDING_ENABLE_ERROR  = BASE_IFACE + 7;
        // notification from the master SM that it had trouble disabling IP Forwarding
        static final int CMD_IP_FORWARDING_DISABLE_ERROR = BASE_IFACE + 8;
        // notification from the master SM that it had trouble starting tethering
        static final int CMD_START_TETHERING_ERROR       = BASE_IFACE + 9;
        // notification from the master SM that it had trouble stopping tethering
        static final int CMD_STOP_TETHERING_ERROR        = BASE_IFACE + 10;
        // notification from the master SM that it had trouble setting the DNS forwarders
        static final int CMD_SET_DNS_FORWARDERS_ERROR    = BASE_IFACE + 11;
        // the upstream connection has changed
        static final int CMD_TETHER_CONNECTION_CHANGED   = BASE_IFACE + 12;

        private State mDefaultState;

        private State mInitialState;
        private State mStartingState;
        private State mTetheredState;

        private State mUnavailableState;

        private boolean mAvailable;
        private boolean mTethered;
        int mLastError;

        String mIfaceName;
        String mMyUpstreamIfaceName;  // may change over time

        boolean mUsb;

        TetherInterfaceSM(String name, Looper looper, boolean usb) {
            super(name, looper);
            mIfaceName = name;
            mUsb = usb;
            setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);

            mInitialState = new InitialState();
            addState(mInitialState);
            mStartingState = new StartingState();
            addState(mStartingState);
            mTetheredState = new TetheredState();
            addState(mTetheredState);
            mUnavailableState = new UnavailableState();
            addState(mUnavailableState);

            setInitialState(mInitialState);
        }

        public String toString() {
            String res = new String();
            res += mIfaceName + " - ";
            IState current = getCurrentState();
            if (current == mInitialState) res += "InitialState";
            if (current == mStartingState) res += "StartingState";
            if (current == mTetheredState) res += "TetheredState";
            if (current == mUnavailableState) res += "UnavailableState";
            if (mAvailable) res += " - Available";
            if (mTethered) res += " - Tethered";
            res += " - lastError =" + mLastError;
            return res;
        }

        public int getLastError() {
            synchronized (Tethering.this.mPublicSync) {
                return mLastError;
            }
        }

        private void setLastError(int error) {
            synchronized (Tethering.this.mPublicSync) {
                mLastError = error;

                if (isErrored()) {
                    if (mUsb) {
                        // note everything's been unwound by this point so nothing to do on
                        // further error..
                        Tethering.this.configureUsbIface(false);
                    }
                }
            }
        }

        public boolean isAvailable() {
            synchronized (Tethering.this.mPublicSync) {
                return mAvailable;
            }
        }

        private void setAvailable(boolean available) {
            synchronized (Tethering.this.mPublicSync) {
                mAvailable = available;
            }
        }

        public boolean isTethered() {
            synchronized (Tethering.this.mPublicSync) {
                return mTethered;
            }
        }

        private void setTethered(boolean tethered) {
            synchronized (Tethering.this.mPublicSync) {
                mTethered = tethered;
            }
        }

        public boolean isErrored() {
            synchronized (Tethering.this.mPublicSync) {
                return (mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR);
            }
        }

        class InitialState extends State {
            @Override
            public void enter() {
                setAvailable(true);
                setTethered(false);
                sendTetherStateChangedBroadcast();
            }

            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_REQUESTED:
                        setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_REQUESTED,
                                TetherInterfaceSM.this);
                        transitionTo(mStartingState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        transitionTo(mUnavailableState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class StartingState extends State {
            @Override
            public void enter() {
                setAvailable(false);
                if (mUsb) {
                    if (!Tethering.this.configureUsbIface(true)) {
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);

                        transitionTo(mInitialState);
                        return;
                    }
                }
                sendTetherStateChangedBroadcast();

                // Skipping StartingState
                transitionTo(mTetheredState);
            }
            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    // maybe a parent class?
                    case CMD_TETHER_UNREQUESTED:
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                                break;
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    case CMD_CELL_DUN_ERROR:
                    case CMD_IP_FORWARDING_ENABLE_ERROR:
                    case CMD_IP_FORWARDING_DISABLE_ERROR:
                    case CMD_START_TETHERING_ERROR:
                    case CMD_STOP_TETHERING_ERROR:
                    case CMD_SET_DNS_FORWARDERS_ERROR:
                        setLastErrorAndTransitionToInitialState(
                                ConnectivityManager.TETHER_ERROR_MASTER_ERROR);
                        break;
                    case CMD_INTERFACE_DOWN:
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        transitionTo(mUnavailableState);
                        break;
                    default:
                        retValue = false;
                }
                return retValue;
            }
        }

        class TetheredState extends State {
            @Override
            public void enter() {
                try {
                    mNMService.tetherInterface(mIfaceName);
                } catch (Exception e) {
                    Log.e(TAG, "Error Tethering: " + e.toString());
                    setLastError(ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR);

                    try {
                        mNMService.untetherInterface(mIfaceName);
                    } catch (Exception ee) {
                        Log.e(TAG, "Error untethering after failure!" + ee.toString());
                    }
                    transitionTo(mInitialState);
                    return;
                }
                if (DBG) Log.d(TAG, "Tethered " + mIfaceName);
                setAvailable(false);
                setTethered(true);
                sendTetherStateChangedBroadcast();
            }

            private void cleanupUpstream() {
                if (mMyUpstreamIfaceName != null) {
                    // note that we don't care about errors here.
                    // sometimes interfaces are gone before we get
                    // to remove their rules, which generates errors.
                    // just do the best we can.
                    try {
                        // about to tear down NAT; gather remaining statistics
                        mStatsService.forceUpdate();
                    } catch (Exception e) {
                        if (VDBG) Log.e(TAG, "Exception in forceUpdate: " + e.toString());
                    }
                    try {
                        mNMService.stopInterfaceForwarding(mIfaceName, mMyUpstreamIfaceName);
                    } catch (Exception e) {
                        if (VDBG) Log.e(
                                TAG, "Exception in removeInterfaceForward: " + e.toString());
                    }
                    try {
                        mNMService.disableNat(mIfaceName, mMyUpstreamIfaceName);
                    } catch (Exception e) {
                        if (VDBG) Log.e(TAG, "Exception in disableNat: " + e.toString());
                    }
                    mMyUpstreamIfaceName = null;
                }
                return;
            }

            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                boolean error = false;
                switch (message.what) {
                    case CMD_TETHER_UNREQUESTED:
                    case CMD_INTERFACE_DOWN:
                        cleanupUpstream();
                        try {
                            mNMService.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                            break;
                        }
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        if (message.what == CMD_TETHER_UNREQUESTED) {
                            if (mUsb) {
                                if (!Tethering.this.configureUsbIface(false)) {
                                    setLastError(
                                            ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                                }
                            }
                            transitionTo(mInitialState);
                        } else if (message.what == CMD_INTERFACE_DOWN) {
                            transitionTo(mUnavailableState);
                        }
                        if (DBG) Log.d(TAG, "Untethered " + mIfaceName);
                        break;
                    case CMD_TETHER_CONNECTION_CHANGED:
                        String newUpstreamIfaceName = (String)(message.obj);
                        if ((mMyUpstreamIfaceName == null && newUpstreamIfaceName == null) ||
                                (mMyUpstreamIfaceName != null &&
                                mMyUpstreamIfaceName.equals(newUpstreamIfaceName))) {
                            if (VDBG) Log.d(TAG, "Connection changed noop - dropping");
                            break;
                        }
                        cleanupUpstream();
                        if (newUpstreamIfaceName != null) {
                            try {
                                mNMService.enableNat(mIfaceName, newUpstreamIfaceName);
                                mNMService.startInterfaceForwarding(mIfaceName,
                                        newUpstreamIfaceName);
                            } catch (Exception e) {
                                Log.e(TAG, "Exception enabling Nat: " + e.toString());
                                try {
                                    mNMService.disableNat(mIfaceName, newUpstreamIfaceName);
                                } catch (Exception ee) {}
                                try {
                                    mNMService.untetherInterface(mIfaceName);
                                } catch (Exception ee) {}

                                setLastError(ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR);
                                transitionTo(mInitialState);
                                return true;
                            }
                        }
                        mMyUpstreamIfaceName = newUpstreamIfaceName;
                        break;
                    case CMD_CELL_DUN_ERROR:
                    case CMD_IP_FORWARDING_ENABLE_ERROR:
                    case CMD_IP_FORWARDING_DISABLE_ERROR:
                    case CMD_START_TETHERING_ERROR:
                    case CMD_STOP_TETHERING_ERROR:
                    case CMD_SET_DNS_FORWARDERS_ERROR:
                        error = true;
                        // fall through
                    case CMD_TETHER_MODE_DEAD:
                        cleanupUpstream();
                        try {
                            mNMService.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                            break;
                        }
                        if (error) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_MASTER_ERROR);
                            break;
                        }
                        if (DBG) Log.d(TAG, "Tether lost upstream connection " + mIfaceName);
                        sendTetherStateChangedBroadcast();
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class UnavailableState extends State {
            @Override
            public void enter() {
                setAvailable(false);
                setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
                setTethered(false);
                sendTetherStateChangedBroadcast();
            }
            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case CMD_INTERFACE_UP:
                        transitionTo(mInitialState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        void setLastErrorAndTransitionToInitialState(int error) {
            setLastError(error);
            transitionTo(mInitialState);
        }

    }

    /**
     * A NetworkCallback class that relays information of interest to the
     * tethering master state machine thread for subsequent processing.
     */
    class UpstreamNetworkCallback extends NetworkCallback {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties newLp) {
            mTetherMasterSM.sendMessage(
                    TetherMasterSM.EVENT_UPSTREAM_LINKPROPERTIES_CHANGED,
                    new NetworkState(null, newLp, null, network, null, null));
        }

        @Override
        public void onLost(Network network) {
            mTetherMasterSM.sendMessage(TetherMasterSM.EVENT_UPSTREAM_LOST, network);
        }
    }

    /**
     * A class to centralize all the network and link properties information
     * pertaining to the current and any potential upstream network.
     *
     * Calling #start() registers two callbacks: one to track the system default
     * network and a second to specifically observe TYPE_MOBILE_DUN networks.
     *
     * The methods and data members of this class are only to be accessed and
     * modified from the tethering master state machine thread. Any other
     * access semantics would necessitate the addition of locking.
     *
     * TODO: Investigate whether more "upstream-specific" logic/functionality
     * could/should be moved here.
     */
    class UpstreamNetworkMonitor {
        final HashMap<Network, NetworkState> mNetworkMap = new HashMap();
        NetworkCallback mDefaultNetworkCallback;
        NetworkCallback mDunTetheringCallback;

        void start() {
            stop();

            mDefaultNetworkCallback = new UpstreamNetworkCallback();
            getConnectivityManager().registerDefaultNetworkCallback(mDefaultNetworkCallback);

            final NetworkRequest dunTetheringRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_DUN)
                    .build();
            mDunTetheringCallback = new UpstreamNetworkCallback();
            getConnectivityManager().registerNetworkCallback(
                    dunTetheringRequest, mDunTetheringCallback);
        }

        void stop() {
            if (mDefaultNetworkCallback != null) {
                getConnectivityManager().unregisterNetworkCallback(mDefaultNetworkCallback);
                mDefaultNetworkCallback = null;
            }

            if (mDunTetheringCallback != null) {
                getConnectivityManager().unregisterNetworkCallback(mDunTetheringCallback);
                mDunTetheringCallback = null;
            }

            mNetworkMap.clear();
        }

        // Returns true if these updated LinkProperties pertain to the current
        // upstream network interface, false otherwise (or if there is not
        // currently any upstream tethering interface).
        boolean processLinkPropertiesChanged(NetworkState networkState) {
            if (networkState == null ||
                    networkState.network == null ||
                    networkState.linkProperties == null) {
                return false;
            }

            mNetworkMap.put(networkState.network, networkState);

            if (mCurrentUpstreamIface != null) {
                for (String ifname : networkState.linkProperties.getAllInterfaceNames()) {
                    if (mCurrentUpstreamIface.equals(ifname)) {
                        return true;
                    }
                }
            }
            return false;
        }

        void processNetworkLost(Network network) {
            if (network != null) {
                mNetworkMap.remove(network);
            }
        }
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
        static final int EVENT_UPSTREAM_LINKPROPERTIES_CHANGED  = BASE_MASTER + 5;
        static final int EVENT_UPSTREAM_LOST                    = BASE_MASTER + 6;

        // This indicates what a timeout event relates to.  A state that
        // sends itself a delayed timeout event and handles incoming timeout events
        // should inc this when it is entered and whenever it sends a new timeout event.
        // We do not flush the old ones.
        private int mSequenceNumber;

        private State mInitialState;
        private State mTetherModeAliveState;

        private State mSetIpForwardingEnabledErrorState;
        private State mSetIpForwardingDisabledErrorState;
        private State mStartTetheringErrorState;
        private State mStopTetheringErrorState;
        private State mSetDnsForwardersErrorState;

        private ArrayList<TetherInterfaceSM> mNotifyList;

        private int mMobileApnReserved = ConnectivityManager.TYPE_NONE;
        private NetworkCallback mMobileUpstreamCallback;

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

            mNotifyList = new ArrayList<TetherInterfaceSM>();
            setInitialState(mInitialState);
        }

        class TetherMasterUtilState extends State {
            @Override
            public boolean processMessage(Message m) {
                return false;
            }

            protected boolean turnOnUpstreamMobileConnection(int apnType) {
                if (apnType == ConnectivityManager.TYPE_NONE) { return false; }

                if (apnType != mMobileApnReserved) {
                    // Unregister any previous mobile upstream callback because
                    // this request, if any, will be different.
                    turnOffUpstreamMobileConnection();
                }

                if (mMobileUpstreamCallback != null) {
                    // Looks like we already filed a request for this apnType.
                    return true;
                }

                switch (apnType) {
                    case ConnectivityManager.TYPE_MOBILE_DUN:
                    case ConnectivityManager.TYPE_MOBILE:
                    case ConnectivityManager.TYPE_MOBILE_HIPRI:
                        mMobileApnReserved = apnType;
                        break;
                    default:
                        return false;
                }

                final NetworkRequest.Builder builder = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
                if (apnType == ConnectivityManager.TYPE_MOBILE_DUN) {
                    builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                           .addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
                } else {
                    builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                }
                final NetworkRequest mobileUpstreamRequest = builder.build();

                // The UpstreamNetworkMonitor's callback will be notified.
                // Therefore, to avoid duplicate notifications, we only register a no-op.
                mMobileUpstreamCallback = new NetworkCallback();

                // TODO: Change the timeout from 0 (no onUnavailable callback) to use some
                // moderate callback time (once timeout callbacks are implemented). This might
                // be useful for updating some UI. Additionally, we should definitely log a
                // message to aid in any subsequent debugging
                if (DBG) Log.d(TAG, "requesting mobile upstream network: " + mobileUpstreamRequest);
                getConnectivityManager().requestNetwork(
                        mobileUpstreamRequest, mMobileUpstreamCallback, 0, apnType);
                return true;
            }

            protected void turnOffUpstreamMobileConnection() {
                if (mMobileUpstreamCallback != null) {
                    getConnectivityManager().unregisterNetworkCallback(mMobileUpstreamCallback);
                    mMobileUpstreamCallback = null;
                }
                mMobileApnReserved = ConnectivityManager.TYPE_NONE;
            }

            protected boolean turnOnMasterTetherSettings() {
                try {
                    mNMService.setIpForwardingEnabled(true);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingEnabledErrorState);
                    return false;
                }
                try {
                    mNMService.startTethering(mDhcpRange);
                } catch (Exception e) {
                    try {
                        mNMService.stopTethering();
                        mNMService.startTethering(mDhcpRange);
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
                int upType = ConnectivityManager.TYPE_NONE;
                String iface = null;

                updateConfiguration(); // TODO - remove?

                synchronized (mPublicSync) {
                    if (VDBG) {
                        Log.d(TAG, "chooseUpstreamType has upstream iface types:");
                        for (Integer netType : mUpstreamIfaceTypes) {
                            Log.d(TAG, " " + netType);
                        }
                    }

                    for (Integer netType : mUpstreamIfaceTypes) {
                        NetworkInfo info =
                                getConnectivityManager().getNetworkInfo(netType.intValue());
                        if ((info != null) && info.isConnected()) {
                            upType = netType.intValue();
                            break;
                        }
                    }
                }

                if (DBG) {
                    Log.d(TAG, "chooseUpstreamType(" + tryCell + "),"
                            + " preferredApn="
                            + ConnectivityManager.getNetworkTypeName(mPreferredUpstreamMobileApn)
                            + ", got type="
                            + ConnectivityManager.getNetworkTypeName(upType));
                }

                switch (upType) {
                    case ConnectivityManager.TYPE_MOBILE_DUN:
                    case ConnectivityManager.TYPE_MOBILE_HIPRI:
                        // If we're on DUN, put our own grab on it.
                        turnOnUpstreamMobileConnection(upType);
                        break;
                    case ConnectivityManager.TYPE_NONE:
                        if (tryCell &&
                                turnOnUpstreamMobileConnection(mPreferredUpstreamMobileApn)) {
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
                        turnOffUpstreamMobileConnection();
                        break;
                }

                if (upType != ConnectivityManager.TYPE_NONE) {
                    LinkProperties linkProperties =
                            getConnectivityManager().getLinkProperties(upType);
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
                        Network network = getConnectivityManager().getNetworkForType(upType);
                        if (network == null) {
                            Log.e(TAG, "No Network for upstream type " + upType + "!");
                        }
                        setDnsForwarders(network, linkProperties);
                    }
                }
                notifyTetheredOfNewUpstreamIface(iface);
            }

            protected void setDnsForwarders(final Network network, final LinkProperties lp) {
                String[] dnsServers = mDefaultDnsServers;
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
                for (TetherInterfaceSM sm : mNotifyList) {
                    sm.sendMessage(TetherInterfaceSM.CMD_TETHER_CONNECTION_CHANGED,
                            ifaceName);
                }
            }
        }

        private final AtomicInteger mSimBcastGenerationNumber = new AtomicInteger(0);
        private SimChangeBroadcastReceiver mBroadcastReceiver = null;

        private void startListeningForSimChanges() {
            if (DBG) Log.d(TAG, "startListeningForSimChanges");
            if (mBroadcastReceiver == null) {
                mBroadcastReceiver = new SimChangeBroadcastReceiver(
                        mSimBcastGenerationNumber.incrementAndGet());
                final IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

                mContext.registerReceiver(mBroadcastReceiver, filter);
            }
        }

        private void stopListeningForSimChanges() {
            if (DBG) Log.d(TAG, "stopListeningForSimChanges");
            if (mBroadcastReceiver != null) {
                mSimBcastGenerationNumber.incrementAndGet();
                mContext.unregisterReceiver(mBroadcastReceiver);
                mBroadcastReceiver = null;
            }
        }

        class SimChangeBroadcastReceiver extends BroadcastReceiver {
            // used to verify this receiver is still current
            final private int mGenerationNumber;

            // we're interested in edge-triggered LOADED notifications, so
            // ignore LOADED unless we saw an ABSENT state first
            private boolean mSimAbsentSeen = false;

            public SimChangeBroadcastReceiver(int generationNumber) {
                super();
                mGenerationNumber = generationNumber;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                if (DBG) {
                    Log.d(TAG, "simchange mGenerationNumber=" + mGenerationNumber +
                            ", current generationNumber=" + mSimBcastGenerationNumber.get());
                }
                if (mGenerationNumber != mSimBcastGenerationNumber.get()) return;

                final String state =
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);

                Log.d(TAG, "got Sim changed to state " + state + ", mSimAbsentSeen=" +
                        mSimAbsentSeen);
                if (!mSimAbsentSeen && IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(state)) {
                    mSimAbsentSeen = true;
                }

                if (mSimAbsentSeen && IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(state)) {
                    mSimAbsentSeen = false;
                    try {
                        if (mContext.getResources().getString(com.android.internal.R.string.
                                config_mobile_hotspot_provision_app_no_ui).isEmpty() == false) {
                            ArrayList<Integer> tethered = new ArrayList<Integer>();
                            synchronized (mPublicSync) {
                                Set ifaces = mIfaces.keySet();
                                for (Object iface : ifaces) {
                                    TetherInterfaceSM sm = mIfaces.get(iface);
                                    if (sm != null && sm.isTethered()) {
                                        if (isUsb((String)iface)) {
                                            tethered.add(new Integer(
                                                    ConnectivityManager.TETHERING_USB));
                                        } else if (isWifi((String)iface)) {
                                            tethered.add(new Integer(
                                                    ConnectivityManager.TETHERING_WIFI));
                                        } else if (isBluetooth((String)iface)) {
                                            tethered.add(new Integer(
                                                    ConnectivityManager.TETHERING_BLUETOOTH));
                                        }
                                    }
                                }
                            }
                            for (int tetherType : tethered) {
                                Intent startProvIntent = new Intent();
                                startProvIntent.putExtra(
                                        ConnectivityManager.EXTRA_ADD_TETHER_TYPE, tetherType);
                                startProvIntent.putExtra(
                                        ConnectivityManager.EXTRA_RUN_PROVISION, true);
                                startProvIntent.setComponent(TETHER_SERVICE);
                                mContext.startServiceAsUser(startProvIntent, UserHandle.CURRENT);
                            }
                            Log.d(TAG, "re-evaluate provisioning");
                        } else {
                            Log.d(TAG, "no prov-check needed for new SIM");
                        }
                    } catch (Resources.NotFoundException e) {
                        Log.d(TAG, "no prov-check needed for new SIM");
                        // not defined, do nothing
                    }
                }
            }
        }

        class InitialState extends TetherMasterUtilState {
            @Override
            public void enter() {
            }
            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode requested by " + who);
                        mNotifyList.add(who);
                        transitionTo(mTetherModeAliveState);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode unrequested by " + who);
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(who);
                        }
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class TetherModeAliveState extends TetherMasterUtilState {
            boolean mTryCell = true;
            @Override
            public void enter() {
                // TODO: examine if we should check the return value.
                turnOnMasterTetherSettings(); // may transition us out
                startListeningForSimChanges();
                mUpstreamNetworkMonitor.start();

                mTryCell = true;  // better try something first pass or crazy tests cases will fail
                chooseUpstreamType(mTryCell);
                mTryCell = !mTryCell;
            }
            @Override
            public void exit() {
                // TODO: examine if we should check the return value.
                turnOffUpstreamMobileConnection();
                mUpstreamNetworkMonitor.stop();
                stopListeningForSimChanges();
                notifyTetheredOfNewUpstreamIface(null);
            }
            @Override
            public boolean processMessage(Message message) {
                maybeLogMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode requested by " + who);
                        mNotifyList.add(who);
                        who.sendMessage(TetherInterfaceSM.CMD_TETHER_CONNECTION_CHANGED,
                                mCurrentUpstreamIface);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode unrequested by " + who);
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            if (DBG) Log.d(TAG, "TetherModeAlive removing notifyee " + who);
                            mNotifyList.remove(index);
                            if (mNotifyList.isEmpty()) {
                                turnOffMasterTetherSettings(); // transitions appropriately
                            } else {
                                if (DBG) {
                                    Log.d(TAG, "TetherModeAlive still has " + mNotifyList.size() +
                                            " live requests:");
                                    for (Object o : mNotifyList) Log.d(TAG, "  " + o);
                                }
                            }
                        } else {
                           Log.e(TAG, "TetherModeAliveState UNREQUESTED has unknown who: " + who);
                        }
                        break;
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
                    case EVENT_UPSTREAM_LINKPROPERTIES_CHANGED:
                        NetworkState state = (NetworkState) message.obj;
                        if (mUpstreamNetworkMonitor.processLinkPropertiesChanged(state)) {
                            setDnsForwarders(state.network, state.linkProperties);
                        } else if (mCurrentUpstreamIface == null) {
                            // If we have no upstream interface, try to run through upstream
                            // selection again.  If, for example, IPv4 connectivity has shown up
                            // after IPv6 (e.g., 464xlat became available) we want the chance to
                            // notice and act accordingly.
                            chooseUpstreamType(false);
                        }
                        break;
                    case EVENT_UPSTREAM_LOST:
                        // TODO: Re-evaluate possible upstreams. Currently upstream reevaluation
                        // is triggered via received CONNECTIVITY_ACTION broadcasts that result
                        // in being passed a TetherMasterSM.CMD_UPSTREAM_CHANGED.
                        mUpstreamNetworkMonitor.processNetworkLost((Network) message.obj);
                        break;
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
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        who.sendMessage(mErrorNotification);
                        break;
                    default:
                       retValue = false;
                }
                return retValue;
            }
            void notify(int msgType) {
                mErrorNotification = msgType;
                for (Object o : mNotifyList) {
                    TetherInterfaceSM sm = (TetherInterfaceSM)o;
                    sm.sendMessage(msgType);
                }
            }

        }
        class SetIpForwardingEnabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingEnabled");
                notify(TetherInterfaceSM.CMD_IP_FORWARDING_ENABLE_ERROR);
            }
        }

        class SetIpForwardingDisabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingDisabled");
                notify(TetherInterfaceSM.CMD_IP_FORWARDING_DISABLE_ERROR);
            }
        }

        class StartTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in startTethering");
                notify(TetherInterfaceSM.CMD_START_TETHERING_ERROR);
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class StopTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in stopTethering");
                notify(TetherInterfaceSM.CMD_STOP_TETHERING_ERROR);
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class SetDnsForwardersErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setDnsForwarders");
                notify(TetherInterfaceSM.CMD_SET_DNS_FORWARDERS_ERROR);
                try {
                    mNMService.stopTethering();
                } catch (Exception e) {}
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
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
        pw.print("mUpstreamIfaceTypes:");
        synchronized (mPublicSync) {
            for (Integer netType : mUpstreamIfaceTypes) {
                pw.print(" " + ConnectivityManager.getNetworkTypeName(netType));
            }
            pw.println();

            pw.println("Tether state:");
            pw.increaseIndent();
            for (Object o : mIfaces.values()) {
                pw.println(o);
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
        return;
    }
}
