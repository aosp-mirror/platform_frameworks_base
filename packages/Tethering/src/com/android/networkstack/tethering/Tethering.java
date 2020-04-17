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

package com.android.networkstack.tethering;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.hardware.usb.UsbManager.USB_CONFIGURED;
import static android.hardware.usb.UsbManager.USB_CONNECTED;
import static android.hardware.usb.UsbManager.USB_FUNCTION_NCM;
import static android.hardware.usb.UsbManager.USB_FUNCTION_RNDIS;
import static android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.EXTRA_NETWORK_INFO;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.TetheringManager.ACTION_TETHER_STATE_CHANGED;
import static android.net.TetheringManager.EXTRA_ACTIVE_LOCAL_ONLY;
import static android.net.TetheringManager.EXTRA_ACTIVE_TETHER;
import static android.net.TetheringManager.EXTRA_AVAILABLE_TETHER;
import static android.net.TetheringManager.EXTRA_ERRORED_TETHER;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_INVALID;
import static android.net.TetheringManager.TETHERING_NCM;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;
import static android.net.TetheringManager.TETHER_ERROR_UNAVAIL_IFACE;
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_IFACE;
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_TYPE;
import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_FAILED;
import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STARTED;
import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STOPPED;
import static android.net.util.TetheringMessageBase.BASE_MASTER;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_UNSPECIFIED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.networkstack.tethering.TetheringNotificationUpdater.DOWNSTREAM_NONE;

import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.IIntResultListener;
import android.net.INetd;
import android.net.ITetheringEventCallback;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TetherStatesParcel;
import android.net.TetheredClient;
import android.net.TetheringCallbackStartedParcel;
import android.net.TetheringConfigurationParcel;
import android.net.TetheringRequestParcel;
import android.net.ip.IpServer;
import android.net.shared.NetdUtils;
import android.net.util.BaseNetdUnsolicitedEventListener;
import android.net.util.InterfaceSet;
import android.net.util.PrefixUtils;
import android.net.util.SharedLog;
import android.net.util.TetheringUtils;
import android.net.util.VersionedBroadcastListener;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 *
 * This class holds much of the business logic to allow Android devices
 * to act as IP gateways via USB, BT, and WiFi interfaces.
 */
public class Tethering {

