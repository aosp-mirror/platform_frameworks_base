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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

/**
 * This class provides the API for managing Wi-Fi peer-to-peer connectivity. This lets an
 * application discover available peers, setup connection to peers and query for the list of peers.
 * When a p2p connection is formed over wifi, the device continues to maintain the uplink
 * connection over mobile or any other available network for internet connectivity on the device.
 *
 * <p> The API is asynchronous and response to a request from an application is sent in the form
 * of a {@link android.os.Message} on a {@link android.os.Handler} that needs to be initialized
 * by the application right at the beginning before any p2p operations are performed via
 * {@link #initialize}.
 *
 * <p> An application can request for the current list of peers using {@link #requestPeers}. The
 * {@link #RESPONSE_PEERS} message on the handler indicates that the peer list is available.
 * Use {@link #peersInResponse} to extract the peer device list upon the receiving the
 * {@link #RESPONSE_PEERS} message.
 *
 * <p> If an application needs to initiate a discovery, use {@link #discoverPeers} and listen
 * to {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent action to initiate a request to fetch
 * list of peers with {@link #requestPeers}. An initiated discovery request from an application
 * stays active until the device starts connecting to a peer or forms a p2p group.
 *
 * <p> An application can initiate a connection request to a peer through {@link #connect}. See
 * {@link WifiP2pConfig} for details on setting up the configuration. For communication with legacy
 * Wi-Fi devices that do not support p2p, an app can create a group using {@link #createGroup}
 * which creates an access point whose details can be fetched with {@link #requestGroupInfo}.
 *
 * <p> After a successful group formation through {@link #createGroup} or through {@link #connect},
 * use {@link #requestConnectionInfo} to fetch the connection details. Connection information
 * can be obtained with {@link #connectionInfoInResponse} on a {@link #RESPONSE_CONNECTION_INFO}
 * message. The connection info {@link WifiP2pInfo} contains the address of the group owner
 * {@link WifiP2pInfo#groupOwnerAddress} and a flag {@link WifiP2pInfo#isGroupOwner} to indicate
 * if the current device is a p2p group owner. A p2p client can thus communicate with
 * the p2p group owner through a socket connection.
 *
 * <p> Android has no platform support for service discovery yet, so applications could
 * run a service discovery protocol to discover services on the peer-to-peer netework.
 *
 * <p class="note"><strong>Note:</strong>
 * Registering an application handler with {@link #initialize} requires the permissions
 * {@link android.Manifest.permission#ACCESS_WIFI_STATE} and
 * {@link android.Manifest.permission#CHANGE_WIFI_STATE} to perform any further peer-to-peer
 * operations.
 *
 * Get an instance of this class by calling {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.WIFI_P2P_SERVICE)}.
 *
 * {@see WifiP2pConfig}
 * {@see WifiP2pInfo}
 * {@see WifiP2pGroup}
 * {@see WifiP2pDevice}
 * {@see WifiP2pDeviceList}
 * {@see android.net.wifi.Wps}
 * @hide
 */
public class WifiP2pManager {
    /**
     * Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled. An
     * extra {@link #EXTRA_WIFI_STATE} provides the state information as int.
     *
     * @see #EXTRA_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_STATE_CHANGED_ACTION =
        "android.net.wifi.P2P_STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wi-Fi p2p is enabled or disabled.
     * Retrieve it with {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_P2P_STATE_DISABLED
     * @see #WIFI_P2P_STATE_ENABLED
     */
    public static final String EXTRA_WIFI_STATE = "wifi_p2p_state";

    /**
     * Wi-Fi p2p is disabled.
     *
     * @see #WIFI_P2P_STATE_CHANGED_ACTION
     */
    public static final int WIFI_P2P_STATE_DISABLED = 1;

    /**
     * Wi-Fi p2p is enabled.
     *
     * @see #WIFI_P2P_STATE_CHANGED_ACTION
     */
    public static final int WIFI_P2P_STATE_ENABLED = 2;

