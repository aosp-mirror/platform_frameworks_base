/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.IConnectivityManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfoInternal;
import android.net.DhcpStateMachine;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiMonitor;
import android.net.wifi.WifiNative;
import android.net.wifi.WifiStateMachine;
import android.net.wifi.Wps;
import android.net.wifi.Wps.Setup;
import android.net.wifi.p2p.WifiP2pDevice.Status;
import android.os.Binder;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import com.android.internal.R;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;

/**
 * WifiP2pService inclues a state machine to perform Wi-Fi p2p operations. Applications
 * communicate with this service to issue device discovery and connectivity requests
 * through the WifiP2pManager interface. The state machine communicates with the wifi
 * driver through wpa_supplicant and handles the event responses through WifiMonitor.
 *
 * Note that the term Wifi when used without a p2p suffix refers to the client mode
 * of Wifi operation
 * @hide
 */
public class WifiP2pService extends IWifiP2pManager.Stub {
    private static final String TAG = "WifiP2pService";
    private static final boolean DBG = true;
    private static final String NETWORKTYPE = "WIFI_P2P";

    private Context mContext;
    private String mInterface;
    private Notification mNotification;

    INetworkManagementService mNwService;
    private DhcpStateMachine mDhcpStateMachine;

    //Tracked to notify the user about wifi client/hotspot being shut down
    //during p2p bring up
    private int mWifiState = WifiManager.WIFI_STATE_DISABLED;
    private int mWifiApState = WifiManager.WIFI_AP_STATE_DISABLED;

    private P2pStateMachine mP2pStateMachine;
    private AsyncChannel mReplyChannel = new AsyncChannel();
    private AsyncChannel mWifiChannel;

    /* Two minutes comes from the wpa_supplicant setting */
    private static final int GROUP_NEGOTIATION_WAIT_TIME_MS = 120 * 1000;
    private static int mGroupNegotiationTimeoutIndex = 0;

    /**
     * Delay between restarts upon failure to setup connection with supplicant
     */
    private static final int P2P_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart p2p
     */
    private static final int P2P_RESTART_TRIES = 5;

    private int mP2pRestartCount = 0;

    private static final int BASE = Protocol.BASE_WIFI_P2P_SERVICE;

    /* Message sent to WifiStateMachine to indicate p2p enable is pending */
    public static final int P2P_ENABLE_PENDING              =   BASE + 1;
    /* Message sent to WifiStateMachine to indicate Wi-Fi client/hotspot operation can proceed */
    public static final int WIFI_ENABLE_PROCEED             =   BASE + 2;

    /* Delayed message to timeout of group negotiation */
    public static final int GROUP_NEGOTIATION_TIMED_OUT     =   BASE + 3;

    /* User accepted to disable Wi-Fi in order to enable p2p */
    private static final int WIFI_DISABLE_USER_ACCEPT       =   BASE + 4;
    /* User rejected to disable Wi-Fi in order to enable p2p */
    private static final int WIFI_DISABLE_USER_REJECT       =   BASE + 5;

    private final boolean mP2pSupported;
    private final String mDeviceType;
    private String mDeviceName;
    private String mDeviceAddress;

    /* When a group has been explicitly created by an app, we persist the group
     * even after all clients have been disconnected until an explicit remove
     * is invoked */
    private boolean mPersistGroup;

    private NetworkInfo mNetworkInfo;

    /* Is chosen as a unique range to avoid conflict with
       the range defined in Tethering.java */
    private static final String[] DHCP_RANGE = {"192.168.49.2", "192.168.49.254"};
    private static final String SERVER_ADDRESS = "192.168.49.1";

