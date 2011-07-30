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
import android.net.wifi.WpsConfiguration;
import android.net.wifi.WpsConfiguration.Setup;
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

    INetworkManagementService mNwService;
    private DhcpStateMachine mDhcpStateMachine;

    //Tracked to notify the user about wifi client/hotspot being shut down
    //during p2p bring up
    private int mWifiState = WifiManager.WIFI_STATE_DISABLED;
    private int mWifiApState = WifiManager.WIFI_AP_STATE_DISABLED;

    private P2pStateMachine mP2pStateMachine;
    private AsyncChannel mReplyChannel = new AsyncChannel();;
    private AsyncChannel mWifiChannel;

    private static final int GROUP_NEGOTIATION_WAIT_TIME_MS = 60 * 1000;
    private static int mGroupNegotiationTimeoutIndex = 0;

    private static final int BASE = Protocol.BASE_WIFI_P2P_SERVICE;

    /* Message sent to WifiStateMachine to indicate p2p enable is pending */
    public static final int P2P_ENABLE_PENDING              =   BASE + 1;
    /* Message sent to WifiStateMachine to indicate Wi-Fi client/hotspot operation can proceed */
    public static final int WIFI_ENABLE_PROCEED             =   BASE + 2;

    /* Delayed message to timeout of group negotiation */
    public static final int GROUP_NEGOTIATION_TIMED_OUT     =   BASE + 3;

    /* User accepted to disable Wi-Fi in order to enable p2p */
    private static final int WIFI_DISABLE_USER_ACCEPT       =   BASE + 11;

    private final boolean mP2pSupported;

    private NetworkInfo mNetworkInfo;
    private LinkProperties mLinkProperties;

    public WifiP2pService(Context context) {
        mContext = context;

        mInterface = SystemProperties.get("wifi.interface", "wlan0");
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P, 0, NETWORKTYPE, "");
        mLinkProperties = new LinkProperties();

        mP2pSupported = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_p2p_support);

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

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiP2pService
     */
    public Messenger getMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mP2pStateMachine.getHandler());
    }

    /**
     * Return if p2p is supported
     */
    public boolean isP2pSupported() {
        enforceAccessPermission();
        return mP2pSupported;
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
        private WaitForWifiDisableState mWaitForWifiDisableState = new WaitForWifiDisableState();
        private P2pEnablingState mP2pEnablingState = new P2pEnablingState();
        private P2pEnabledState mP2pEnabledState = new P2pEnabledState();
        // Inactive is when p2p is enabled with no connectivity
        private InactiveState mInactiveState = new InactiveState();
        private GroupNegotiationState mGroupNegotiationState = new GroupNegotiationState();
        private GroupCreatedState mGroupCreatedState = new GroupCreatedState();

        private WifiMonitor mWifiMonitor = new WifiMonitor(this);

        private WifiP2pDeviceList mPeers = new WifiP2pDeviceList();
        private WifiP2pGroup mGroup;

        // Saved enable request message so the state machine can send an appropriate response
        private Message mSavedEnableRequestMessage;

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
                addState(mWaitForWifiDisableState, mDefaultState);
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

    // TODO: Respond to every p2p request with success/failure
    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "Full connection with WifiStateMachine established");
                        mWifiChannel = (AsyncChannel) message.obj;
                    } else {
                        Slog.e(TAG, "Full connection failure, error = " + message.arg1);
                        mWifiChannel = null;
                    }
                    break;

                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        Slog.e(TAG, "Send failed, client connection lost");
                    } else {
                        Slog.e(TAG, "Client connection lost with reason: " + message.arg1);
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
                    mReplyChannel.replyToMessage(message, WifiP2pManager.ENABLE_P2P_FAILED);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.DISABLE_P2P_FAILED);
                    break;
                case WifiP2pManager.START_LISTEN_MODE:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED);
                    break;
                case WifiP2pManager.CANCEL_DISCOVER_PEERS:
                    mReplyChannel.replyToMessage(message,
                            WifiP2pManager.CANCEL_DISCOVER_PEERS_FAILED);
                    break;
                case WifiP2pManager.CONNECT:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED);
                    break;
                case WifiP2pManager.REJECT:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.REJECT_FAILED);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED);
                    break;
                // TODO: fix
                case WifiP2pManager.REQUEST_SETTINGS:
                case WifiP2pManager.REQUEST_PEERS:
                case WifiP2pManager.REQUEST_CONNECTION_STATUS:
                    break;
                // Ignore
                case WIFI_DISABLE_USER_ACCEPT:
                case GROUP_NEGOTIATION_TIMED_OUT:
                    break;
                default:
                    Slog.e(TAG, "Unhandled message " + message);
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
                    mReplyChannel.replyToMessage(message, WIFI_ENABLE_PROCEED);
                    break;
                case WifiP2pManager.ENABLE_P2P:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.ENABLE_P2P_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.DISABLE_P2P_FAILED,
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
            if (DBG) Slog.d(TAG, getName());
            transitionTo(mP2pDisabledState);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    transitionTo(mP2pDisabledState);
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
            if (DBG) Slog.d(TAG, getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiP2pManager.ENABLE_P2P:
                    mSavedEnableRequestMessage = Message.obtain(message);
                    OnClickListener listener = new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == DialogInterface.BUTTON_POSITIVE) {
                                sendMessage(WIFI_DISABLE_USER_ACCEPT);
                            } else {
                                mReplyChannel.replyToMessage(mSavedEnableRequestMessage,
                                        WifiP2pManager.ENABLE_P2P_FAILED);
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
                    } else {
                        mWifiChannel.sendMessage(P2P_ENABLE_PENDING);
                        transitionTo(mWaitForWifiDisableState);
                    }
                    break;
                case WIFI_DISABLE_USER_ACCEPT:
                    mWifiChannel.sendMessage(P2P_ENABLE_PENDING);
                    transitionTo(mWaitForWifiDisableState);
                    break;
                case WifiStateMachine.WIFI_ENABLE_PENDING:
                    mReplyChannel.replyToMessage(message, WIFI_ENABLE_PROCEED);
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
            if (DBG) Slog.d(TAG, getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiStateMachine.P2P_ENABLE_PROCEED:
                    try {
                        mNwService.wifiFirmwareReload(mInterface, "P2P");
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to reload p2p firmware " + e);
                        // continue
                    }
                    if (WifiNative.startSupplicant()) {
                        Slog.d(TAG, "Wi-fi Direct start successful");
                        mWifiMonitor.startMonitoring();
                        transitionTo(mP2pEnablingState);
                    } else {
                        notifyP2pEnableFailure();
                        mReplyChannel.replyToMessage(mSavedEnableRequestMessage,
                                WifiP2pManager.ENABLE_P2P_FAILED);
                        transitionTo(mP2pDisabledState);
                    }
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
            if (DBG) Slog.d(TAG, getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    mReplyChannel.replyToMessage(mSavedEnableRequestMessage,
                            WifiP2pManager.ENABLE_P2P_SUCCEEDED);
                    transitionTo(mInactiveState);
                    break;
                case WifiP2pManager.DISABLE_P2P:
                    //TODO: fix
                    WifiNative.killSupplicant();
                    transitionTo(mP2pDisabledState);
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnabledState extends State {
        @Override
        public void enter() {
            if (DBG) Slog.d(TAG, getName());
            sendP2pStateChangedBroadcast(true);
            mNetworkInfo.setIsAvailable(true);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiP2pManager.DISABLE_P2P:
                    // TODO: use stopSupplicant after control channel fixed
                    WifiNative.killSupplicant();
                    transitionTo(mP2pDisablingState);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    int timeout = message.arg1;
                    WifiNative.p2pFlush();
                    WifiNative.p2pFind(timeout);
                   break;
                case WifiP2pManager.REQUEST_PEERS:
                    mReplyChannel.replyToMessage(message, WifiP2pManager.RESPONSE_PEERS, mPeers);
                    break;
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    mPeers.add(device);
                    sendP2pPeersChangedBroadcast();
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    if (mPeers.remove(device)) sendP2pPeersChangedBroadcast();
                    break;
                case WifiP2pManager.CONNECT:
                    if (DBG) Slog.d(TAG, getName() + " sending connect");
                    mSavedConnectConfig = (WifiP2pConfig) message.obj;
                    String pin = WifiNative.p2pConnect(mSavedConnectConfig);
                    try {
                        Integer.parseInt(pin);
                        notifyWpsPin(pin, mSavedConnectConfig.deviceAddress);
                    } catch (NumberFormatException ignore) {
                        // do nothing if p2pConnect did not return a pin
                    }
                    updateDeviceStatus(mSavedConnectConfig.deviceAddress, Status.INVITED);
                    sendP2pPeersChangedBroadcast();
                    transitionTo(mGroupNegotiationState);
                    break;
                case WifiP2pManager.REJECT:
                    if (DBG) Slog.d(TAG, getName() + " sending reject");
                    WifiNative.p2pReject((String) message.obj);
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
        }
    }

    class InactiveState extends State {
        @Override public void enter() {
            if (DBG) Slog.d(TAG, getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT:
                    mSavedGoNegotiationConfig = (WifiP2pConfig) message.obj;
                    notifyP2pGoNegotationRequest(mSavedGoNegotiationConfig);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    WifiNative.p2pGroupAdd();
                    transitionTo(mGroupNegotiationState);
                    break;
                case WifiMonitor.P2P_INVITATION_RECEIVED_EVENT:
                    WifiP2pGroup group = (WifiP2pGroup) message.obj;
                    notifyP2pInvitationReceived(group);
                    break;
                case WifiP2pManager.REQUEST_PEERS:
                    return NOT_HANDLED;
               default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupNegotiationState extends State {
        @Override
        public void enter() {
            if (DBG) Slog.d(TAG, getName());
            sendMessageDelayed(obtainMessage(GROUP_NEGOTIATION_TIMED_OUT,
                    ++mGroupNegotiationTimeoutIndex, 0), GROUP_NEGOTIATION_WAIT_TIME_MS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                // We ignore these right now, since we get a GROUP_STARTED notification
                // afterwards
                case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                    if (DBG) Slog.d(TAG, getName() + " go success");
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    if (DBG) Slog.d(TAG, getName() + " go failure");
                    updateDeviceStatus(mSavedConnectConfig.deviceAddress, Status.FAILED);
                    mSavedConnectConfig = null;
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    if (DBG) Slog.d(TAG, getName() + " group started");
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
                case WifiP2pManager.CANCEL_CONNECT:
                    // TODO: fix
                    break;
                case GROUP_NEGOTIATION_TIMED_OUT:
                    if (mGroupNegotiationTimeoutIndex == message.arg1) {
                        if (DBG) Slog.d(TAG, "Group negotiation timed out");
                        updateDeviceStatus(mSavedConnectConfig.deviceAddress, Status.FAILED);
                        mSavedConnectConfig = null;
                        transitionTo(mInactiveState);
                    }
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
            if (DBG) Slog.d(TAG, getName());
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);

            if (mGroup.isGroupOwner()) {
                sendP2pConnectionChangedBroadcast();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Slog.d(TAG, getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.AP_STA_CONNECTED_EVENT:
                    String address = (String) message.obj;
                    mGroup.addClient(address);
                    updateDeviceStatus(address, Status.CONNECTED);
                    if (DBG) Slog.d(TAG, getName() + " ap sta connected");
                    sendP2pPeersChangedBroadcast();
                    break;
                case WifiMonitor.AP_STA_DISCONNECTED_EVENT:
                    address = (String) message.obj;
                    updateDeviceStatus(address, Status.AVAILABLE);
                    if (mGroup.removeClient(address)) {
                        if (DBG) Slog.d(TAG, "Removed client " + address);
                        if (mGroup.isClientListEmpty()) {
                            Slog.d(TAG, "Client list empty, killing p2p connection");
                            sendMessage(WifiP2pManager.REMOVE_GROUP);
                        } else {
                            // Just send a notification
                            sendP2pPeersChangedBroadcast();
                        }
                    } else {
                        if (DBG) Slog.d(TAG, "Failed to remove client " + address);
                        for (WifiP2pDevice c : mGroup.getClientList()) {
                            if (DBG) Slog.d(TAG,"client " + c.deviceAddress);
                        }
                    }
                    if (DBG) Slog.e(TAG, getName() + " ap sta disconnected");
                    break;
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    DhcpInfoInternal dhcpInfo = (DhcpInfoInternal) message.obj;
                    if (DBG) Slog.d(TAG, "DhcpInfo: " + dhcpInfo);
                    if (dhcpInfo != null) {
                        mLinkProperties = dhcpInfo.makeLinkProperties();
                        mLinkProperties.setInterfaceName(mGroup.getInterface());
                        sendP2pConnectionChangedBroadcast();
                    }
                    break;
                //disconnect & remove group have same effect when connected
                case WifiP2pManager.CANCEL_CONNECT:
                case WifiP2pManager.REMOVE_GROUP:
                    if (DBG) Slog.e(TAG, getName() + " remove group");
                    WifiNative.p2pFlush();
                    WifiNative.p2pGroupRemove(mGroup.getInterface());
                    break;
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                    if (DBG) Slog.e(TAG, getName() + " group removed");
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
                        if (DBG) Slog.d(TAG, "stop DHCP client");
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
                        Slog.d(TAG, "Lost the group owner, killing p2p connection");
                        WifiNative.p2pFlush();
                        WifiNative.p2pGroupRemove(mGroup.getInterface());
                    } else if (mGroup.removeClient(device) && mGroup.isClientListEmpty()) {
                        Slog.d(TAG, "Client list empty, killing p2p connection");
                        WifiNative.p2pFlush();
                        WifiNative.p2pGroupRemove(mGroup.getInterface());
                    }
                    return NOT_HANDLED; // Do the regular device lost handling
                case WifiP2pManager.DISABLE_P2P:
                    sendMessage(WifiP2pManager.REMOVE_GROUP);
                    deferMessage(message);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    int timeout = message.arg1;
                    WifiNative.p2pFind(timeout);
                    break;
                case WifiP2pManager.CONNECT:
                    WifiP2pConfig config = (WifiP2pConfig) message.obj;
                    Slog.d(TAG, "Inviting device : " + config.deviceAddress);
                    WifiNative.p2pInvite(mGroup, config.deviceAddress);
                    updateDeviceStatus(config.deviceAddress, Status.INVITED);
                    sendP2pPeersChangedBroadcast();
                    // TODO: figure out updating the status to declined when invitation is rejected
                    break;
                case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                    Slog.d(TAG,"===> INVITATION RESULT EVENT : " + message.obj);
                    break;
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                    notifyP2pProvDiscPbcRequest((WifiP2pDevice) message.obj);
                    break;
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                    notifyP2pProvDiscPinRequest((WifiP2pDevice) message.obj);
                    break;
                case WifiP2pManager.WPS_PBC:
                    WifiNative.p2pWpsPbc();
                    break;
                case WifiP2pManager.WPS_PIN:
                    WifiNative.p2pWpsPin((String) message.obj);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        public void exit() {
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
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
        if (DBG) Slog.d(TAG, "sending p2p connection changed broadcast");
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        intent.putExtra(WifiP2pManager.EXTRA_LINK_PROPERTIES,
                new LinkProperties (mLinkProperties));
        mContext.sendStickyBroadcast(intent);
    }

    private void startDhcpServer(String intf) {
        /* Is chosen as a unique range to avoid conflict with
           the range defined in Tethering.java */
        String[] dhcp_range = {"192.168.49.2", "192.168.49.254"};
        String serverAddress = "192.168.49.1";

        mLinkProperties.clear();
        mLinkProperties.setInterfaceName(mGroup.getInterface());

        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(intf);
            ifcg.addr = new LinkAddress(NetworkUtils.numericToInetAddress(
                        serverAddress), 24);
            ifcg.interfaceFlags = "[up]";
            mNwService.setInterfaceConfig(intf, ifcg);
            /* This starts the dnsmasq server */
            mNwService.startTethering(dhcp_range);
        } catch (Exception e) {
            Slog.e(TAG, "Error configuring interface " + intf + ", :" + e);
            return;
        }

        mLinkProperties.addDns(NetworkUtils.numericToInetAddress(serverAddress));
        Slog.d(TAG, "Started Dhcp server on " + intf);
    }

    private void stopDhcpServer() {
        try {
            mNwService.stopTethering();
        } catch (Exception e) {
            Slog.e(TAG, "Error stopping Dhcp server" + e);
            return;
        }

        Slog.d(TAG, "Stopped Dhcp server");
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
        WpsConfiguration wpsConfig = config.wpsConfig;
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_go_negotiation_request_alert, null);
        final EditText pin = (EditText) textEntryView .findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), new OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (DBG) Slog.d(TAG, getName() + " connect " + pin.getText());

                            if (pin.getVisibility() == View.GONE) {
                                mSavedGoNegotiationConfig.wpsConfig.setup = Setup.PBC;
                            } else {
                                mSavedGoNegotiationConfig.wpsConfig.setup = Setup.KEYPAD;
                                mSavedGoNegotiationConfig.wpsConfig.pin = pin.getText().toString();
                            }
                            sendMessage(WifiP2pManager.CONNECT, mSavedGoNegotiationConfig);
                            mSavedGoNegotiationConfig = null;
                        }
                    })
            .setNegativeButton(r.getString(R.string.cancel), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                                if (DBG) Slog.d(TAG, getName() + " reject");
                                sendMessage(WifiP2pManager.REJECT,
                                        mSavedGoNegotiationConfig.deviceAddress);
                                mSavedGoNegotiationConfig = null;
                        }
                    })
            .create();

        if (wpsConfig.setup == Setup.PBC) {
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
                                if (DBG) Slog.d(TAG, getName() + " wps_pbc");
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
                        if (DBG) Slog.d(TAG, getName() + " wps_pin");
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
                                config.joinExistingGroup = true;
                                if (DBG) Slog.d(TAG, getName() + " connect to invited group");
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
           // TODO: fix later
           // if (d.deviceAddress.equals(deviceAddress)) {
            if (d.deviceAddress.startsWith(deviceAddress.substring(0, 8))) {
                d.status = status;
            }
        }
    }
    }
}