    private static final String TAG = Tethering.class.getSimpleName();
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    private static final Class[] sMessageClasses = {
            Tethering.class, TetherMasterSM.class, IpServer.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(sMessageClasses);
    // Keep in sync with NETID_UNSET in system/netd/include/netid_client.h
    private static final int NETID_UNSET = 0;

    private static class TetherState {
        public final IpServer ipServer;
        public int lastState;
        public int lastError;

        TetherState(IpServer ipServer) {
            this.ipServer = ipServer;
            // Assume all state machines start out available and with no errors.
            lastState = IpServer.STATE_AVAILABLE;
            lastError = TETHER_ERROR_NO_ERROR;
        }

        public boolean isCurrentlyServing() {
            switch (lastState) {
                case IpServer.STATE_TETHERED:
                case IpServer.STATE_LOCAL_ONLY:
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * Cookie added when registering {@link android.net.TetheringManager.TetheringEventCallback}.
     */
    private static class CallbackCookie {
        public final boolean hasListClientsPermission;

        private CallbackCookie(boolean hasListClientsPermission) {
            this.hasListClientsPermission = hasListClientsPermission;
        }
    }

    private final SharedLog mLog = new SharedLog(TAG);
    private final RemoteCallbackList<ITetheringEventCallback> mTetheringEventCallbacks =
            new RemoteCallbackList<>();
    // Currently active tethering requests per tethering type. Only one of each type can be
    // requested at a time. After a tethering type is requested, the map keeps tethering parameters
    // to be used after the interface comes up asynchronously.
    private final SparseArray<TetheringRequestParcel> mActiveTetheringRequests =
            new SparseArray<>();

    // used to synchronize public access to members
    private final Object mPublicSync;
    private final Context mContext;
    private final ArrayMap<String, TetherState> mTetherStates;
    private final BroadcastReceiver mStateReceiver;
    private final Looper mLooper;
    private final StateMachine mTetherMasterSM;
    private final OffloadController mOffloadController;
    private final UpstreamNetworkMonitor mUpstreamNetworkMonitor;
    // TODO: Figure out how to merge this and other downstream-tracking objects
    // into a single coherent structure.
    // Use LinkedHashSet for predictable ordering order for ConnectedClientsTracker.
    private final LinkedHashSet<IpServer> mForwardedDownstreams;
    private final VersionedBroadcastListener mCarrierConfigChange;
    private final TetheringDependencies mDeps;
    private final EntitlementManager mEntitlementMgr;
    private final Handler mHandler;
    private final INetd mNetd;
    private final NetdCallback mNetdCallback;
    private final UserRestrictionActionListener mTetheringRestriction;
    private final ActiveDataSubIdListener mActiveDataSubIdListener;
    private final ConnectedClientsTracker mConnectedClientsTracker;
    private final TetheringThreadExecutor mExecutor;
    private final TetheringNotificationUpdater mNotificationUpdater;
    private int mActiveDataSubId = INVALID_SUBSCRIPTION_ID;
    // All the usage of mTetheringEventCallback should run in the same thread.
    private ITetheringEventCallback mTetheringEventCallback = null;

    private volatile TetheringConfiguration mConfig;
    private InterfaceSet mCurrentUpstreamIfaceSet;

    private boolean mRndisEnabled;       // track the RNDIS function enabled state
    // True iff. WiFi tethering should be started when soft AP is ready.
    private boolean mWifiTetherRequested;
    private Network mTetherUpstream;
    private TetherStatesParcel mTetherStatesParcel;
    private boolean mDataSaverEnabled = false;
    private String mWifiP2pTetherInterface = null;
    private int mOffloadStatus = TETHER_HARDWARE_OFFLOAD_STOPPED;

    @GuardedBy("mPublicSync")
    private EthernetManager.TetheredInterfaceRequest mEthernetIfaceRequest;
    @GuardedBy("mPublicSync")
    private String mConfiguredEthernetIface;
    @GuardedBy("mPublicSync")
    private EthernetCallback mEthernetCallback;

    public Tethering(TetheringDependencies deps) {
        mLog.mark("Tethering.constructed");
        mDeps = deps;
        mContext = mDeps.getContext();
        mNetd = mDeps.getINetd(mContext);
        mLooper = mDeps.getTetheringLooper();
        mNotificationUpdater = mDeps.getNotificationUpdater(mContext, mLooper);

        mPublicSync = new Object();

        mTetherStates = new ArrayMap<>();
        mConnectedClientsTracker = new ConnectedClientsTracker();

        mTetherMasterSM = new TetherMasterSM("TetherMaster", mLooper, deps);
        mTetherMasterSM.start();

        final NetworkStatsManager statsManager =
                (NetworkStatsManager) mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
        mHandler = mTetherMasterSM.getHandler();
        mOffloadController = new OffloadController(mHandler,
                mDeps.getOffloadHardwareInterface(mHandler, mLog), mContext.getContentResolver(),
                statsManager, mLog);
        mUpstreamNetworkMonitor = mDeps.getUpstreamNetworkMonitor(mContext, mTetherMasterSM, mLog,
                TetherMasterSM.EVENT_UPSTREAM_CALLBACK);
        mForwardedDownstreams = new LinkedHashSet<>();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CARRIER_CONFIG_CHANGED);
        // EntitlementManager will send EVENT_UPSTREAM_PERMISSION_CHANGED when cellular upstream
        // permission is changed according to entitlement check result.
        mEntitlementMgr = mDeps.getEntitlementManager(mContext, mTetherMasterSM, mLog,
                TetherMasterSM.EVENT_UPSTREAM_PERMISSION_CHANGED);
        mEntitlementMgr.setOnUiEntitlementFailedListener((int downstream) -> {
            mLog.log("OBSERVED UiEnitlementFailed");
            stopTethering(downstream);
        });
        mEntitlementMgr.setTetheringConfigurationFetcher(() -> {
            return mConfig;
        });

        mCarrierConfigChange = new VersionedBroadcastListener(
                "CarrierConfigChangeListener", mContext, mHandler, filter,
                (Intent ignored) -> {
                    mLog.log("OBSERVED carrier config change");
                    updateConfiguration();
                    mEntitlementMgr.reevaluateSimCardProvisioning(mConfig);
                });

        mStateReceiver = new StateReceiver();

        final UserManager userManager = (UserManager) mContext.getSystemService(
                Context.USER_SERVICE);
        mTetheringRestriction = new UserRestrictionActionListener(
                userManager, this, mNotificationUpdater);
        mExecutor = new TetheringThreadExecutor(mHandler);
        mActiveDataSubIdListener = new ActiveDataSubIdListener(mExecutor);
        mNetdCallback = new NetdCallback();

        // Load tethering configuration.
        updateConfiguration();
    }

    /**
     * Start to register callbacks.
     * Call this function when tethering is ready to handle callback events.
     */
    public void startStateMachineUpdaters() {
        try {
            mNetd.registerUnsolicitedEventListener(mNetdCallback);
        } catch (RemoteException e) {
            mLog.e("Unable to register netd UnsolicitedEventListener");
        }
        mCarrierConfigChange.startListening();
        mContext.getSystemService(TelephonyManager.class).listen(mActiveDataSubIdListener,
                PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_STATE);
        filter.addAction(CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        filter.addAction(ACTION_RESTRICT_BACKGROUND_CHANGED);
        mContext.registerReceiver(mStateReceiver, filter, null, mHandler);

        final IntentFilter noUpstreamFilter = new IntentFilter();
        noUpstreamFilter.addAction(TetheringNotificationUpdater.ACTION_DISABLE_TETHERING);
        mContext.registerReceiver(
                mStateReceiver, noUpstreamFilter, PERMISSION_MAINLINE_NETWORK_STACK, mHandler);

        final WifiManager wifiManager = getWifiManager();
        if (wifiManager != null) {
            wifiManager.registerSoftApCallback(mExecutor, new TetheringSoftApCallback());
        }

        startTrackDefaultNetwork();
    }

    private class TetheringThreadExecutor implements Executor {
        private final Handler mTetherHandler;
        TetheringThreadExecutor(Handler handler) {
            mTetherHandler = handler;
        }
        @Override
        public void execute(Runnable command) {
            if (!mTetherHandler.post(command)) {
                throw new RejectedExecutionException(mTetherHandler + " is shutting down");
            }
        }
    }

    private class ActiveDataSubIdListener extends PhoneStateListener {
        ActiveDataSubIdListener(Executor executor) {
            super(executor);
        }

        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            mLog.log("OBSERVED active data subscription change, from " + mActiveDataSubId
                    + " to " + subId);
            if (subId == mActiveDataSubId) return;

            mActiveDataSubId = subId;
            updateConfiguration();
            mNotificationUpdater.onActiveDataSubscriptionIdChanged(subId);
            // To avoid launching unexpected provisioning checks, ignore re-provisioning
            // when no CarrierConfig loaded yet. Assume reevaluateSimCardProvisioning()
            // will be triggered again when CarrierConfig is loaded.
            if (mEntitlementMgr.getCarrierConfig(mConfig) != null) {
                mEntitlementMgr.reevaluateSimCardProvisioning(mConfig);
            } else {
                mLog.log("IGNORED reevaluate provisioning, no carrier config loaded");
            }
        }
    }

    private WifiManager getWifiManager() {
        return (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    // NOTE: This is always invoked on the mLooper thread.
    private void updateConfiguration() {
        mConfig = mDeps.generateTetheringConfiguration(mContext, mLog, mActiveDataSubId);
        mUpstreamNetworkMonitor.updateMobileRequiresDun(mConfig.isDunRequired);
        reportConfigurationChanged(mConfig.toStableParcelable());
    }

    private void maybeDunSettingChanged() {
        final boolean isDunRequired = TetheringConfiguration.checkDunRequired(mContext);
        if (isDunRequired == mConfig.isDunRequired) return;
        updateConfiguration();
    }

    private class NetdCallback extends BaseNetdUnsolicitedEventListener {
        @Override
        public void onInterfaceChanged(String ifName, boolean up) {
            mHandler.post(() -> interfaceStatusChanged(ifName, up));
        }

        @Override
        public void onInterfaceLinkStateChanged(String ifName, boolean up) {
            mHandler.post(() -> interfaceLinkStateChanged(ifName, up));
        }

        @Override
        public void onInterfaceAdded(String ifName) {
            mHandler.post(() -> interfaceAdded(ifName));
        }

        @Override
        public void onInterfaceRemoved(String ifName) {
            mHandler.post(() -> interfaceRemoved(ifName));
        }
    }

    private class TetheringSoftApCallback implements WifiManager.SoftApCallback {
        // TODO: Remove onStateChanged override when this method has default on
        // WifiManager#SoftApCallback interface.
        // Wifi listener for state change of the soft AP
        @Override
        public void onStateChanged(final int state, final int failureReason) {
            // Nothing
        }

        // Called by wifi when the number of soft AP clients changed.
        @Override
        public void onConnectedClientsChanged(final List<WifiClient> clients) {
            updateConnectedClients(clients);
        }
    }

    void interfaceStatusChanged(String iface, boolean up) {
        // Never called directly: only called from interfaceLinkStateChanged.
        // See NetlinkHandler.cpp: notifyInterfaceChanged.
        if (VDBG) Log.d(TAG, "interfaceStatusChanged " + iface + ", " + up);
        synchronized (mPublicSync) {
            if (up) {
                maybeTrackNewInterfaceLocked(iface);
            } else {
                if (ifaceNameToType(iface) == TETHERING_BLUETOOTH) {
                    stopTrackingInterfaceLocked(iface);
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

    void interfaceLinkStateChanged(String iface, boolean up) {
        interfaceStatusChanged(iface, up);
    }

    private int ifaceNameToType(String iface) {
        final TetheringConfiguration cfg = mConfig;

        if (cfg.isWifi(iface)) {
            return TETHERING_WIFI;
        } else if (cfg.isWifiP2p(iface)) {
            return TETHERING_WIFI_P2P;
        } else if (cfg.isUsb(iface)) {
            return TETHERING_USB;
        } else if (cfg.isBluetooth(iface)) {
            return TETHERING_BLUETOOTH;
        } else if (cfg.isNcm(iface)) {
            return TETHERING_NCM;
        }
        return TETHERING_INVALID;
    }

    void interfaceAdded(String iface) {
        if (VDBG) Log.d(TAG, "interfaceAdded " + iface);
        synchronized (mPublicSync) {
            maybeTrackNewInterfaceLocked(iface);
        }
    }

    void interfaceRemoved(String iface) {
        if (VDBG) Log.d(TAG, "interfaceRemoved " + iface);
        synchronized (mPublicSync) {
            stopTrackingInterfaceLocked(iface);
        }
    }

    void startTethering(final TetheringRequestParcel request, final IIntResultListener listener) {
        mHandler.post(() -> {
            final TetheringRequestParcel unfinishedRequest = mActiveTetheringRequests.get(
                    request.tetheringType);
            // If tethering is already enabled with a different request,
            // disable before re-enabling.
            if (unfinishedRequest != null
                    && !TetheringUtils.isTetheringRequestEquals(unfinishedRequest, request)) {
                enableTetheringInternal(request.tetheringType, false /* disabled */, null);
                mEntitlementMgr.stopProvisioningIfNeeded(request.tetheringType);
            }
            mActiveTetheringRequests.put(request.tetheringType, request);

            mEntitlementMgr.startProvisioningIfNeeded(request.tetheringType,
                    request.showProvisioningUi);
            enableTetheringInternal(request.tetheringType, true /* enabled */, listener);
        });
    }

    void stopTethering(int type) {
        mHandler.post(() -> {
            mActiveTetheringRequests.remove(type);

            enableTetheringInternal(type, false /* disabled */, null);
            mEntitlementMgr.stopProvisioningIfNeeded(type);
        });
    }

    /**
     * Enables or disables tethering for the given type. If provisioning is required, it will
     * schedule provisioning rechecks for the specified interface.
     */
    private void enableTetheringInternal(int type, boolean enable,
            final IIntResultListener listener) {
        int result = TETHER_ERROR_NO_ERROR;
        switch (type) {
            case TETHERING_WIFI:
                result = setWifiTethering(enable);
                break;
            case TETHERING_USB:
                result = setUsbTethering(enable);
                break;
            case TETHERING_BLUETOOTH:
                setBluetoothTethering(enable, listener);
                break;
            case TETHERING_NCM:
                result = setNcmTethering(enable);
                break;
            case TETHERING_ETHERNET:
                result = setEthernetTethering(enable);
                break;
            default:
                Log.w(TAG, "Invalid tether type.");
                result = TETHER_ERROR_UNKNOWN_TYPE;
        }

        // The result of Bluetooth tethering will be sent by #setBluetoothTethering.
        if (type != TETHERING_BLUETOOTH) {
            sendTetherResult(listener, result, type);
        }
    }

    private void sendTetherResult(final IIntResultListener listener, final int result,
            final int type) {
        if (listener != null) {
            try {
                listener.onResult(result);
            } catch (RemoteException e) { }
        }

        // If changing tethering fail, remove corresponding request
        // no matter who trigger the start/stop.
        if (result != TETHER_ERROR_NO_ERROR) mActiveTetheringRequests.remove(type);
    }

    private int setWifiTethering(final boolean enable) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mPublicSync) {
                final WifiManager mgr = getWifiManager();
                if (mgr == null) {
                    mLog.e("setWifiTethering: failed to get WifiManager!");
                    return TETHER_ERROR_SERVICE_UNAVAIL;
                }
                if ((enable && mgr.startSoftAp(null /* use existing wifi config */))
                        || (!enable && mgr.stopSoftAp())) {
                    mWifiTetherRequested = enable;
                    return TETHER_ERROR_NO_ERROR;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return TETHER_ERROR_INTERNAL_ERROR;
    }

    private void setBluetoothTethering(final boolean enable, final IIntResultListener listener) {
        final BluetoothAdapter adapter = mDeps.getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Tried to enable bluetooth tethering with null or disabled adapter. null: "
                    + (adapter == null));
            sendTetherResult(listener, TETHER_ERROR_SERVICE_UNAVAIL, TETHERING_BLUETOOTH);
            return;
        }

        adapter.getProfileProxy(mContext, new ServiceListener() {
            @Override
            public void onServiceDisconnected(int profile) { }

            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                // Clear identify is fine because caller already pass tethering permission at
                // ConnectivityService#startTethering()(or stopTethering) before the control comes
                // here. Bluetooth will check tethering permission again that there is
                // Context#getOpPackageName() under BluetoothPan#setBluetoothTethering() to get
                // caller's package name for permission check.
                // Calling BluetoothPan#setBluetoothTethering() here means the package name always
                // be system server. If calling identity is not cleared, that package's uid might
                // not match calling uid and end up in permission denied.
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    ((BluetoothPan) proxy).setBluetoothTethering(enable);
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }
                // TODO: Enabling bluetooth tethering can fail asynchronously here.
                // We should figure out a way to bubble up that failure instead of sending success.
                final int result = (((BluetoothPan) proxy).isTetheringOn() == enable)
                        ? TETHER_ERROR_NO_ERROR
                        : TETHER_ERROR_INTERNAL_ERROR;
                sendTetherResult(listener, result, TETHERING_BLUETOOTH);
                adapter.closeProfileProxy(BluetoothProfile.PAN, proxy);
            }
        }, BluetoothProfile.PAN);
    }

    private int setEthernetTethering(final boolean enable) {
        final EthernetManager em = (EthernetManager) mContext.getSystemService(
                Context.ETHERNET_SERVICE);
        synchronized (mPublicSync) {
            if (enable) {
                if (mEthernetCallback != null) {
                    Log.d(TAG, "Ethernet tethering already started");
                    return TETHER_ERROR_NO_ERROR;
                }

                mEthernetCallback = new EthernetCallback();
                mEthernetIfaceRequest = em.requestTetheredInterface(mExecutor, mEthernetCallback);
            } else {
                stopEthernetTetheringLocked();
            }
        }
        return TETHER_ERROR_NO_ERROR;
    }

    private void stopEthernetTetheringLocked() {
        if (mConfiguredEthernetIface != null) {
            stopTrackingInterfaceLocked(mConfiguredEthernetIface);
            mConfiguredEthernetIface = null;
        }
        if (mEthernetCallback != null) {
            mEthernetIfaceRequest.release();
            mEthernetCallback = null;
            mEthernetIfaceRequest = null;
        }
    }

    private class EthernetCallback implements EthernetManager.TetheredInterfaceCallback {
        @Override
        public void onAvailable(String iface) {
            synchronized (mPublicSync) {
                if (this != mEthernetCallback) {
                    // Ethernet callback arrived after Ethernet tethering stopped. Ignore.
                    return;
                }
                maybeTrackNewInterfaceLocked(iface, TETHERING_ETHERNET);
                changeInterfaceState(iface, IpServer.STATE_TETHERED);
                mConfiguredEthernetIface = iface;
            }
        }

        @Override
        public void onUnavailable() {
            synchronized (mPublicSync) {
                if (this != mEthernetCallback) {
                    // onAvailable called after stopping Ethernet tethering.
                    return;
                }
                stopEthernetTetheringLocked();
            }
        }
    }

    int tether(String iface) {
        return tether(iface, IpServer.STATE_TETHERED);
    }

    private int tether(String iface, int requestedState) {
        if (DBG) Log.d(TAG, "Tethering " + iface);
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                Log.e(TAG, "Tried to Tether an unknown iface: " + iface + ", ignoring");
                return TETHER_ERROR_UNKNOWN_IFACE;
            }
            // Ignore the error status of the interface.  If the interface is available,
            // the errors are referring to past tethering attempts anyway.
            if (tetherState.lastState != IpServer.STATE_AVAILABLE) {
                Log.e(TAG, "Tried to Tether an unavailable iface: " + iface + ", ignoring");
                return TETHER_ERROR_UNAVAIL_IFACE;
            }
            // NOTE: If a CMD_TETHER_REQUESTED message is already in the TISM's queue but not yet
            // processed, this will be a no-op and it will not return an error.
            //
            // This code cannot race with untether() because they both synchronize on mPublicSync.
            // TODO: reexamine the threading and messaging model to totally remove mPublicSync.
            final int type = tetherState.ipServer.interfaceType();
            final TetheringRequestParcel request = mActiveTetheringRequests.get(type, null);
            if (request != null) {
                mActiveTetheringRequests.delete(type);
            }
            tetherState.ipServer.sendMessage(IpServer.CMD_TETHER_REQUESTED, requestedState, 0,
                    request);
            return TETHER_ERROR_NO_ERROR;
        }
    }

    int untether(String iface) {
        if (DBG) Log.d(TAG, "Untethering " + iface);
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                Log.e(TAG, "Tried to Untether an unknown iface :" + iface + ", ignoring");
                return TETHER_ERROR_UNKNOWN_IFACE;
            }
            if (!tetherState.isCurrentlyServing()) {
                Log.e(TAG, "Tried to untether an inactive iface :" + iface + ", ignoring");
                return TETHER_ERROR_UNAVAIL_IFACE;
            }
            tetherState.ipServer.sendMessage(IpServer.CMD_TETHER_UNREQUESTED);
            return TETHER_ERROR_NO_ERROR;
        }
    }

    void untetherAll() {
        stopTethering(TETHERING_WIFI);
        stopTethering(TETHERING_WIFI_P2P);
        stopTethering(TETHERING_USB);
        stopTethering(TETHERING_BLUETOOTH);
        stopTethering(TETHERING_ETHERNET);
    }

    int getLastTetherError(String iface) {
        synchronized (mPublicSync) {
            TetherState tetherState = mTetherStates.get(iface);
            if (tetherState == null) {
                Log.e(TAG, "Tried to getLastTetherError on an unknown iface :" + iface
                        + ", ignoring");
                return TETHER_ERROR_UNKNOWN_IFACE;
            }
            return tetherState.lastError;
        }
    }

    boolean isTetherProvisioningRequired() {
        final TetheringConfiguration cfg = mConfig;
        return mEntitlementMgr.isTetherProvisioningRequired(cfg);
    }

    // TODO: Figure out how to update for local hotspot mode interfaces.
    private void sendTetherStateChangedBroadcast() {
        if (!mDeps.isTetheringSupported()) return;

        final ArrayList<String> availableList = new ArrayList<>();
        final ArrayList<String> tetherList = new ArrayList<>();
        final ArrayList<String> localOnlyList = new ArrayList<>();
        final ArrayList<String> erroredList = new ArrayList<>();
        final ArrayList<Integer> lastErrorList = new ArrayList<>();

        final TetheringConfiguration cfg = mConfig;
        mTetherStatesParcel = new TetherStatesParcel();

        int downstreamTypesMask = DOWNSTREAM_NONE;
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                String iface = mTetherStates.keyAt(i);
                if (tetherState.lastError != TETHER_ERROR_NO_ERROR) {
                    erroredList.add(iface);
                    lastErrorList.add(tetherState.lastError);
                } else if (tetherState.lastState == IpServer.STATE_AVAILABLE) {
                    availableList.add(iface);
                } else if (tetherState.lastState == IpServer.STATE_LOCAL_ONLY) {
                    localOnlyList.add(iface);
                } else if (tetherState.lastState == IpServer.STATE_TETHERED) {
                    if (cfg.isUsb(iface)) {
                        downstreamTypesMask |= (1 << TETHERING_USB);
                    } else if (cfg.isWifi(iface)) {
                        downstreamTypesMask |= (1 << TETHERING_WIFI);
                    } else if (cfg.isBluetooth(iface)) {
                        downstreamTypesMask |= (1 << TETHERING_BLUETOOTH);
                    }
                    tetherList.add(iface);
                }
            }
        }