    public WifiP2pService(Context context) {
        mContext = context;

        mInterface = SystemProperties.get("wifi.interface", "wlan0");
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P, 0, NETWORKTYPE, "");

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mDeviceType = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_p2p_device_type);
        mDeviceName = getDefaultDeviceName();

        mP2pStateMachine = new P2pStateMachine(TAG, mP2pSupported);
        mP2pStateMachine.start();

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        mContext.registerReceiver(new WifiStateReceiver(), filter);

    }

    public void connectivityServiceReady() {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);
    }

    private class WifiStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_DISABLED);
            } else if (intent.getAction().equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                mWifiApState = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                        WifiManager.WIFI_AP_STATE_DISABLED);
            }
        }
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiP2pService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiP2pService");
    }

    /* We use the 4 digits of the ANDROID_ID to have a friendly
     * default that has low likelihood of collision with a peer */
    private String getDefaultDeviceName() {
        String id = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
        return "Android_" + id.substring(0,4);
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiP2pService
     */
    public Messenger getMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mP2pStateMachine.getHandler());
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiP2pService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
    }


    /**
     * Handles interaction with WifiStateMachine
     */
    private class P2pStateMachine extends StateMachine {

        private DefaultState mDefaultState = new DefaultState();
        private P2pNotSupportedState mP2pNotSupportedState = new P2pNotSupportedState();
        private P2pDisablingState mP2pDisablingState = new P2pDisablingState();
        private P2pDisabledState mP2pDisabledState = new P2pDisabledState();
        private WaitForUserActionState mWaitForUserActionState = new WaitForUserActionState();
        private WaitForWifiDisableState mWaitForWifiDisableState = new WaitForWifiDisableState();
        private P2pEnablingState mP2pEnablingState = new P2pEnablingState();
        private P2pEnabledState mP2pEnabledState = new P2pEnabledState();
        // Inactive is when p2p is enabled with no connectivity
        private InactiveState mInactiveState = new InactiveState();
        private GroupNegotiationState mGroupNegotiationState = new GroupNegotiationState();
        private GroupCreatedState mGroupCreatedState = new GroupCreatedState();

        private WifiMonitor mWifiMonitor = new WifiMonitor(this);

        private WifiP2pDeviceList mPeers = new WifiP2pDeviceList();
        private WifiP2pInfo mWifiP2pInfo = new WifiP2pInfo();
        private WifiP2pGroup mGroup;

        // Saved WifiP2pConfig from GO negotiation request
        private WifiP2pConfig mSavedGoNegotiationConfig;

        // Saved WifiP2pConfig from connect request
        private WifiP2pConfig mSavedConnectConfig;

        // Saved WifiP2pGroup from invitation request
        private WifiP2pGroup mSavedP2pGroup;

        P2pStateMachine(String name, boolean p2pSupported) {
            super(name);

            addState(mDefaultState);
                addState(mP2pNotSupportedState, mDefaultState);
                addState(mP2pDisablingState, mDefaultState);
                addState(mP2pDisabledState, mDefaultState);
                    addState(mWaitForUserActionState, mP2pDisabledState);
                    addState(mWaitForWifiDisableState, mP2pDisabledState);
                addState(mP2pEnablingState, mDefaultState);
                addState(mP2pEnabledState, mDefaultState);
                    addState(mInactiveState, mP2pEnabledState);
                    addState(mGroupNegotiationState, mP2pEnabledState);
                    addState(mGroupCreatedState, mP2pEnabledState);

            if (p2pSupported) {
                setInitialState(mP2pDisabledState);
            } else {
                setInitialState(mP2pNotSupportedState);
            }
        }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) logd("Full connection with WifiStateMachine established");
                        mWifiChannel = (AsyncChannel) message.obj;
                    } else {
                        loge("Full connection failure, error = " + message.arg1);
                        mWifiChannel = null;
                    }
                    break;

                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        loge("Send failed, client connection lost");
                    } else {
                        loge("Client connection lost with reason: " + message.arg1);
                    }
                    mWifiChannel = null;
                    break;

                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, getHandler(), message.replyTo);
                    break;
                case WifiStateMachine.WIFI_ENABLE_PENDING:
                    // Disable p2p operation before we can respond
                    sendMessage(WifiP2pManager.DISABLE_P2P);
                    deferMessage(message);
                    break;
                case WifiP2pManager.ENABLE_P2P:
                    replyToMessage(message, WifiP2pManager.ENABLE_P2P_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    replyToMessage(message, WifiP2pManager.DISABLE_P2P_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CONNECT:
                    replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REQUEST_PEERS:
                    replyToMessage(message, WifiP2pManager.RESPONSE_PEERS, mPeers);
                    break;
                case WifiP2pManager.REQUEST_CONNECTION_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_CONNECTION_INFO, mWifiP2pInfo);
                    break;
                case WifiP2pManager.REQUEST_GROUP_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_GROUP_INFO, mGroup);
                    break;
                // Ignore
                case WIFI_DISABLE_USER_ACCEPT:
                case WIFI_DISABLE_USER_REJECT:
                case GROUP_NEGOTIATION_TIMED_OUT:
                    break;
                default:
                    loge("Unhandled message " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pNotSupportedState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                // Allow Wi-Fi to proceed
                case WifiStateMachine.WIFI_ENABLE_PENDING:
                    replyToMessage(message, WIFI_ENABLE_PROCEED);
                    break;
                case WifiP2pManager.ENABLE_P2P:
                    replyToMessage(message, WifiP2pManager.ENABLE_P2P_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    replyToMessage(message, WifiP2pManager.DISABLE_P2P_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CONNECT:
                    replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
               default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pDisablingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            logd("stopping supplicant");
            if (!WifiNative.stopSupplicant()) {
                loge("Failed to stop supplicant, issue kill");
                WifiNative.killSupplicant();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    logd("Supplicant connection lost");
                    WifiNative.closeSupplicantConnection();
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiP2pManager.ENABLE_P2P:
                case WifiP2pManager.DISABLE_P2P:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }


    class P2pDisabledState extends State {
       @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiP2pManager.ENABLE_P2P:
                    OnClickListener listener = new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == DialogInterface.BUTTON_POSITIVE) {
                                sendMessage(WIFI_DISABLE_USER_ACCEPT);
                            } else {
                                sendMessage(WIFI_DISABLE_USER_REJECT);
                            }
                        }
                    };

                    // Show a user request dialog if we know Wi-Fi client/hotspot is in operation
                    if (mWifiState != WifiManager.WIFI_STATE_DISABLED ||
                            mWifiApState != WifiManager.WIFI_AP_STATE_DISABLED) {
                        Resources r = Resources.getSystem();
                        AlertDialog dialog = new AlertDialog.Builder(mContext)
                            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
                            .setMessage(r.getString(R.string.wifi_p2p_turnon_message))
                            .setPositiveButton(r.getString(R.string.ok), listener)
                            .setNegativeButton(r.getString(R.string.cancel), listener)
                            .create();
                        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                        dialog.show();
                        transitionTo(mWaitForUserActionState);
                    } else {
                        mWifiChannel.sendMessage(P2P_ENABLE_PENDING);
                        transitionTo(mWaitForWifiDisableState);
                    }
                    replyToMessage(message, WifiP2pManager.ENABLE_P2P_SUCCEEDED);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    replyToMessage(message, WifiP2pManager.DISABLE_P2P_SUCCEEDED);
                    break;
                case WifiStateMachine.WIFI_ENABLE_PENDING:
                    replyToMessage(message, WIFI_ENABLE_PROCEED);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class WaitForUserActionState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WIFI_DISABLE_USER_ACCEPT:
                    mWifiChannel.sendMessage(P2P_ENABLE_PENDING);
                    transitionTo(mWaitForWifiDisableState);
                    break;
                case WIFI_DISABLE_USER_REJECT:
                    logd("User rejected enabling p2p");
                    sendP2pStateChangedBroadcast(false);
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiP2pManager.ENABLE_P2P:
                case WifiP2pManager.DISABLE_P2P:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class WaitForWifiDisableState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiStateMachine.P2P_ENABLE_PROCEED:
                    try {
                        mNwService.wifiFirmwareReload(mInterface, "P2P");
                    } catch (Exception e) {
                        loge("Failed to reload p2p firmware " + e);
                        // continue
                    }

                    //A runtime crash can leave the interface up and
                    //this affects p2p when supplicant starts up.
                    //Ensure interface is down before a supplicant start.
                    try {
                        mNwService.setInterfaceDown(mInterface);
                    } catch (Exception e) {
                        if (DBG) Slog.w(TAG, "Unable to bring down wlan interface: " + e);
                    }

                    if (WifiNative.startP2pSupplicant()) {
                        mWifiMonitor.startMonitoring();
                        transitionTo(mP2pEnablingState);
                    } else {
                        notifyP2pEnableFailure();
                        transitionTo(mP2pDisabledState);
                    }
                    break;
                case WifiP2pManager.ENABLE_P2P:
                case WifiP2pManager.DISABLE_P2P:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnablingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    logd("P2p start successful");
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (++mP2pRestartCount <= P2P_RESTART_TRIES) {
                        loge("Failed to start p2p, retry");
                        WifiNative.killSupplicant();
                        sendMessageDelayed(WifiP2pManager.ENABLE_P2P, P2P_RESTART_INTERVAL_MSECS);
                    } else {
                        loge("Failed " + mP2pRestartCount + " times to start p2p, quit ");
                        mP2pRestartCount = 0;
                    }
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiP2pManager.ENABLE_P2P:
                case WifiP2pManager.DISABLE_P2P:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnabledState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendP2pStateChangedBroadcast(true);
            mNetworkInfo.setIsAvailable(true);
            initializeP2pSettings();
            showNotification();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiP2pManager.ENABLE_P2P:
                    replyToMessage(message, WifiP2pManager.ENABLE_P2P_SUCCEEDED);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    if (mPeers.clear()) sendP2pPeersChangedBroadcast();
                    replyToMessage(message, WifiP2pManager.DISABLE_P2P_SUCCEEDED);
                    transitionTo(mP2pDisablingState);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    int timeout = message.arg1;
                    if (WifiNative.p2pFind(timeout)) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    if (mDeviceAddress.equals(device.deviceAddress)) break;
                    mPeers.update(device);
                    sendP2pPeersChangedBroadcast();
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    if (mPeers.remove(device)) sendP2pPeersChangedBroadcast();
                    break;
                case WifiP2pManager.CONNECT:
                    if (DBG) logd(getName() + " sending connect");
                    mSavedConnectConfig = (WifiP2pConfig) message.obj;
                    mPersistGroup = false;
                    int netId = configuredNetworkId(mSavedConnectConfig.deviceAddress);
                    if (netId >= 0) {
                        //TODO: if failure, remove config and do a regular p2pConnect()
                        WifiNative.p2pReinvoke(netId, mSavedConnectConfig.deviceAddress);
                    } else {
                        boolean join = false;
                        if (isGroupOwner(mSavedConnectConfig.deviceAddress)) join = true;
                        String pin = WifiNative.p2pConnect(mSavedConnectConfig, join);
                        try {
                            Integer.parseInt(pin);
                            notifyWpsPin(pin, mSavedConnectConfig.deviceAddress);
                        } catch (NumberFormatException ignore) {
                            // do nothing if p2pConnect did not return a pin
                        }
                    }
                    updateDeviceStatus(mSavedConnectConfig.deviceAddress, Status.INVITED);
                    sendP2pPeersChangedBroadcast();
                    replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                    transitionTo(mGroupNegotiationState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:  /* Supplicant died */
                    loge("Connection lost, restart p2p");
                    WifiNative.killSupplicant();
                    WifiNative.closeSupplicantConnection();
                    if (mPeers.clear()) sendP2pPeersChangedBroadcast();
                    transitionTo(mP2pDisabledState);
                    sendMessageDelayed(WifiP2pManager.ENABLE_P2P, P2P_RESTART_INTERVAL_MSECS);
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    if (DBG) logd(getName() + " group started");
                    if (mGroup.isGroupOwner()) {
                        startDhcpServer(mGroup.getInterface());
                    } else {
                        mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(mContext,
                                P2pStateMachine.this, mGroup.getInterface());
                        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
                        WifiP2pDevice groupOwner = mGroup.getOwner();
                        updateDeviceStatus(groupOwner.deviceAddress, Status.CONNECTED);
                        sendP2pPeersChangedBroadcast();
                    }
                    transitionTo(mGroupCreatedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            sendP2pStateChangedBroadcast(false);
            mNetworkInfo.setIsAvailable(false);
            clearNotification();
        }
    }

    class InactiveState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            //Start listening every time we get inactive
            WifiNative.p2pListen();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT:
                    mSavedGoNegotiationConfig = (WifiP2pConfig) message.obj;
                    notifyP2pGoNegotationRequest(mSavedGoNegotiationConfig);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    mPersistGroup = true;
                    if (WifiNative.p2pGroupAdd()) {
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    transitionTo(mGroupNegotiationState);
                    break;
                case WifiMonitor.P2P_INVITATION_RECEIVED_EVENT:
                    WifiP2pGroup group = (WifiP2pGroup) message.obj;
                    notifyP2pInvitationReceived(group);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupNegotiationState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendMessageDelayed(obtainMessage(GROUP_NEGOTIATION_TIMED_OUT,
                    ++mGroupNegotiationTimeoutIndex, 0), GROUP_NEGOTIATION_WAIT_TIME_MS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                // We ignore these right now, since we get a GROUP_STARTED notification
                // afterwards
                case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                    if (DBG) logd(getName() + " go success");
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    if (DBG) logd(getName() + " go failure");
                    updateDeviceStatus(mSavedConnectConfig.deviceAddress, Status.FAILED);
                    mSavedConnectConfig = null;
                    sendP2pPeersChangedBroadcast();
                    transitionTo(mInactiveState);
                    break;
                case GROUP_NEGOTIATION_TIMED_OUT:
                    if (mGroupNegotiationTimeoutIndex == message.arg1) {
                        if (DBG) logd("Group negotiation timed out");
                        updateDeviceStatus(mSavedConnectConfig.deviceAddress, Status.FAILED);
                        mSavedConnectConfig = null;
                        sendP2pPeersChangedBroadcast();
                        transitionTo(mInactiveState);
                    }
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    /* Discovery will break negotiation */
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupCreatedState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);

            //DHCP server has already been started if I am a group owner
            if (mGroup.isGroupOwner()) {
                setWifiP2pInfoOnGroupFormation(SERVER_ADDRESS);
                sendP2pConnectionChangedBroadcast();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.AP_STA_CONNECTED_EVENT:
                    //After a GO setup, STA connected event comes with interface address
                    String interfaceAddress = (String) message.obj;
                    String deviceAddress = getDeviceAddress(interfaceAddress);
                    if (deviceAddress != null) {
                        mGroup.addClient(deviceAddress);
                        updateDeviceStatus(deviceAddress, Status.CONNECTED);
                        if (DBG) logd(getName() + " ap sta connected");
                        sendP2pPeersChangedBroadcast();
                    } else {
                        loge("Connect on unknown device address : " + interfaceAddress);
                    }
                    break;
                case WifiMonitor.AP_STA_DISCONNECTED_EVENT:
                    interfaceAddress = (String) message.obj;
                    deviceAddress = getDeviceAddress(interfaceAddress);
                    if (deviceAddress != null) {
                        updateDeviceStatus(deviceAddress, Status.AVAILABLE);
                        if (mGroup.removeClient(deviceAddress)) {
                            if (DBG) logd("Removed client " + deviceAddress);
                            if (!mPersistGroup && mGroup.isClientListEmpty()) {
                                Slog.d(TAG, "Client list empty, remove non-persistent p2p group");
                                WifiNative.p2pGroupRemove(mGroup.getInterface());
                            }
                        } else {
                            if (DBG) logd("Failed to remove client " + deviceAddress);
                            for (WifiP2pDevice c : mGroup.getClientList()) {
                                if (DBG) logd("client " + c.deviceAddress);
                            }
                        }
                        sendP2pPeersChangedBroadcast();
                        if (DBG) loge(getName() + " ap sta disconnected");
                    } else {
                        loge("Disconnect on unknown device address : " + interfaceAddress);
                    }
                    break;
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    DhcpInfoInternal dhcpInfo = (DhcpInfoInternal) message.obj;
                    if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS &&
                            dhcpInfo != null) {
                        if (DBG) logd("DhcpInfo: " + dhcpInfo);
                        setWifiP2pInfoOnGroupFormation(dhcpInfo.serverAddress);
                        sendP2pConnectionChangedBroadcast();
                    } else {
                        WifiNative.p2pGroupRemove(mGroup.getInterface());
                    }
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    if (DBG) loge(getName() + " remove group");
                    if (WifiNative.p2pGroupRemove(mGroup.getInterface())) {
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                    if (DBG) loge(getName() + " group removed");
                    Collection <WifiP2pDevice> devices = mGroup.getClientList();
                    boolean changed = false;
                    for (WifiP2pDevice d : mPeers.getDeviceList()) {
                        if (devices.contains(d) || mGroup.getOwner().equals(d)) {
                            d.status = Status.AVAILABLE;
                            changed = true;
                        }
                    }

                    if (mGroup.isGroupOwner()) {
                        stopDhcpServer();
                    } else {
                        if (DBG) logd("stop DHCP client");
                        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
                        mDhcpStateMachine.quit();
                        mDhcpStateMachine = null;
                    }

                    mGroup = null;
                    if (changed) sendP2pPeersChangedBroadcast();
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    if (device.equals(mGroup.getOwner())) {
                        logd("Lost the group owner, killing p2p connection");
                        WifiNative.p2pGroupRemove(mGroup.getInterface());
                    } else if (mGroup.removeClient(device)) {
                        if (!mPersistGroup && mGroup.isClientListEmpty()) {
                            Slog.d(TAG, "Client list empty, removing a non-persistent p2p group");
                            WifiNative.p2pGroupRemove(mGroup.getInterface());
                        }
                    }
                    return NOT_HANDLED; // Do the regular device lost handling
                case WifiP2pManager.DISABLE_P2P:
                    sendMessage(WifiP2pManager.REMOVE_GROUP);
                    deferMessage(message);
                    break;
                case WifiP2pManager.CONNECT:
                    WifiP2pConfig config = (WifiP2pConfig) message.obj;
                    logd("Inviting device : " + config.deviceAddress);
                    if (WifiNative.p2pInvite(mGroup, config.deviceAddress)) {
                        updateDeviceStatus(config.deviceAddress, Status.INVITED);
                        sendP2pPeersChangedBroadcast();
                        replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    // TODO: figure out updating the status to declined when invitation is rejected
                    break;
                case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                    logd("===> INVITATION RESULT EVENT : " + message.obj);
                    break;
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                    notifyP2pProvDiscPbcRequest((WifiP2pDevice) message.obj);
                    break;
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                    notifyP2pProvDiscPinRequest((WifiP2pDevice) message.obj);
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    Slog.e(TAG, "Duplicate group creation event notice, ignore");
                    break;
                case WifiP2pManager.WPS_PBC:
                    WifiNative.wpsPbc();
                    break;
                case WifiP2pManager.WPS_PIN:
                    WifiNative.wpsPin((String) message.obj);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        public void exit() {
            setWifiP2pInfoOnGroupTermination();
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
            sendP2pConnectionChangedBroadcast();
        }
    }

    private void sendP2pStateChangedBroadcast(boolean enabled) {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (enabled) {
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED);
        } else {
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED);
        }
        mContext.sendStickyBroadcast(intent);
    }

    private void sendP2pPeersChangedBroadcast() {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent);
    }

    private void sendP2pConnectionChangedBroadcast() {
        if (DBG) logd("sending p2p connection changed broadcast");
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, new WifiP2pInfo(mWifiP2pInfo));
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        mContext.sendStickyBroadcast(intent);
    }

    private void startDhcpServer(String intf) {
        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(intf);
            ifcg.addr = new LinkAddress(NetworkUtils.numericToInetAddress(
                        SERVER_ADDRESS), 24);
            ifcg.interfaceFlags = "[up]";
            mNwService.setInterfaceConfig(intf, ifcg);
            /* This starts the dnsmasq server */
            mNwService.startTethering(DHCP_RANGE);
        } catch (Exception e) {
            loge("Error configuring interface " + intf + ", :" + e);
            return;
        }

        logd("Started Dhcp server on " + intf);
   }

    private void stopDhcpServer() {
        try {
            mNwService.stopTethering();
        } catch (Exception e) {
            loge("Error stopping Dhcp server" + e);
            return;
        }

        logd("Stopped Dhcp server");
    }

    private void notifyP2pEnableFailure() {
        Resources r = Resources.getSystem();
        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setMessage(r.getString(R.string.wifi_p2p_failed_message))
            .setPositiveButton(r.getString(R.string.ok), null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyWpsPin(String pin, String peerAddress) {
        Resources r = Resources.getSystem();
        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setMessage(r.getString(R.string.wifi_p2p_pin_display_message, pin, peerAddress))
            .setPositiveButton(r.getString(R.string.ok), null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyP2pGoNegotationRequest(WifiP2pConfig config) {
        Resources r = Resources.getSystem();
        Wps wps = config.wps;
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_go_negotiation_request_alert, null);
        final EditText pin = (EditText) textEntryView .findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), new OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (DBG) logd(getName() + " connect " + pin.getText());

                            if (pin.getVisibility() == View.GONE) {
                                mSavedGoNegotiationConfig.wps.setup = Setup.PBC;
                            } else {
                                mSavedGoNegotiationConfig.wps.setup = Setup.KEYPAD;
                                mSavedGoNegotiationConfig.wps.pin = pin.getText().toString();
                            }
                            sendMessage(WifiP2pManager.CONNECT, mSavedGoNegotiationConfig);
                            mSavedGoNegotiationConfig = null;
                        }
                    })
            .setNegativeButton(r.getString(R.string.cancel), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DBG) logd(getName() + " ignore connect");
                            mSavedGoNegotiationConfig = null;
                        }
                    })
            .create();

        if (wps.setup == Setup.PBC) {
            pin.setVisibility(View.GONE);
            dialog.setMessage(r.getString(R.string.wifi_p2p_pbc_go_negotiation_request_message,
                        config.deviceAddress));
        } else {
            dialog.setMessage(r.getString(R.string.wifi_p2p_pin_go_negotiation_request_message,
                        config.deviceAddress));
        }

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyP2pProvDiscPbcRequest(WifiP2pDevice peer) {
        Resources r = Resources.getSystem();
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_go_negotiation_request_alert, null);
        final EditText pin = (EditText) textEntryView .findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), new OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                                if (DBG) logd(getName() + " wps_pbc");
                                sendMessage(WifiP2pManager.WPS_PBC);
                        }
                    })
            .setNegativeButton(r.getString(R.string.cancel), null)
            .create();

        pin.setVisibility(View.GONE);
        dialog.setMessage(r.getString(R.string.wifi_p2p_pbc_go_negotiation_request_message,
                        peer.deviceAddress));

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyP2pProvDiscPinRequest(WifiP2pDevice peer) {
        Resources r = Resources.getSystem();
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_go_negotiation_request_alert, null);
        final EditText pin = (EditText) textEntryView .findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (DBG) logd(getName() + " wps_pin");
                        sendMessage(WifiP2pManager.WPS_PIN, pin.getText().toString());
                    }
                    })
            .setNegativeButton(r.getString(R.string.cancel), null)
            .create();

        dialog.setMessage(r.getString(R.string.wifi_p2p_pin_go_negotiation_request_message,
                        peer.deviceAddress));

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyP2pInvitationReceived(WifiP2pGroup group) {
        mSavedP2pGroup = group;
        Resources r = Resources.getSystem();
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_go_negotiation_request_alert, null);
        final EditText pin = (EditText) textEntryView .findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), new OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                                WifiP2pConfig config = new WifiP2pConfig();
                                config.deviceAddress = mSavedP2pGroup.getOwner().deviceAddress;
                                if (DBG) logd(getName() + " connect to invited group");
                                sendMessage(WifiP2pManager.CONNECT, config);
                                mSavedP2pGroup = null;
                        }
                    })
            .setNegativeButton(r.getString(R.string.cancel), null)
            .create();

        pin.setVisibility(View.GONE);
        dialog.setMessage(r.getString(R.string.wifi_p2p_pbc_go_negotiation_request_message,
                        group.getOwner().deviceAddress));

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void updateDeviceStatus(String deviceAddress, Status status) {
        for (WifiP2pDevice d : mPeers.getDeviceList()) {
            if (d.deviceAddress.equals(deviceAddress)) {
                d.status = status;
            }
        }
    }

    private boolean isGroupOwner(String deviceAddress) {
        for (WifiP2pDevice d : mPeers.getDeviceList()) {
            if (d.deviceAddress.equals(deviceAddress)) {
                return d.isGroupOwner();
            }
        }
        return false;
    }

    //TODO: implement when wpa_supplicant is fixed
    private int configuredNetworkId(String deviceAddress) {
        return -1;
    }

    private void setWifiP2pInfoOnGroupFormation(String serverAddress) {
        mWifiP2pInfo.groupFormed = true;
        mWifiP2pInfo.isGroupOwner = mGroup.isGroupOwner();
        mWifiP2pInfo.groupOwnerAddress = NetworkUtils.numericToInetAddress(serverAddress);
    }

    private void setWifiP2pInfoOnGroupTermination() {
        mWifiP2pInfo.groupFormed = false;
        mWifiP2pInfo.isGroupOwner = false;
        mWifiP2pInfo.groupOwnerAddress = null;
    }

    private String getDeviceAddress(String interfaceAddress) {
        for (WifiP2pDevice d : mPeers.getDeviceList()) {
            if (interfaceAddress.equals(WifiNative.p2pGetInterfaceAddress(d.deviceAddress))) {
                return d.deviceAddress;
            }
        }
        return null;
    }

    private void initializeP2pSettings() {
        WifiNative.setPersistentReconnect(true);
        WifiNative.setDeviceName(mDeviceName);
        WifiNative.setDeviceType(mDeviceType);

        mDeviceAddress = WifiNative.p2pGetDeviceAddress();
        if (DBG) Slog.d(TAG, "DeviceAddress: " + mDeviceAddress);
    }

    //State machine initiated requests can have replyTo set to null indicating
    //there are no recepients, we ignore those reply actions
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        mReplyChannel.replyToMessage(msg, what);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        mReplyChannel.replyToMessage(msg, what, arg1);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        mReplyChannel.replyToMessage(msg, what, obj);
    }

    private void logd(String s) {
        Slog.d(TAG, s);
    }

    private void loge(String s) {
        Slog.e(TAG, s);
    }

    private void showNotification() {
        NotificationManager notificationManager =
            (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null || mNotification != null) {
            return;
        }

        Intent intent = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        Resources r = Resources.getSystem();
        CharSequence title = r.getText(R.string.wifi_p2p_enabled_notification_title);
        CharSequence message = r.getText(R.string.wifi_p2p_enabled_notification_message);

        mNotification = new Notification();
        mNotification.when = 0;
        //TODO: might change to be a seperate icon
        mNotification.icon = R.drawable.stat_sys_tether_wifi;
        mNotification.defaults &= ~Notification.DEFAULT_SOUND;
        mNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mNotification.tickerText = title;
        mNotification.setLatestEventInfo(mContext, title, message, pi);

        notificationManager.notify(mNotification.icon, mNotification);
    }

    private void clearNotification() {
        NotificationManager notificationManager =
            (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && mNotification != null) {
            notificationManager.cancel(mNotification.icon);
            mNotification = null;
        }
    }

    }
}