    /**
     * Broadcast intent action indicating that the state of Wi-Fi p2p connectivity
     * has changed. One extra {@link #EXTRA_WIFI_P2P_INFO} provides the p2p connection info in
     * the form of a {@link WifiP2pInfo} object. Another extra {@link #EXTRA_NETWORK_INFO} provides
     * the network info in the form of a {@link android.net.NetworkInfo}.
     *
     * @see #EXTRA_WIFI_P2P_INFO
     * @see #EXTRA_NETWORK_INFO
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_CONNECTION_CHANGED_ACTION =
        "android.net.wifi.CONNECTION_STATE_CHANGE";

    /**
     * The lookup key for a {@link android.net.wifi.p2p.WifiP2pInfo} object
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_WIFI_P2P_INFO = "wifiP2pInfo";

    /**
     * The lookup key for a {@link android.net.NetworkInfo} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_INFO = "networkInfo";

    /**
     * The lookup key for a {@link android.net.LinkProperties} object associated with the
     * network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_LINK_PROPERTIES = "linkProperties";

    /**
     * The lookup key for a {@link android.net.LinkCapabilities} object associated with the
     * network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_LINK_CAPABILITIES = "linkCapabilities";

    /**
     * Broadcast intent action indicating that the available peer list has changed. Fetch
     * the changed list of peers with {@link #requestPeers}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_PEERS_CHANGED_ACTION =
        "android.net.wifi.PEERS_CHANGED";

    /**
     * Activity Action: Pick a Wi-Fi p2p network to connect to.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK_WIFI_P2P_NETWORK =
        "android.net.wifi.PICK_WIFI_P2P_NETWORK";

    IWifiP2pManager mService;

    /**
     * Message {@link android.os.Message#what} sent on the application handler specified
     * at {@link #initialize} indicating the asynchronous channel has disconnected. An
     * application could choose to reconnect with {@link #initialize}
     */
    public static final int HANDLER_DISCONNECTION = AsyncChannel.CMD_CHANNEL_DISCONNECTED;

    private static final int BASE = Protocol.BASE_WIFI_P2P_MANAGER;

    /** @hide */
    public static final int ENABLE_P2P                              = BASE + 1;
    /** @hide */
    public static final int ENABLE_P2P_FAILED                       = BASE + 2;
    /** @hide */
    public static final int ENABLE_P2P_SUCCEEDED                    = BASE + 3;

    /** @hide */
    public static final int DISABLE_P2P                             = BASE + 4;
    /** @hide */
    public static final int DISABLE_P2P_FAILED                      = BASE + 5;
    /** @hide */
    public static final int DISABLE_P2P_SUCCEEDED                   = BASE + 6;