        mTetherStatesParcel.availableList = availableList.toArray(new String[0]);
        mTetherStatesParcel.tetheredList = tetherList.toArray(new String[0]);
        mTetherStatesParcel.localOnlyList = localOnlyList.toArray(new String[0]);
        mTetherStatesParcel.erroredIfaceList = erroredList.toArray(new String[0]);
        mTetherStatesParcel.lastErrorList = new int[lastErrorList.size()];
        Iterator<Integer> iterator = lastErrorList.iterator();
        for (int i = 0; i < lastErrorList.size(); i++) {
            mTetherStatesParcel.lastErrorList[i] = iterator.next().intValue();
        }
        reportTetherStateChanged(mTetherStatesParcel);

        final Intent bcast = new Intent(ACTION_TETHER_STATE_CHANGED);
        bcast.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        bcast.putStringArrayListExtra(EXTRA_AVAILABLE_TETHER, availableList);
        bcast.putStringArrayListExtra(EXTRA_ACTIVE_LOCAL_ONLY, localOnlyList);
        bcast.putStringArrayListExtra(EXTRA_ACTIVE_TETHER, tetherList);
        bcast.putStringArrayListExtra(EXTRA_ERRORED_TETHER, erroredList);
        mContext.sendStickyBroadcastAsUser(bcast, UserHandle.ALL);
        if (DBG) {
            Log.d(TAG, String.format(
                    "sendTetherStateChangedBroadcast %s=[%s] %s=[%s] %s=[%s] %s=[%s]",
                    "avail", TextUtils.join(",", availableList),
                    "local_only", TextUtils.join(",", localOnlyList),
                    "tether", TextUtils.join(",", tetherList),
                    "error", TextUtils.join(",", erroredList)));
        }