    /** @hide */
    public static final int DISCOVER_PEERS                          = BASE + 7;

    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #discoverPeers}
     * operation failed.
     * <p> The reason for failure could be one of {@link #P2P_UNSUPPORTED}, {@link #ERROR}
     * or {@link #BUSY}
     */
    public static final int DISCOVER_PEERS_FAILED                   = BASE + 8;
    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #discoverPeers}
     * operation succeeded.
     * <p> The application can register for {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent
     * to listen for changes in the peer list as a result of the discovery process.
     */
    public static final int DISCOVER_PEERS_SUCCEEDED                = BASE + 9;

    /** @hide */
    public static final int CONNECT                                 = BASE + 10;

    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #connect}
     * operation failed.
     * <p> The reason for failure could be one of {@link #P2P_UNSUPPORTED}, {@link #ERROR}
     * or {@link #BUSY}
     */
    public static final int CONNECT_FAILED                          = BASE + 11;
    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #connect}
     * operation succeeded.
     * <p> The application can register for {@link #WIFI_P2P_CONNECTION_CHANGED_ACTION} intent
     * to listen for connectivity change as a result of the connect operation
     */
    public static final int CONNECT_SUCCEEDED                       = BASE + 12;

    /** @hide */
    public static final int CREATE_GROUP                            = BASE + 13;

    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #createGroup}
     * operation failed.
     * <p> The reason for failure could be one of {@link #P2P_UNSUPPORTED}, {@link #ERROR}
     * or {@link #BUSY}
     */
    public static final int CREATE_GROUP_FAILED                     = BASE + 14;
    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #createGroup}
     * operation succeeded.
     * <p> The application can request the group details with {@link #requestGroupInfo}
     */
    public static final int CREATE_GROUP_SUCCEEDED                  = BASE + 15;

    /** @hide */
    public static final int REMOVE_GROUP                            = BASE + 16;
    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #removeGroup}
     * operation failed.
     * <p> The reason for failure could be one of {@link #P2P_UNSUPPORTED}, {@link #ERROR}
     * or {@link #BUSY}
     */
    public static final int REMOVE_GROUP_FAILED                     = BASE + 17;
    /**
     * Message {@link android.os.Message#what} value indicating that the {@link #removeGroup}
     * operation succeeded.
     */
    public static final int REMOVE_GROUP_SUCCEEDED                  = BASE + 18;

    /**
     * Supported {@link android.os.Message#arg1} value on the following response messages:
     * {@link #DISCOVER_PEERS_FAILED}, {@link #CONNECT_FAILED}, {@link #CREATE_GROUP_FAILED}
     * and {@link #REMOVE_GROUP_FAILED}
     *
     * <p> This indicates that the operation failed due to an internal error
     */
    public static final int ERROR               = 0;

    /**
     * Supported {@link android.os.Message#arg1} value on the following response messages:
     * {@link #DISCOVER_PEERS_FAILED}, {@link #CONNECT_FAILED}, {@link #CREATE_GROUP_FAILED}
     * and {@link #REMOVE_GROUP_FAILED}
     *
     * <p> This indicates that the operation failed because p2p is unsupported on the
     * device
     */
    public static final int P2P_UNSUPPORTED     = 1;

    /**
     * Supported {@link android.os.Message#arg1} value on the following response messages:
     * {@link #DISCOVER_PEERS_FAILED}, {@link #CONNECT_FAILED}, {@link #CREATE_GROUP_FAILED}
     * and {@link #REMOVE_GROUP_FAILED}
     *
     * <p> This indicates that the operation failed because the framework is busy and
     * unable to service the request
     */
    public static final int BUSY                = 2;

    /** @hide */
    public static final int REQUEST_PEERS                           = BASE + 19;
    /**
     * Message {@link android.os.Message#what} delivered on the application hander
     * in response to a {@link #requestPeers} call from the application.
     *
     * <p> Extract a {@link WifiP2pDeviceList} object by calling {@link #peersInResponse}
     * on the message object
     */
    public static final int RESPONSE_PEERS                          = BASE + 20;

    /** @hide */
    public static final int REQUEST_CONNECTION_INFO                 = BASE + 21;

    /**
     * Message {@link android.os.Message#what} delivered on the application hander
     * in response to a {@link #requestConnectionInfo} call from the application.
     *
     * <p> Extract a {@link WifiP2pInfo} object by calling {@link #connectionInfoInResponse}
     * on the message object
     */
    public static final int RESPONSE_CONNECTION_INFO                = BASE + 22;

    /** @hide */
    public static final int REQUEST_GROUP_INFO                      = BASE + 23;

    /**
     * Message {@link android.os.Message#what} delivered on the application hander
     * in response to a {@link #requestGroupInfo} call from the application.
     *
     * <p> Extract a {@link WifiP2pGroup} object by calling {@link #groupInfoInResponse}
     * on the message object
     */

    public static final int RESPONSE_GROUP_INFO                     = BASE + 24;

    /** @hide */
    public static final int WPS_PBC                                 = BASE + 25;
    /** @hide */
    public static final int WPS_PIN                                 = BASE + 26;
    /** @hide */
    public static final int WPS_PIN_AVAILABLE                       = BASE + 27;

    /**
     * Create a new WifiP2pManager instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_P2P_SERVICE Context.WIFI_P2P_SERVICE}.
     * @param service the Binder interface
     * @param handler target for messages
     * @hide - hide this because it takes in a parameter of type IWifiP2pManager, which
     * is a system private class.
     */
    public WifiP2pManager(IWifiP2pManager service) {
        mService = service;
    }

    /**
     * A channel that connects the application handler to the Wifi framework.
     * Most p2p operations require a Channel as an argument. An instance of Channel is obtained
     * by doing a call on {@link #initialize}
     */
    public class Channel {
        Channel(AsyncChannel c) {
            mAsyncChannel = c;
        }
        AsyncChannel mAsyncChannel;
    }

    /**
     * Registers the application handler with the Wi-Fi framework. This function
     * must be the first to be called before any p2p operations are performed.
     *
     * <p class="note"><strong>Note:</strong>
     * The handler registered with the framework should only handle messages
     * with {@link android.os.Message#what} values defined in this file. Adding application
     * specific private {@link android.os.Message#what} types should be done on a seperate handler
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the handler on which the source will receive message responses
     * asynchronously
     * @return Channel instance that is necessary for performing any further p2p operations
     */
    public Channel initialize(Context srcContext, Handler srcHandler) {
        Messenger messenger = getMessenger();
        if (messenger == null) return null;

        AsyncChannel asyncChannel = new AsyncChannel();
        Channel c = new Channel(asyncChannel);
        if (asyncChannel.connectSync(srcContext, srcHandler, messenger)
                == AsyncChannel.STATUS_SUCCESSFUL) {
            return c;
        } else {
            return null;
        }
    }

    /**
     * Sends in a request to the system to enable p2p. This will pop up a dialog
     * to the user and upon authorization will enable p2p.
     * @hide
     */
    public void enableP2p(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(ENABLE_P2P);
    }

    /**
     * Sends in a request to the system to disable p2p. This will pop up a dialog
     * to the user and upon authorization will enable p2p.
     * @hide
     */
    public void disableP2p(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(DISABLE_P2P);
    }

    /**
     * Initiate peer discovery. A discovery process involves scanning for available Wi-Fi peers
     * for the purpose of establishing a connection.
     *
     * <p> The function call immediately returns after sending a discovery request
     * to the framework. The application handler is notified of a success or failure to initiate
     * discovery with {@link #DISCOVER_PEERS_SUCCEEDED} or {@link #DISCOVER_PEERS_FAILED}.
     *
     * <p> The discovery remains active until a connection is initiated or
     * a p2p group is formed. Register for {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent to
     * determine when the framework notifies of a change as peers are discovered.
     *
     * <p> Upon receiving a {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent, an application
     * can request for the list of peers using {@link #requestPeers} which will deliver a
     * {@link #RESPONSE_PEERS} message on the application handler. The application can then
     * extract a {@link WifiP2pDeviceList} object by calling {@link #peersInResponse}
     * on the message.
     */
    public void discoverPeers(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(DISCOVER_PEERS);
    }

    /**
     * Start a p2p connection to a device with the specified configuration.
     *
     * <p> The function call immediately returns after sending a connection request
     * to the framework. The application handler is notified of a success or failure to initiate
     * connectivity with {@link #CONNECT_SUCCEEDED} or {@link #CONNECT_FAILED}.
     *
     * <p> Register for {@link #WIFI_P2P_CONNECTION_CHANGED_ACTION} intent to
     * determine when the framework notifies of a change in connectivity.
     *
     * <p> If the current device is not part of a p2p group, a connect request initiates
     * a group negotiation with the peer.
     *
     * <p> If the current device is part of an existing p2p group or has created
     * a p2p group with {@link #createGroup}, an invitation to join the group is sent to
     * the peer device.
     *
     * @param config options as described in {@link WifiP2pConfig} class.
     */
    public void connect(Channel c, WifiP2pConfig config) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(CONNECT, config);
    }

    /**
     * Create a p2p group with the current device as the group owner. This essentially creates
     * an access point that can accept connections from legacy clients as well as other p2p
     * devices.
     * <p> For p2p operation, this would normally not be used unless the current device needs
     * to form a p2p connection with a legacy client
     *
     * <p> The function call immediately returns after sending a group creation request
     * to the framework. The application handler is notified of a success or failure to create
     * group with {@link #CREATE_GROUP_SUCCEEDED} or {@link #CREATE_GROUP_FAILED}.
     *
     * <p> Application can request for the group details with {@link #requestGroupInfo} which will
     * deliver a {@link #RESPONSE_GROUP_INFO} message on the application handler. The application
     * can then extract a {@link WifiP2pGroup} object by calling {@link #groupInfoInResponse}
     * on the message.
     */
    public void createGroup(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(CREATE_GROUP);
    }

    /**
     * Remove the current p2p group.
     *
     * <p> The function call immediately returns after sending a group removal request
     * to the framework. The application handler is notified of a success or failure to remove
     * a group with {@link #REMOVE_GROUP_SUCCEEDED} or {@link #REMOVE_GROUP_FAILED}.
     */
    public void removeGroup(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REMOVE_GROUP);
    }

    /**
     * Request the current list of peers. This returns a {@link #RESPONSE_PEERS} on the application
     * handler. The {@link #RESPONSE_PEERS} message on the handler indicates that the peer list is
     * available. Use {@link #peersInResponse} to extract {@link WifiP2pDeviceList} from the message
     */
    public void requestPeers(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REQUEST_PEERS);
    }

    /**
     * Upon receiving a {@link #RESPONSE_PEERS} on the application handler, an application
     * can extract the peer device list using this function.
     */
    public WifiP2pDeviceList peersInResponse(Message msg) {
        return (WifiP2pDeviceList) msg.obj;
    }

    /**
     * Request device connection info. This returns a {@link #RESPONSE_CONNECTION_INFO} on
     * the application handler. The {@link #RESPONSE_CONNECTION_INFO} message on the handler
     * indicates that connection info is available. Use {@link #connectionInfoInResponse} to
     * extract {@link WifiP2pInfo} from the message.
     */
    public void requestConnectionInfo(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REQUEST_CONNECTION_INFO);
    }

    /**
     * Upon receiving a {@link #RESPONSE_CONNECTION_INFO} on the application handler, an application
     * can extract the connection info using this function.
     */
    public WifiP2pInfo connectionInfoInResponse(Message msg) {
        return (WifiP2pInfo) msg.obj;
    }

    /**
     * Request p2p group info. This returns a {@link #RESPONSE_GROUP_INFO} on
     * the application handler. The {@link #RESPONSE_GROUP_INFO} message on the handler
     * indicates that group info is available. Use {@link #groupInfoInResponse} to
     * extract {@link WifiP2pGroup} from the message.
     */
    public void requestGroupInfo(Channel c) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REQUEST_GROUP_INFO);
    }

    /**
     * Upon receiving a {@link #RESPONSE_GROUP_INFO} on the application handler, an application
     * can extract the group info using this function.
     */
    public WifiP2pGroup groupInfoInResponse(Message msg) {
        return (WifiP2pGroup) msg.obj;
    }

    /**
     * Get a reference to WifiP2pService handler. This is used to establish
     * an AsyncChannel communication with WifiService
     *
     * @return Messenger pointing to the WifiP2pService handler
     * @hide
     */
    public Messenger getMessenger() {
        try {
            return mService.getMessenger();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Setup DNS connectivity on the current process to the connected Wi-Fi p2p peers
     *
     * @return -1 on failure
     * @hide
     */
    public int startPeerCommunication() {
        IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);
        try {
            return cm.startUsingNetworkFeature(ConnectivityManager.TYPE_WIFI, "p2p", new Binder());
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Tear down connectivity to the connected Wi-Fi p2p peers
     *
     * @return -1 on failure
     * @hide
     */
    public int stopPeerCommunication() {
        IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);
        try {
            return cm.stopUsingNetworkFeature(ConnectivityManager.TYPE_WIFI, "p2p");
        } catch (RemoteException e) {
            return -1;
        }
    }

}