        mNotificationUpdater.onDownstreamChanged(downstreamTypesMask);
    }

    private class StateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context content, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (action.equals(UsbManager.ACTION_USB_STATE)) {
                handleUsbAction(intent);
            } else if (action.equals(CONNECTIVITY_ACTION)) {
                handleConnectivityAction(intent);
            } else if (action.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                handleWifiApAction(intent);
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                handleWifiP2pAction(intent);
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                mLog.log("OBSERVED configuration changed");
                updateConfiguration();
            } else if (action.equals(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)) {
                mLog.log("OBSERVED user restrictions changed");
                handleUserRestrictionAction();
            } else if (action.equals(ACTION_RESTRICT_BACKGROUND_CHANGED)) {
                mLog.log("OBSERVED data saver changed");
                handleDataSaverChanged();
            } else if (action.equals(TetheringNotificationUpdater.ACTION_DISABLE_TETHERING)) {
                untetherAll();
            }
        }

        private void handleConnectivityAction(Intent intent) {
            final NetworkInfo networkInfo =
                    (NetworkInfo) intent.getParcelableExtra(EXTRA_NETWORK_INFO);
            if (networkInfo == null
                    || networkInfo.getDetailedState() == NetworkInfo.DetailedState.FAILED) {
                return;
            }

            if (VDBG) Log.d(TAG, "Tethering got CONNECTIVITY_ACTION: " + networkInfo.toString());
            mTetherMasterSM.sendMessage(TetherMasterSM.CMD_UPSTREAM_CHANGED);
        }

        private void handleUsbAction(Intent intent) {
            final boolean usbConnected = intent.getBooleanExtra(USB_CONNECTED, false);
            final boolean usbConfigured = intent.getBooleanExtra(USB_CONFIGURED, false);
            final boolean rndisEnabled = intent.getBooleanExtra(USB_FUNCTION_RNDIS, false);
            final boolean ncmEnabled = intent.getBooleanExtra(USB_FUNCTION_NCM, false);

            mLog.log(String.format("USB bcast connected:%s configured:%s rndis:%s",
                    usbConnected, usbConfigured, rndisEnabled));

            // There are three types of ACTION_USB_STATE:
            //
            //     - DISCONNECTED (USB_CONNECTED and USB_CONFIGURED are 0)
            //       Meaning: USB connection has ended either because of
            //       software reset or hard unplug.
            //
            //     - CONNECTED (USB_CONNECTED is 1, USB_CONFIGURED is 0)
            //       Meaning: the first stage of USB protocol handshake has
            //       occurred but it is not complete.
            //
            //     - CONFIGURED (USB_CONNECTED and USB_CONFIGURED are 1)
            //       Meaning: the USB handshake is completely done and all the
            //       functions are ready to use.
            //
            // For more explanation, see b/62552150 .
            synchronized (Tethering.this.mPublicSync) {
                if (!usbConnected && mRndisEnabled) {
                    // Turn off tethering if it was enabled and there is a disconnect.
                    tetherMatchingInterfaces(IpServer.STATE_AVAILABLE, TETHERING_USB);
                    mEntitlementMgr.stopProvisioningIfNeeded(TETHERING_USB);
                } else if (usbConfigured && rndisEnabled) {
                    // Tether if rndis is enabled and usb is configured.
                    tetherMatchingInterfaces(IpServer.STATE_TETHERED, TETHERING_USB);
                } else if (usbConnected && ncmEnabled) {
                    tetherMatchingInterfaces(IpServer.STATE_LOCAL_ONLY, TETHERING_NCM);
                }
                mRndisEnabled = usbConfigured && rndisEnabled;
            }
        }

        private void handleWifiApAction(Intent intent) {
            final int curState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED);
            final String ifname = intent.getStringExtra(EXTRA_WIFI_AP_INTERFACE_NAME);
            final int ipmode = intent.getIntExtra(EXTRA_WIFI_AP_MODE, IFACE_IP_MODE_UNSPECIFIED);

            synchronized (Tethering.this.mPublicSync) {
                switch (curState) {
                    case WifiManager.WIFI_AP_STATE_ENABLING:
                        // We can see this state on the way to both enabled and failure states.
                        break;
                    case WifiManager.WIFI_AP_STATE_ENABLED:
                        enableWifiIpServingLocked(ifname, ipmode);
                        break;
                    case WifiManager.WIFI_AP_STATE_DISABLING:
                        // We can see this state on the way to disabled.
                        break;
                    case WifiManager.WIFI_AP_STATE_DISABLED:
                    case WifiManager.WIFI_AP_STATE_FAILED:
                    default:
                        disableWifiIpServingLocked(ifname, curState);
                        break;
                }
            }
        }

        private boolean isGroupOwner(WifiP2pGroup group) {
            return group != null && group.isGroupOwner()
                    && !TextUtils.isEmpty(group.getInterface());
        }

        private void handleWifiP2pAction(Intent intent) {
            if (mConfig.isWifiP2pLegacyTetheringMode()) return;

            final WifiP2pInfo p2pInfo =
                    (WifiP2pInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
            final WifiP2pGroup group =
                    (WifiP2pGroup) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);

            if (VDBG) {
                Log.d(TAG, "WifiP2pAction: P2pInfo: " + p2pInfo + " Group: " + group);
            }

            synchronized (Tethering.this.mPublicSync) {
                // if no group is formed, bring it down if needed.
                if (p2pInfo == null || !p2pInfo.groupFormed) {
                    disableWifiP2pIpServingLockedIfNeeded(mWifiP2pTetherInterface);
                    mWifiP2pTetherInterface = null;
                    return;
                }

                // If there is a group but the device is not the owner, bail out.
                if (!isGroupOwner(group)) return;

                // If already serving from the correct interface, nothing to do.
                if (group.getInterface().equals(mWifiP2pTetherInterface)) return;

                // If already serving from another interface, turn it down first.
                if (!TextUtils.isEmpty(mWifiP2pTetherInterface)) {
                    mLog.w("P2P tethered interface " + mWifiP2pTetherInterface
                            + "is different from current interface "
                            + group.getInterface() + ", re-tether it");
                    disableWifiP2pIpServingLockedIfNeeded(mWifiP2pTetherInterface);
                }

                // Finally bring up serving on the new interface
                mWifiP2pTetherInterface = group.getInterface();
                enableWifiIpServingLocked(mWifiP2pTetherInterface, IFACE_IP_MODE_LOCAL_ONLY);
            }
        }

        private void handleUserRestrictionAction() {
            mTetheringRestriction.onUserRestrictionsChanged();
        }

        private void handleDataSaverChanged() {
            final ConnectivityManager connMgr = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            final boolean isDataSaverEnabled = connMgr.getRestrictBackgroundStatus()
                    != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;

            if (mDataSaverEnabled == isDataSaverEnabled) return;

            mDataSaverEnabled = isDataSaverEnabled;
            if (mDataSaverEnabled) {
                untetherAll();
            }
        }
    }

    @VisibleForTesting
    protected static class UserRestrictionActionListener {
        private final UserManager mUserManager;
        private final Tethering mWrapper;
        private final TetheringNotificationUpdater mNotificationUpdater;
        public boolean mDisallowTethering;

        public UserRestrictionActionListener(@NonNull UserManager um, @NonNull Tethering wrapper,
                @NonNull TetheringNotificationUpdater updater) {
            mUserManager = um;
            mWrapper = wrapper;
            mNotificationUpdater = updater;
            mDisallowTethering = false;
        }

        public void onUserRestrictionsChanged() {
            // getUserRestrictions gets restriction for this process' user, which is the primary
            // user. This is fine because DISALLOW_CONFIG_TETHERING can only be set on the primary
            // user. See UserManager.DISALLOW_CONFIG_TETHERING.
            final Bundle restrictions = mUserManager.getUserRestrictions();
            final boolean newlyDisallowed =
                    restrictions.getBoolean(UserManager.DISALLOW_CONFIG_TETHERING);
            final boolean prevDisallowed = mDisallowTethering;
            mDisallowTethering = newlyDisallowed;

            final boolean tetheringDisallowedChanged = (newlyDisallowed != prevDisallowed);
            if (!tetheringDisallowedChanged) {
                return;
            }

            if (!newlyDisallowed) {
                // Clear the restricted notification when user is allowed to have tethering
                // function.
                mNotificationUpdater.tetheringRestrictionLifted();
                return;
            }

            // Restricted notification is shown when tethering function is disallowed on
            // user's device.
            mNotificationUpdater.notifyTetheringDisabledByRestriction();

            // Untether from all downstreams since tethering is disallowed.
            mWrapper.untetherAll();

            // TODO(b/148139325): send tetheringSupported on restriction change
        }
    }

    private void disableWifiIpServingLockedCommon(int tetheringType, String ifname, int apState) {
        mLog.log("Canceling WiFi tethering request -"
                + " type=" + tetheringType
                + " interface=" + ifname
                + " state=" + apState);

        if (!TextUtils.isEmpty(ifname)) {
            final TetherState ts = mTetherStates.get(ifname);
            if (ts != null) {
                ts.ipServer.unwanted();
                return;
            }
        }

        for (int i = 0; i < mTetherStates.size(); i++) {
            final IpServer ipServer = mTetherStates.valueAt(i).ipServer;
            if (ipServer.interfaceType() == tetheringType) {
                ipServer.unwanted();
                return;
            }
        }

        mLog.log("Error disabling Wi-Fi IP serving; "
                + (TextUtils.isEmpty(ifname) ? "no interface name specified"
                                           : "specified interface: " + ifname));
    }

    private void disableWifiIpServingLocked(String ifname, int apState) {
        // Regardless of whether we requested this transition, the AP has gone
        // down.  Don't try to tether again unless we're requested to do so.
        // TODO: Remove this altogether, once Wi-Fi reliably gives us an
        // interface name with every broadcast.
        mWifiTetherRequested = false;

        disableWifiIpServingLockedCommon(TETHERING_WIFI, ifname, apState);
    }

    private void disableWifiP2pIpServingLockedIfNeeded(String ifname) {
        if (TextUtils.isEmpty(ifname)) return;

        disableWifiIpServingLockedCommon(TETHERING_WIFI_P2P, ifname, /* dummy */ 0);
    }

    private void enableWifiIpServingLocked(String ifname, int wifiIpMode) {
        // Map wifiIpMode values to IpServer.Callback serving states, inferring
        // from mWifiTetherRequested as a final "best guess".
        final int ipServingMode;
        switch (wifiIpMode) {
            case IFACE_IP_MODE_TETHERED:
                ipServingMode = IpServer.STATE_TETHERED;
                break;
            case IFACE_IP_MODE_LOCAL_ONLY:
                ipServingMode = IpServer.STATE_LOCAL_ONLY;
                break;
            default:
                mLog.e("Cannot enable IP serving in unknown WiFi mode: " + wifiIpMode);
                return;
        }

        if (!TextUtils.isEmpty(ifname)) {
            maybeTrackNewInterfaceLocked(ifname);
            changeInterfaceState(ifname, ipServingMode);
        } else {
            mLog.e(String.format(
                    "Cannot enable IP serving in mode %s on missing interface name",
                    ipServingMode));
        }
    }

    // TODO: Consider renaming to something more accurate in its description.
    // This method:
    //     - allows requesting either tethering or local hotspot serving states
    //     - handles both enabling and disabling serving states
    //     - only tethers the first matching interface in listInterfaces()
    //       order of a given type
    private void tetherMatchingInterfaces(int requestedState, int interfaceType) {
        if (VDBG) {
            Log.d(TAG, "tetherMatchingInterfaces(" + requestedState + ", " + interfaceType + ")");
        }

        String[] ifaces = null;
        try {
            ifaces = mNetd.interfaceGetList();
        } catch (RemoteException | ServiceSpecificException e) {
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

        changeInterfaceState(chosenIface, requestedState);
    }

    private void changeInterfaceState(String ifname, int requestedState) {
        final int result;
        switch (requestedState) {
            case IpServer.STATE_UNAVAILABLE:
            case IpServer.STATE_AVAILABLE:
                result = untether(ifname);
                break;
            case IpServer.STATE_TETHERED:
            case IpServer.STATE_LOCAL_ONLY:
                result = tether(ifname, requestedState);
                break;
            default:
                Log.wtf(TAG, "Unknown interface state: " + requestedState);
                return;
        }
        if (result != TETHER_ERROR_NO_ERROR) {
            Log.e(TAG, "unable start or stop tethering on iface " + ifname);
            return;
        }
    }

    TetheringConfiguration getTetheringConfiguration() {
        return mConfig;
    }

    boolean hasTetherableConfiguration() {
        final TetheringConfiguration cfg = mConfig;
        final boolean hasDownstreamConfiguration =
                (cfg.tetherableUsbRegexs.length != 0)
                || (cfg.tetherableWifiRegexs.length != 0)
                || (cfg.tetherableBluetoothRegexs.length != 0);
        final boolean hasUpstreamConfiguration = !cfg.preferredUpstreamIfaceTypes.isEmpty()
                || cfg.chooseUpstreamAutomatically;

        return hasDownstreamConfiguration && hasUpstreamConfiguration;
    }

    // TODO - update callers to use getTetheringConfiguration(),
    // which has only final members.
    String[] getTetherableUsbRegexs() {
        return copy(mConfig.tetherableUsbRegexs);
    }

    String[] getTetherableWifiRegexs() {
        return copy(mConfig.tetherableWifiRegexs);
    }

    String[] getTetherableBluetoothRegexs() {
        return copy(mConfig.tetherableBluetoothRegexs);
    }

    int setUsbTethering(boolean enable) {
        if (VDBG) Log.d(TAG, "setUsbTethering(" + enable + ")");
        UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            mLog.e("setUsbTethering: failed to get UsbManager!");
            return TETHER_ERROR_SERVICE_UNAVAIL;
        }

        synchronized (mPublicSync) {
            usbManager.setCurrentFunctions(enable ? UsbManager.FUNCTION_RNDIS
                    : UsbManager.FUNCTION_NONE);
        }
        return TETHER_ERROR_NO_ERROR;
    }

    private int setNcmTethering(boolean enable) {
        if (VDBG) Log.d(TAG, "setNcmTethering(" + enable + ")");
        UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        synchronized (mPublicSync) {
            usbManager.setCurrentFunctions(enable ? UsbManager.FUNCTION_NCM
                    : UsbManager.FUNCTION_NONE);
        }
        return TETHER_ERROR_NO_ERROR;
    }

    // TODO review API - figure out how to delete these entirely.
    String[] getTetheredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                if (tetherState.lastState == IpServer.STATE_TETHERED) {
                    list.add(mTetherStates.keyAt(i));
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }

    String[] getTetherableIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                if (tetherState.lastState == IpServer.STATE_AVAILABLE) {
                    list.add(mTetherStates.keyAt(i));
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }

    String[] getTetheredDhcpRanges() {
        // TODO: this is only valid for the old DHCP server. Latest search suggests it is only used
        // by WifiP2pServiceImpl to start dnsmasq: remove/deprecate after migrating callers.
        return mConfig.legacyDhcpRanges;
    }

    String[] getErroredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mPublicSync) {
            for (int i = 0; i < mTetherStates.size(); i++) {
                TetherState tetherState = mTetherStates.valueAt(i);
                if (tetherState.lastError != TETHER_ERROR_NO_ERROR) {
                    list.add(mTetherStates.keyAt(i));
                }
            }
        }
        return list.toArray(new String[list.size()]);
    }

    private void logMessage(State state, int what) {
        mLog.log(state.getName() + " got " + sMagicDecoderRing.get(what, Integer.toString(what)));
    }

    private boolean upstreamWanted() {
        if (!mForwardedDownstreams.isEmpty()) return true;

        synchronized (mPublicSync) {
            return mWifiTetherRequested;
        }
    }

    // Needed because the canonical source of upstream truth is just the
    // upstream interface set, |mCurrentUpstreamIfaceSet|.
    private boolean pertainsToCurrentUpstream(UpstreamNetworkState ns) {
        if (ns != null && ns.linkProperties != null && mCurrentUpstreamIfaceSet != null) {
            for (String ifname : ns.linkProperties.getAllInterfaceNames()) {
                if (mCurrentUpstreamIfaceSet.ifnames.contains(ifname)) {
                    return true;
                }
            }
        }
        return false;
    }

    class TetherMasterSM extends StateMachine {
        // an interface SM has requested Tethering/Local Hotspot
        static final int EVENT_IFACE_SERVING_STATE_ACTIVE       = BASE_MASTER + 1;
        // an interface SM has unrequested Tethering/Local Hotspot
        static final int EVENT_IFACE_SERVING_STATE_INACTIVE     = BASE_MASTER + 2;
        // upstream connection change - do the right thing
        static final int CMD_UPSTREAM_CHANGED                   = BASE_MASTER + 3;
        // we don't have a valid upstream conn, check again after a delay
        static final int CMD_RETRY_UPSTREAM                     = BASE_MASTER + 4;
        // Events from NetworkCallbacks that we process on the master state
        // machine thread on behalf of the UpstreamNetworkMonitor.
        static final int EVENT_UPSTREAM_CALLBACK                = BASE_MASTER + 5;
        // we treated the error and want now to clear it
        static final int CMD_CLEAR_ERROR                        = BASE_MASTER + 6;
        static final int EVENT_IFACE_UPDATE_LINKPROPERTIES      = BASE_MASTER + 7;
        // Events from EntitlementManager to choose upstream again.
        static final int EVENT_UPSTREAM_PERMISSION_CHANGED      = BASE_MASTER + 8;
        private final State mInitialState;
        private final State mTetherModeAliveState;

        private final State mSetIpForwardingEnabledErrorState;
        private final State mSetIpForwardingDisabledErrorState;
        private final State mStartTetheringErrorState;
        private final State mStopTetheringErrorState;
        private final State mSetDnsForwardersErrorState;

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
        private final ArrayList<IpServer> mNotifyList;
        private final IPv6TetheringCoordinator mIPv6TetheringCoordinator;
        private final OffloadWrapper mOffload;

        private static final int UPSTREAM_SETTLE_TIME_MS     = 10000;

        TetherMasterSM(String name, Looper looper, TetheringDependencies deps) {
            super(name, looper);

            mInitialState = new InitialState();
            mTetherModeAliveState = new TetherModeAliveState();
            mSetIpForwardingEnabledErrorState = new SetIpForwardingEnabledErrorState();
            mSetIpForwardingDisabledErrorState = new SetIpForwardingDisabledErrorState();
            mStartTetheringErrorState = new StartTetheringErrorState();
            mStopTetheringErrorState = new StopTetheringErrorState();
            mSetDnsForwardersErrorState = new SetDnsForwardersErrorState();

            addState(mInitialState);
            addState(mTetherModeAliveState);
            addState(mSetIpForwardingEnabledErrorState);
            addState(mSetIpForwardingDisabledErrorState);
            addState(mStartTetheringErrorState);
            addState(mStopTetheringErrorState);
            addState(mSetDnsForwardersErrorState);

            mNotifyList = new ArrayList<>();
            mIPv6TetheringCoordinator = deps.getIPv6TetheringCoordinator(mNotifyList, mLog);
            mOffload = new OffloadWrapper();

            setInitialState(mInitialState);
        }

        class InitialState extends State {
            @Override
            public boolean processMessage(Message message) {
                logMessage(this, message.what);
                switch (message.what) {
                    case EVENT_IFACE_SERVING_STATE_ACTIVE: {
                        final IpServer who = (IpServer) message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode requested by " + who);
                        handleInterfaceServingStateActive(message.arg1, who);
                        transitionTo(mTetherModeAliveState);
                        break;
                    }
                    case EVENT_IFACE_SERVING_STATE_INACTIVE: {
                        final IpServer who = (IpServer) message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode unrequested by " + who);
                        handleInterfaceServingStateInactive(who);
                        break;
                    }
                    case EVENT_IFACE_UPDATE_LINKPROPERTIES:
                        // Silently ignore these for now.
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        protected boolean turnOnMasterTetherSettings() {
            final TetheringConfiguration cfg = mConfig;
            try {
                mNetd.ipfwdEnableForwarding(TAG);
            } catch (RemoteException | ServiceSpecificException e) {
                mLog.e(e);
                transitionTo(mSetIpForwardingEnabledErrorState);
                return false;
            }

            // TODO: Randomize DHCPv4 ranges, especially in hotspot mode.
            // Legacy DHCP server is disabled if passed an empty ranges array
            final String[] dhcpRanges = cfg.enableLegacyDhcpServer
                    ? cfg.legacyDhcpRanges : new String[0];
            try {
                NetdUtils.tetherStart(mNetd, true /** usingLegacyDnsProxy */, dhcpRanges);
            } catch (RemoteException | ServiceSpecificException e) {
                try {
                    // Stop and retry.
                    mNetd.tetherStop();
                    NetdUtils.tetherStart(mNetd, true /** usingLegacyDnsProxy */, dhcpRanges);
                } catch (RemoteException | ServiceSpecificException ee) {
                    mLog.e(ee);
                    transitionTo(mStartTetheringErrorState);
                    return false;
                }
            }
            mLog.log("SET master tether settings: ON");
            return true;
        }

        protected boolean turnOffMasterTetherSettings() {
            try {
                mNetd.tetherStop();
            } catch (RemoteException | ServiceSpecificException e) {
                mLog.e(e);
                transitionTo(mStopTetheringErrorState);
                return false;
            }
            try {
                mNetd.ipfwdDisableForwarding(TAG);
            } catch (RemoteException | ServiceSpecificException e) {
                mLog.e(e);
                transitionTo(mSetIpForwardingDisabledErrorState);
                return false;
            }
            transitionTo(mInitialState);
            mLog.log("SET master tether settings: OFF");
            return true;
        }

        protected void chooseUpstreamType(boolean tryCell) {
            // We rebuild configuration on ACTION_CONFIGURATION_CHANGED, but we
            // do not currently know how to watch for changes in DUN settings.
            maybeDunSettingChanged();

            final TetheringConfiguration config = mConfig;
            final UpstreamNetworkState ns = (config.chooseUpstreamAutomatically)
                    ? mUpstreamNetworkMonitor.getCurrentPreferredUpstream()
                    : mUpstreamNetworkMonitor.selectPreferredUpstreamType(
                            config.preferredUpstreamIfaceTypes);
            if (ns == null) {
                if (tryCell) {
                    mUpstreamNetworkMonitor.registerMobileNetworkRequest();
                    // We think mobile should be coming up; don't set a retry.
                } else {
                    sendMessageDelayed(CMD_RETRY_UPSTREAM, UPSTREAM_SETTLE_TIME_MS);
                }
            }
            setUpstreamNetwork(ns);
            final Network newUpstream = (ns != null) ? ns.network : null;
            if (mTetherUpstream != newUpstream) {
                mTetherUpstream = newUpstream;
                mUpstreamNetworkMonitor.setCurrentUpstream(mTetherUpstream);
                reportUpstreamChanged(ns);
            }
        }

        protected void setUpstreamNetwork(UpstreamNetworkState ns) {
            InterfaceSet ifaces = null;
            if (ns != null) {
                // Find the interface with the default IPv4 route. It may be the
                // interface described by linkProperties, or one of the interfaces
                // stacked on top of it.
                mLog.i("Looking for default routes on: " + ns.linkProperties);
                ifaces = TetheringInterfaceUtils.getTetheringInterfaces(ns);
                mLog.i("Found upstream interface(s): " + ifaces);
            }

            if (ifaces != null) {
                setDnsForwarders(ns.network, ns.linkProperties);
            }
            notifyDownstreamsOfNewUpstreamIface(ifaces);
            if (ns != null && pertainsToCurrentUpstream(ns)) {
                // If we already have UpstreamNetworkState for this network update it immediately.
                handleNewUpstreamNetworkState(ns);
            } else if (mCurrentUpstreamIfaceSet == null) {
                // There are no available upstream networks.
                handleNewUpstreamNetworkState(null);
            }
        }

        protected void setDnsForwarders(final Network network, final LinkProperties lp) {
            // TODO: Set v4 and/or v6 DNS per available connectivity.
            final Collection<InetAddress> dnses = lp.getDnsServers();
            // TODO: Properly support the absence of DNS servers.
            final String[] dnsServers;
            if (dnses != null && !dnses.isEmpty()) {
                dnsServers = new String[dnses.size()];
                int i = 0;
                for (InetAddress dns : dnses) {
                    dnsServers[i++] = dns.getHostAddress();
                }
            } else {
                dnsServers = mConfig.defaultIPv4DNS;
            }
            final int netId = (network != null) ? network.getNetId() : NETID_UNSET;
            try {
                mNetd.tetherDnsSet(netId, dnsServers);
                mLog.log(String.format(
                        "SET DNS forwarders: network=%s dnsServers=%s",
                        network, Arrays.toString(dnsServers)));
            } catch (RemoteException | ServiceSpecificException e) {
                // TODO: Investigate how this can fail and what exactly
                // happens if/when such failures occur.
                mLog.e("setting DNS forwarders failed, " + e);
                transitionTo(mSetDnsForwardersErrorState);
            }
        }

        protected void notifyDownstreamsOfNewUpstreamIface(InterfaceSet ifaces) {
            mCurrentUpstreamIfaceSet = ifaces;
            for (IpServer ipServer : mNotifyList) {
                ipServer.sendMessage(IpServer.CMD_TETHER_CONNECTION_CHANGED, ifaces);
            }
        }

        protected void handleNewUpstreamNetworkState(UpstreamNetworkState ns) {
            mIPv6TetheringCoordinator.updateUpstreamNetworkState(ns);
            mOffload.updateUpstreamNetworkState(ns);
        }

        private void handleInterfaceServingStateActive(int mode, IpServer who) {
            if (mNotifyList.indexOf(who) < 0) {
                mNotifyList.add(who);
                mIPv6TetheringCoordinator.addActiveDownstream(who, mode);
            }

            if (mode == IpServer.STATE_TETHERED) {
                // No need to notify OffloadController just yet as there are no
                // "offload-able" prefixes to pass along. This will handled
                // when the TISM informs Tethering of its LinkProperties.
                mForwardedDownstreams.add(who);
            } else {
                mOffload.excludeDownstreamInterface(who.interfaceName());
                mForwardedDownstreams.remove(who);
            }

            // If this is a Wi-Fi interface, notify WifiManager of the active serving state.
            if (who.interfaceType() == TETHERING_WIFI) {
                final WifiManager mgr = getWifiManager();
                final String iface = who.interfaceName();
                switch (mode) {
                    case IpServer.STATE_TETHERED:
                        mgr.updateInterfaceIpState(iface, IFACE_IP_MODE_TETHERED);
                        break;
                    case IpServer.STATE_LOCAL_ONLY:
                        mgr.updateInterfaceIpState(iface, IFACE_IP_MODE_LOCAL_ONLY);
                        break;
                    default:
                        Log.wtf(TAG, "Unknown active serving mode: " + mode);
                        break;
                }
            }
        }

        private void handleInterfaceServingStateInactive(IpServer who) {
            mNotifyList.remove(who);
            mIPv6TetheringCoordinator.removeActiveDownstream(who);
            mOffload.excludeDownstreamInterface(who.interfaceName());
            mForwardedDownstreams.remove(who);
            updateConnectedClients(null /* wifiClients */);

            // If this is a Wi-Fi interface, tell WifiManager of any errors
            // or the inactive serving state.
            if (who.interfaceType() == TETHERING_WIFI) {
                if (who.lastError() != TETHER_ERROR_NO_ERROR) {
                    getWifiManager().updateInterfaceIpState(
                            who.interfaceName(), IFACE_IP_MODE_CONFIGURATION_ERROR);
                } else {
                    getWifiManager().updateInterfaceIpState(
                            who.interfaceName(), IFACE_IP_MODE_UNSPECIFIED);
                }
            }
        }

        @VisibleForTesting
        void handleUpstreamNetworkMonitorCallback(int arg1, Object o) {
            if (arg1 == UpstreamNetworkMonitor.NOTIFY_LOCAL_PREFIXES) {
                mOffload.sendOffloadExemptPrefixes((Set<IpPrefix>) o);
                return;
            }

            final UpstreamNetworkState ns = (UpstreamNetworkState) o;

            if (ns == null || !pertainsToCurrentUpstream(ns)) {
                // TODO: In future, this is where upstream evaluation and selection
                // could be handled for notifications which include sufficient data.
                // For example, after CONNECTIVITY_ACTION listening is removed, here
                // is where we could observe a Wi-Fi network becoming available and
                // passing validation.
                if (mCurrentUpstreamIfaceSet == null) {
                    // If we have no upstream interface, try to run through upstream
                    // selection again.  If, for example, IPv4 connectivity has shown up
                    // after IPv6 (e.g., 464xlat became available) we want the chance to
                    // notice and act accordingly.
                    chooseUpstreamType(false);
                }
                return;
            }

            switch (arg1) {
                case UpstreamNetworkMonitor.EVENT_ON_CAPABILITIES:
                    if (ns.network.equals(mTetherUpstream)) {
                        mNotificationUpdater.onUpstreamCapabilitiesChanged(ns.networkCapabilities);
                    }
                    handleNewUpstreamNetworkState(ns);
                    break;
                case UpstreamNetworkMonitor.EVENT_ON_LINKPROPERTIES:
                    chooseUpstreamType(false);
                    break;
                case UpstreamNetworkMonitor.EVENT_ON_LOST:
                    // TODO: Re-evaluate possible upstreams. Currently upstream
                    // reevaluation is triggered via received CONNECTIVITY_ACTION
                    // broadcasts that result in being passed a
                    // TetherMasterSM.CMD_UPSTREAM_CHANGED.
                    handleNewUpstreamNetworkState(null);
                    break;
                default:
                    mLog.e("Unknown arg1 value: " + arg1);
                    break;
            }
        }

        class TetherModeAliveState extends State {
            boolean mUpstreamWanted = false;
            boolean mTryCell = true;

            @Override
            public void enter() {
                // If turning on master tether settings fails, we have already
                // transitioned to an error state; exit early.
                if (!turnOnMasterTetherSettings()) {
                    return;
                }

                mUpstreamNetworkMonitor.startObserveAllNetworks();

                // TODO: De-duplicate with updateUpstreamWanted() below.
                if (upstreamWanted()) {
                    mUpstreamWanted = true;
                    mOffload.start();
                    chooseUpstreamType(true);
                    mTryCell = false;
                }
            }

            @Override
            public void exit() {
                mOffload.stop();
                mUpstreamNetworkMonitor.stop();
                notifyDownstreamsOfNewUpstreamIface(null);
                handleNewUpstreamNetworkState(null);
                if (mTetherUpstream != null) {
                    mTetherUpstream = null;
                    reportUpstreamChanged(null);
                }
            }

            private boolean updateUpstreamWanted() {
                final boolean previousUpstreamWanted = mUpstreamWanted;
                mUpstreamWanted = upstreamWanted();
                if (mUpstreamWanted != previousUpstreamWanted) {
                    if (mUpstreamWanted) {
                        mOffload.start();
                    } else {
                        mOffload.stop();
                    }
                }
                return previousUpstreamWanted;
            }

            @Override
            public boolean processMessage(Message message) {
                logMessage(this, message.what);
                boolean retValue = true;
                switch (message.what) {
                    case EVENT_IFACE_SERVING_STATE_ACTIVE: {
                        IpServer who = (IpServer) message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode requested by " + who);
                        handleInterfaceServingStateActive(message.arg1, who);
                        who.sendMessage(IpServer.CMD_TETHER_CONNECTION_CHANGED,
                                mCurrentUpstreamIfaceSet);
                        // If there has been a change and an upstream is now
                        // desired, kick off the selection process.
                        final boolean previousUpstreamWanted = updateUpstreamWanted();
                        if (!previousUpstreamWanted && mUpstreamWanted) {
                            chooseUpstreamType(true);
                        }
                        break;
                    }
                    case EVENT_IFACE_SERVING_STATE_INACTIVE: {
                        IpServer who = (IpServer) message.obj;
                        if (VDBG) Log.d(TAG, "Tether Mode unrequested by " + who);
                        handleInterfaceServingStateInactive(who);

                        if (mNotifyList.isEmpty()) {
                            // This transitions us out of TetherModeAliveState,
                            // either to InitialState or an error state.
                            turnOffMasterTetherSettings();
                            break;
                        }

                        if (DBG) {
                            Log.d(TAG, "TetherModeAlive still has " + mNotifyList.size()
                                    + " live requests:");
                            for (IpServer o : mNotifyList) {
                                Log.d(TAG, "  " + o);
                            }
                        }
                        // If there has been a change and an upstream is no
                        // longer desired, release any mobile requests.
                        final boolean previousUpstreamWanted = updateUpstreamWanted();
                        if (previousUpstreamWanted && !mUpstreamWanted) {
                            mUpstreamNetworkMonitor.releaseMobileNetworkRequest();
                        }
                        break;
                    }
                    case EVENT_IFACE_UPDATE_LINKPROPERTIES: {
                        final LinkProperties newLp = (LinkProperties) message.obj;
                        if (message.arg1 == IpServer.STATE_TETHERED) {
                            mOffload.updateDownstreamLinkProperties(newLp);
                        } else {
                            mOffload.excludeDownstreamInterface(newLp.getInterfaceName());
                        }
                        break;
                    }
                    case EVENT_UPSTREAM_PERMISSION_CHANGED:
                    case CMD_UPSTREAM_CHANGED:
                        updateUpstreamWanted();
                        if (!mUpstreamWanted) break;

                        // Need to try DUN immediately if Wi-Fi goes down.
                        chooseUpstreamType(true);
                        mTryCell = false;
                        break;
                    case CMD_RETRY_UPSTREAM:
                        updateUpstreamWanted();
                        if (!mUpstreamWanted) break;

                        chooseUpstreamType(mTryCell);
                        mTryCell = !mTryCell;
                        break;
                    case EVENT_UPSTREAM_CALLBACK: {
                        updateUpstreamWanted();
                        if (mUpstreamWanted) {
                            handleUpstreamNetworkMonitorCallback(message.arg1, message.obj);
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
            private int mErrorNotification;

            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case EVENT_IFACE_SERVING_STATE_ACTIVE:
                        IpServer who = (IpServer) message.obj;
                        who.sendMessage(mErrorNotification);
                        break;
                    case CMD_CLEAR_ERROR:
                        mErrorNotification = TETHER_ERROR_NO_ERROR;
                        transitionTo(mInitialState);
                        break;
                    default:
                        retValue = false;
                }
                return retValue;
            }

            void notify(int msgType) {
                mErrorNotification = msgType;
                for (IpServer ipServer : mNotifyList) {
                    ipServer.sendMessage(msgType);
                }
            }

        }

        class SetIpForwardingEnabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingEnabled");
                notify(IpServer.CMD_IP_FORWARDING_ENABLE_ERROR);
            }
        }

        class SetIpForwardingDisabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingDisabled");
                notify(IpServer.CMD_IP_FORWARDING_DISABLE_ERROR);
            }
        }

        class StartTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in startTethering");
                notify(IpServer.CMD_START_TETHERING_ERROR);
                try {
                    mNetd.ipfwdDisableForwarding(TAG);
                } catch (RemoteException | ServiceSpecificException e) { }
            }
        }

        class StopTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in stopTethering");
                notify(IpServer.CMD_STOP_TETHERING_ERROR);
                try {
                    mNetd.ipfwdDisableForwarding(TAG);
                } catch (RemoteException | ServiceSpecificException e) { }
            }
        }

        class SetDnsForwardersErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setDnsForwarders");
                notify(IpServer.CMD_SET_DNS_FORWARDERS_ERROR);
                try {
                    mNetd.tetherStop();
                } catch (RemoteException | ServiceSpecificException e) { }
                try {
                    mNetd.ipfwdDisableForwarding(TAG);
                } catch (RemoteException | ServiceSpecificException e) { }
            }
        }

        // A wrapper class to handle multiple situations where several calls to
        // the OffloadController need to happen together.
        //
        // TODO: This suggests that the interface between OffloadController and
        // Tethering is in need of improvement. Refactor these calls into the
        // OffloadController implementation.
        class OffloadWrapper {
            public void start() {
                final int status = mOffloadController.start() ? TETHER_HARDWARE_OFFLOAD_STARTED
                        : TETHER_HARDWARE_OFFLOAD_FAILED;
                updateOffloadStatus(status);
                sendOffloadExemptPrefixes();
            }

            public void stop() {
                mOffloadController.stop();
                updateOffloadStatus(TETHER_HARDWARE_OFFLOAD_STOPPED);
            }

            public void updateUpstreamNetworkState(UpstreamNetworkState ns) {
                mOffloadController.setUpstreamLinkProperties(
                        (ns != null) ? ns.linkProperties : null);
            }

            public void updateDownstreamLinkProperties(LinkProperties newLp) {
                // Update the list of offload-exempt prefixes before adding
                // new prefixes on downstream interfaces to the offload HAL.
                sendOffloadExemptPrefixes();
                mOffloadController.notifyDownstreamLinkProperties(newLp);
            }

            public void excludeDownstreamInterface(String ifname) {
                // This and other interfaces may be in local-only hotspot mode;
                // resend all local prefixes to the OffloadController.
                sendOffloadExemptPrefixes();
                mOffloadController.removeDownstreamInterface(ifname);
            }

            public void sendOffloadExemptPrefixes() {
                sendOffloadExemptPrefixes(mUpstreamNetworkMonitor.getLocalPrefixes());
            }

            public void sendOffloadExemptPrefixes(final Set<IpPrefix> localPrefixes) {
                // Add in well-known minimum set.
                PrefixUtils.addNonForwardablePrefixes(localPrefixes);
                // Add tragically hardcoded prefixes.
                localPrefixes.add(PrefixUtils.DEFAULT_WIFI_P2P_PREFIX);

                // Maybe add prefixes or addresses for downstreams, depending on
                // the IP serving mode of each.
                for (IpServer ipServer : mNotifyList) {
                    final LinkProperties lp = ipServer.linkProperties();

                    switch (ipServer.servingMode()) {
                        case IpServer.STATE_UNAVAILABLE:
                        case IpServer.STATE_AVAILABLE:
                            // No usable LinkProperties in these states.
                            continue;
                        case IpServer.STATE_TETHERED:
                            // Only add IPv4 /32 and IPv6 /128 prefixes. The
                            // directly-connected prefixes will be sent as
                            // downstream "offload-able" prefixes.
                            for (LinkAddress addr : lp.getAllLinkAddresses()) {
                                final InetAddress ip = addr.getAddress();
                                if (ip.isLinkLocalAddress()) continue;
                                localPrefixes.add(PrefixUtils.ipAddressAsPrefix(ip));
                            }
                            break;
                        case IpServer.STATE_LOCAL_ONLY:
                            // Add prefixes covering all local IPs.
                            localPrefixes.addAll(PrefixUtils.localPrefixesFrom(lp));
                            break;
                    }
                }

                mOffloadController.setLocalPrefixes(localPrefixes);
            }

            private void updateOffloadStatus(final int newStatus) {
                if (newStatus == mOffloadStatus) return;

                mOffloadStatus = newStatus;
                reportOffloadStatusChanged(mOffloadStatus);
            }
        }
    }

    private void startTrackDefaultNetwork() {
        mUpstreamNetworkMonitor.startTrackDefaultNetwork(mDeps.getDefaultNetworkRequest(),
                mEntitlementMgr);
    }

    /** Get the latest value of the tethering entitlement check. */
    void requestLatestTetheringEntitlementResult(int type, ResultReceiver receiver,
            boolean showEntitlementUi) {
        if (receiver == null) return;

        mHandler.post(() -> {
            mEntitlementMgr.requestLatestTetheringEntitlementResult(type, receiver,
                    showEntitlementUi);
        });
    }

    /** Register tethering event callback */
    void registerTetheringEventCallback(ITetheringEventCallback callback) {
        final boolean hasListPermission =
                hasCallingPermission(NETWORK_SETTINGS)
                        || hasCallingPermission(PERMISSION_MAINLINE_NETWORK_STACK)
                        || hasCallingPermission(NETWORK_STACK);
        mHandler.post(() -> {
            mTetheringEventCallbacks.register(callback, new CallbackCookie(hasListPermission));
            final TetheringCallbackStartedParcel parcel = new TetheringCallbackStartedParcel();
            parcel.tetheringSupported = mDeps.isTetheringSupported();
            parcel.upstreamNetwork = mTetherUpstream;
            parcel.config = mConfig.toStableParcelable();
            parcel.states =
                    mTetherStatesParcel != null ? mTetherStatesParcel : emptyTetherStatesParcel();
            parcel.tetheredClients = hasListPermission
                    ? mConnectedClientsTracker.getLastTetheredClients()
                    : Collections.emptyList();
            parcel.offloadStatus = mOffloadStatus;
            try {
                callback.onCallbackStarted(parcel);
            } catch (RemoteException e) {
                // Not really very much to do here.
            }
        });
    }

    private TetherStatesParcel emptyTetherStatesParcel() {
        final TetherStatesParcel parcel = new TetherStatesParcel();
        parcel.availableList = new String[0];
        parcel.tetheredList = new String[0];
        parcel.localOnlyList = new String[0];
        parcel.erroredIfaceList = new String[0];
        parcel.lastErrorList = new int[0];

        return parcel;
    }

    private boolean hasCallingPermission(@NonNull String permission) {
        return mContext.checkCallingPermission(permission) == PERMISSION_GRANTED;
    }

    /** Unregister tethering event callback */
    void unregisterTetheringEventCallback(ITetheringEventCallback callback) {
        mHandler.post(() -> {
            mTetheringEventCallbacks.unregister(callback);
        });
    }

    private void reportUpstreamChanged(UpstreamNetworkState ns) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        final Network network = (ns != null) ? ns.network : null;
        final NetworkCapabilities capabilities = (ns != null) ? ns.networkCapabilities : null;
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onUpstreamChanged(network);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
        // Need to notify capabilities change after upstream network changed because new network's
        // capabilities should be checked every time.
        mNotificationUpdater.onUpstreamCapabilitiesChanged(capabilities);
    }

    private void reportConfigurationChanged(TetheringConfigurationParcel config) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onConfigurationChanged(config);
                    // TODO(b/148139325): send tetheringSupported on configuration change
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    private void reportTetherStateChanged(TetherStatesParcel states) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onTetherStatesChanged(states);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    private void reportTetherClientsChanged(List<TetheredClient> clients) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    final CallbackCookie cookie =
                            (CallbackCookie) mTetheringEventCallbacks.getBroadcastCookie(i);
                    if (!cookie.hasListClientsPermission) continue;
                    mTetheringEventCallbacks.getBroadcastItem(i).onTetherClientsChanged(clients);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    private void reportOffloadStatusChanged(final int status) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onOffloadStatusChanged(status);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer, @Nullable String[] args) {
        // Binder.java closes the resource for us.
        @SuppressWarnings("resource")
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump.");
            return;
        }

        pw.println("Tethering:");
        pw.increaseIndent();

        pw.println("Configuration:");
        pw.increaseIndent();
        final TetheringConfiguration cfg = mConfig;
        cfg.dump(pw);
        pw.decreaseIndent();

        pw.println("Entitlement:");
        pw.increaseIndent();
        mEntitlementMgr.dump(pw);
        pw.decreaseIndent();

        synchronized (mPublicSync) {
            pw.println("Tether state:");
            pw.increaseIndent();
            for (int i = 0; i < mTetherStates.size(); i++) {
                final String iface = mTetherStates.keyAt(i);
                final TetherState tetherState = mTetherStates.valueAt(i);
                pw.print(iface + " - ");

                switch (tetherState.lastState) {
                    case IpServer.STATE_UNAVAILABLE:
                        pw.print("UnavailableState");
                        break;
                    case IpServer.STATE_AVAILABLE:
                        pw.print("AvailableState");
                        break;
                    case IpServer.STATE_TETHERED:
                        pw.print("TetheredState");
                        break;
                    case IpServer.STATE_LOCAL_ONLY:
                        pw.print("LocalHotspotState");
                        break;
                    default:
                        pw.print("UnknownState");
                        break;
                }
                pw.println(" - lastError = " + tetherState.lastError);
            }
            pw.println("Upstream wanted: " + upstreamWanted());
            pw.println("Current upstream interface(s): " + mCurrentUpstreamIfaceSet);
            pw.decreaseIndent();
        }

        pw.println("Hardware offload:");
        pw.increaseIndent();
        mOffloadController.dump(pw);
        pw.decreaseIndent();

        pw.println("Log:");
        pw.increaseIndent();
        if (argsContain(args, "--short")) {
            pw.println("<log removed for brevity>");
        } else {
            mLog.dump(fd, pw, args);
        }
        pw.decreaseIndent();

        pw.decreaseIndent();
    }

    private static boolean argsContain(String[] args, String target) {
        for (String arg : args) {
            if (target.equals(arg)) return true;
        }
        return false;
    }

    private void updateConnectedClients(final List<WifiClient> wifiClients) {
        if (mConnectedClientsTracker.updateConnectedClients(mForwardedDownstreams, wifiClients)) {
            reportTetherClientsChanged(mConnectedClientsTracker.getLastTetheredClients());
        }
    }

    private IpServer.Callback makeControlCallback() {
        return new IpServer.Callback() {
            @Override
            public void updateInterfaceState(IpServer who, int state, int lastError) {
                notifyInterfaceStateChange(who, state, lastError);
            }

            @Override
            public void updateLinkProperties(IpServer who, LinkProperties newLp) {
                notifyLinkPropertiesChanged(who, newLp);
            }

            @Override
            public void dhcpLeasesChanged() {
                updateConnectedClients(null /* wifiClients */);
            }
        };
    }

    // TODO: Move into TetherMasterSM.
    private void notifyInterfaceStateChange(IpServer who, int state, int error) {
        final String iface = who.interfaceName();
        synchronized (mPublicSync) {
            final TetherState tetherState = mTetherStates.get(iface);
            if (tetherState != null && tetherState.ipServer.equals(who)) {
                tetherState.lastState = state;
                tetherState.lastError = error;
            } else {
                if (DBG) Log.d(TAG, "got notification from stale iface " + iface);
            }
        }

        mLog.log(String.format("OBSERVED iface=%s state=%s error=%s", iface, state, error));

        // If TetherMasterSM is in ErrorState, TetherMasterSM stays there.
        // Thus we give a chance for TetherMasterSM to recover to InitialState
        // by sending CMD_CLEAR_ERROR
        if (error == TETHER_ERROR_INTERNAL_ERROR) {
            mTetherMasterSM.sendMessage(TetherMasterSM.CMD_CLEAR_ERROR, who);
        }
        int which;
        switch (state) {
            case IpServer.STATE_UNAVAILABLE:
            case IpServer.STATE_AVAILABLE:
                which = TetherMasterSM.EVENT_IFACE_SERVING_STATE_INACTIVE;
                break;
            case IpServer.STATE_TETHERED:
            case IpServer.STATE_LOCAL_ONLY:
                which = TetherMasterSM.EVENT_IFACE_SERVING_STATE_ACTIVE;
                break;
            default:
                Log.wtf(TAG, "Unknown interface state: " + state);
                return;
        }
        mTetherMasterSM.sendMessage(which, state, 0, who);
        sendTetherStateChangedBroadcast();
    }

    private void notifyLinkPropertiesChanged(IpServer who, LinkProperties newLp) {
        final String iface = who.interfaceName();
        final int state;
        synchronized (mPublicSync) {
            final TetherState tetherState = mTetherStates.get(iface);
            if (tetherState != null && tetherState.ipServer.equals(who)) {
                state = tetherState.lastState;
            } else {
                mLog.log("got notification from stale iface " + iface);
                return;
            }
        }

        mLog.log(String.format(
                "OBSERVED LinkProperties update iface=%s state=%s lp=%s",
                iface, IpServer.getStateString(state), newLp));
        final int which = TetherMasterSM.EVENT_IFACE_UPDATE_LINKPROPERTIES;
        mTetherMasterSM.sendMessage(which, state, 0, newLp);
    }

    private void maybeTrackNewInterfaceLocked(final String iface) {
        // If we don't care about this type of interface, ignore.
        final int interfaceType = ifaceNameToType(iface);
        if (interfaceType == TETHERING_INVALID) {
            mLog.log(iface + " is not a tetherable iface, ignoring");
            return;
        }
        maybeTrackNewInterfaceLocked(iface, interfaceType);
    }

    private void maybeTrackNewInterfaceLocked(final String iface, int interfaceType) {
        // If we have already started a TISM for this interface, skip.
        if (mTetherStates.containsKey(iface)) {
            mLog.log("active iface (" + iface + ") reported as added, ignoring");
            return;
        }

        mLog.log("adding TetheringInterfaceStateMachine for: " + iface);
        final TetherState tetherState = new TetherState(
                new IpServer(iface, mLooper, interfaceType, mLog, mNetd,
                             makeControlCallback(), mConfig.enableLegacyDhcpServer,
                             mDeps.getIpServerDependencies()));
        mTetherStates.put(iface, tetherState);
        tetherState.ipServer.start();
    }

    private void stopTrackingInterfaceLocked(final String iface) {
        final TetherState tetherState = mTetherStates.get(iface);
        if (tetherState == null) {
            mLog.log("attempting to remove unknown iface (" + iface + "), ignoring");
            return;
        }
        tetherState.ipServer.stop();
        mLog.log("removing TetheringInterfaceStateMachine for: " + iface);
        mTetherStates.remove(iface);
    }

    private static String[] copy(String[] strarray) {
        return Arrays.copyOf(strarray, strarray.length);
    }
}
