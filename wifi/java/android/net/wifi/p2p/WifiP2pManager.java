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
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.HashMap;

/**
 * This class provides the API for managing Wi-Fi peer-to-peer connectivity. This lets an
 * application discover available peers, setup connection to peers and query for the list of peers.
 * When a p2p connection is formed over wifi, the device continues to maintain the uplink
 * connection over mobile or any other available network for internet connectivity on the device.
 *
 * <p> The API is asynchronous and responses to requests from an application are on listener
 * callbacks provided by the application. The application needs to do an initialization with
 * {@link #initialize} before doing any p2p operation.
 *
 * <p> Application actions {@link #discoverPeers}, {@link #connect}, {@link #cancelConnect},
 * {@link #createGroup} and {@link #removeGroup} need a {@link ActionListener} instance for
 * receiving callbacks {@link ActionListener#onSuccess} or {@link ActionListener#onFailure}.
 * Action callbacks indicate whether the initiation of the action was a success or a failure.
 * Upon failure, the reason of failure can be one of {@link #ERROR}, {@link #P2P_UNSUPPORTED}
 * or {@link #BUSY}.
 *
 * <p> An application can initiate discovery of peers with {@link #discoverPeers}. An initiated
 * discovery request from an application stays active until the device starts connecting to a peer
 * or forms a p2p group. The {@link ActionListener} callbacks provide feedback on whether the
 * discovery initiation was successful or failure. Additionally, applications can listen
 * to {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent action to know when the peer list changes.
 *
 * <p> When the peer list change intent {@link #WIFI_P2P_PEERS_CHANGED_ACTION} is received
 * or when an application needs to fetch the current list of peers, it can request the list
 * of peers with {@link #requestPeers}. When the peer list is available
 * {@link PeerListListener#onPeersAvailable} is called with the device list.
 *
 * <p> An application can initiate a connection request to a peer through {@link #connect}. See
 * {@link WifiP2pConfig} for details on setting up the configuration. For communication with legacy
 * Wi-Fi devices that do not support p2p, an app can create a group using {@link #createGroup}
 * which creates an access point whose details can be fetched with {@link #requestGroupInfo}.
*
 * <p> After a successful group formation through {@link #createGroup} or through {@link #connect},
 * use {@link #requestConnectionInfo} to fetch the connection details. The connection info
 * {@link WifiP2pInfo} contains the address of the group owner
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
 * {@see android.net.wifi.WpsInfo}
 */
public class WifiP2pManager {
    private static final String TAG = "WifiP2pManager";
    /**
     * Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled. An
     * extra {@link #EXTRA_WIFI_STATE} provides the state information as int.
     *
     * @see #EXTRA_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_STATE_CHANGED_ACTION =
        "android.net.wifi.p2p.STATE_CHANGED";

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
        "android.net.wifi.p2p.CONNECTION_STATE_CHANGE";

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
        "android.net.wifi.p2p.PEERS_CHANGED";

    /**
     * Broadcast intent action indicating that this device details have changed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_THIS_DEVICE_CHANGED_ACTION =
        "android.net.wifi.p2p.THIS_DEVICE_CHANGED";

    /**
     * The lookup key for a {@link android.net.wifi.p2p.WifiP2pDevice} object
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_WIFI_P2P_DEVICE = "wifiP2pDevice";

    IWifiP2pManager mService;

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
    /** @hide */
    public static final int DISCOVER_PEERS_FAILED                   = BASE + 8;
    /** @hide */
    public static final int DISCOVER_PEERS_SUCCEEDED                = BASE + 9;

    /** @hide */
    public static final int CONNECT                                 = BASE + 10;
    /** @hide */
    public static final int CONNECT_FAILED                          = BASE + 11;
    /** @hide */
    public static final int CONNECT_SUCCEEDED                       = BASE + 12;

    /** @hide */
    public static final int CANCEL_CONNECT                          = BASE + 13;
    /** @hide */
    public static final int CANCEL_CONNECT_FAILED                   = BASE + 14;
    /** @hide */
    public static final int CANCEL_CONNECT_SUCCEEDED                = BASE + 15;

    /** @hide */
    public static final int CREATE_GROUP                            = BASE + 16;
    /** @hide */
    public static final int CREATE_GROUP_FAILED                     = BASE + 17;
    /** @hide */
    public static final int CREATE_GROUP_SUCCEEDED                  = BASE + 18;

    /** @hide */
    public static final int REMOVE_GROUP                            = BASE + 19;
    /** @hide */
    public static final int REMOVE_GROUP_FAILED                     = BASE + 20;
    /** @hide */
    public static final int REMOVE_GROUP_SUCCEEDED                  = BASE + 21;

    /** @hide */
    public static final int REQUEST_PEERS                           = BASE + 22;
    /** @hide */
    public static final int RESPONSE_PEERS                          = BASE + 23;

    /** @hide */
    public static final int REQUEST_CONNECTION_INFO                 = BASE + 24;
    /** @hide */
    public static final int RESPONSE_CONNECTION_INFO                = BASE + 25;

    /** @hide */
    public static final int REQUEST_GROUP_INFO                      = BASE + 26;
    /** @hide */
    public static final int RESPONSE_GROUP_INFO                     = BASE + 27;

    /**
     * Create a new WifiP2pManager instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_P2P_SERVICE Context.WIFI_P2P_SERVICE}.
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type IWifiP2pManager, which
     * is a system private class.
     */
    public WifiP2pManager(IWifiP2pManager service) {
        mService = service;
    }

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to an internal error.
     */
    public static final int ERROR               = 0;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed because p2p is unsupported on the device.
     */
    public static final int P2P_UNSUPPORTED     = 1;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed because the framework is busy and
     * unable to service the request
     */
    public static final int BUSY                = 2;

    /** Interface for callback invocation when framework channel is lost */
    public interface ChannelListener {
        /**
         * The channel to the framework has been disconnected.
         * Application could try re-initializing using {@link #initialize}
         */
        public void onChannelDisconnected();
    }

    /** Interface for callback invocation on an application action */
    public interface ActionListener {
        /** The operation succeeded */
        public void onSuccess();
        /**
         * The operation failed
         * @param reason The reason for failure could be one of {@link #P2P_UNSUPPORTED},
         * {@link #ERROR} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    /** Interface for callback invocation when peer list is available */
    public interface PeerListListener {
        /**
         * The requested peer list is available
         * @param peers List of available peers
         */
        public void onPeersAvailable(WifiP2pDeviceList peers);
    }

    /** Interface for callback invocation when connection info is available */
    public interface ConnectionInfoListener {
        /**
         * The requested connection info is available
         * @param info Wi-Fi p2p connection info
         */
        public void onConnectionInfoAvailable(WifiP2pInfo info);
    }

    /** Interface for callback invocation when group info is available */
    public interface GroupInfoListener {
        /**
         * The requested p2p group info is available
         * @param group Wi-Fi p2p group info
         */
        public void onGroupInfoAvailable(WifiP2pGroup group);
    }

    /**
     * A channel that connects the application to the Wifi p2p framework.
     * Most p2p operations require a Channel as an argument. An instance of Channel is obtained
     * by doing a call on {@link #initialize}
     */
    public static class Channel {
        Channel(Looper looper, ChannelListener l) {
            mAsyncChannel = new AsyncChannel();
            mHandler = new P2pHandler(looper);
            mChannelListener = l;
        }
        private ChannelListener mChannelListener;
        private HashMap<Integer, Object> mListenerMap = new HashMap<Integer, Object>();
        private Object mListenerMapLock = new Object();
        private int mListenerKey = 0;

        AsyncChannel mAsyncChannel;
        P2pHandler mHandler;
        class P2pHandler extends Handler {
            P2pHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message message) {
                Object listener = getListener(message.arg2);
                switch (message.what) {
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (mChannelListener != null) {
                            mChannelListener.onChannelDisconnected();
                            mChannelListener = null;
                        }
                        break;
                    /* ActionListeners grouped together */
                    case WifiP2pManager.DISCOVER_PEERS_FAILED:
                    case WifiP2pManager.CONNECT_FAILED:
                    case WifiP2pManager.CANCEL_CONNECT_FAILED:
                    case WifiP2pManager.CREATE_GROUP_FAILED:
                    case WifiP2pManager.REMOVE_GROUP_FAILED:
                        if (listener != null) {
                            ((ActionListener) listener).onFailure(message.arg1);
                        }
                        break;
                    /* ActionListeners grouped together */
                    case WifiP2pManager.DISCOVER_PEERS_SUCCEEDED:
                    case WifiP2pManager.CONNECT_SUCCEEDED:
                    case WifiP2pManager.CANCEL_CONNECT_SUCCEEDED:
                    case WifiP2pManager.CREATE_GROUP_SUCCEEDED:
                    case WifiP2pManager.REMOVE_GROUP_SUCCEEDED:
                        if (listener != null) {
                            ((ActionListener) listener).onSuccess();
                        }
                        break;
                    case WifiP2pManager.RESPONSE_PEERS:
                        WifiP2pDeviceList peers = (WifiP2pDeviceList) message.obj;
                        if (listener != null) {
                            ((PeerListListener) listener).onPeersAvailable(peers);
                        }
                        break;
                    case WifiP2pManager.RESPONSE_CONNECTION_INFO:
                        WifiP2pInfo wifiP2pInfo = (WifiP2pInfo) message.obj;
                        if (listener != null) {
                            ((ConnectionInfoListener) listener).onConnectionInfoAvailable(wifiP2pInfo);
                        }
                        break;
                    case WifiP2pManager.RESPONSE_GROUP_INFO:
                        WifiP2pGroup group = (WifiP2pGroup) message.obj;
                        if (listener != null) {
                            ((GroupInfoListener) listener).onGroupInfoAvailable(group);
                        }
                        break;
                   default:
                        Log.d(TAG, "Ignored " + message);
                        break;
                }
            }
        }

        int putListener(Object listener) {
            if (listener == null) return 0;
            int key;
            synchronized (mListenerMapLock) {
                key = mListenerKey++;
                mListenerMap.put(key, listener);
            }
            return key;
        }

        Object getListener(int key) {
            synchronized (mListenerMapLock) {
                return mListenerMap.remove(key);
            }
        }
    }

    /**
     * Registers the application with the Wi-Fi framework. This function
     * must be the first to be called before any p2p operations are performed.
     *
     * @param srcContext is the context of the source
     * @param srcLooper is the Looper on which the callbacks are receivied
     * @param listener for callback at loss of framework communication. Can be null.
     * @return Channel instance that is necessary for performing any further p2p operations
     */
    public Channel initialize(Context srcContext, Looper srcLooper, ChannelListener listener) {
        Messenger messenger = getMessenger();
        if (messenger == null) return null;

        Channel c = new Channel(srcLooper, listener);
        if (c.mAsyncChannel.connectSync(srcContext, c.mHandler, messenger)
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
     * to the framework. The application is notified of a success or failure to initiate
     * discovery through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p> The discovery remains active until a connection is initiated or
     * a p2p group is formed. Register for {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent to
     * determine when the framework notifies of a change as peers are discovered.
     *
     * <p> Upon receiving a {@link #WIFI_P2P_PEERS_CHANGED_ACTION} intent, an application
     * can request for the list of peers using {@link #requestPeers}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void discoverPeers(Channel c, ActionListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(DISCOVER_PEERS, 0, c.putListener(listener));
    }

    /**
     * Start a p2p connection to a device with the specified configuration.
     *
     * <p> The function call immediately returns after sending a connection request
     * to the framework. The application is notified of a success or failure to initiate
     * connect through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
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
     * @param c is the channel created at {@link #initialize}
     * @param config options as described in {@link WifiP2pConfig} class
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void connect(Channel c, WifiP2pConfig config, ActionListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(CONNECT, 0, c.putListener(listener), config);
    }

    /**
     * Cancel any ongoing p2p group negotiation
     *
     * <p> The function call immediately returns after sending a connection cancellation request
     * to the framework. The application is notified of a success or failure to initiate
     * cancellation through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void cancelConnect(Channel c, ActionListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(CANCEL_CONNECT, 0, c.putListener(listener));
    }

    /**
     * Create a p2p group with the current device as the group owner. This essentially creates
     * an access point that can accept connections from legacy clients as well as other p2p
     * devices.
     *
     * <p class="note"><strong>Note:</strong>
     * This function would normally not be used unless the current device needs
     * to form a p2p connection with a legacy client
     *
     * <p> The function call immediately returns after sending a group creation request
     * to the framework. The application is notified of a success or failure to initiate
     * group creation through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p> Application can request for the group details with {@link #requestGroupInfo}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void createGroup(Channel c, ActionListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(CREATE_GROUP, 0, c.putListener(listener));
    }

    /**
     * Remove the current p2p group.
     *
     * <p> The function call immediately returns after sending a group removal request
     * to the framework. The application is notified of a success or failure to initiate
     * group removal through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void removeGroup(Channel c, ActionListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REMOVE_GROUP, 0, c.putListener(listener));
    }

    /**
     * Request the current list of peers.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when peer list is available. Can be null.
     */
    public void requestPeers(Channel c, PeerListListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REQUEST_PEERS, 0, c.putListener(listener));
    }

    /**
     * Request device connection info.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when connection info is available. Can be null.
     */
    public void requestConnectionInfo(Channel c, ConnectionInfoListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REQUEST_CONNECTION_INFO, 0, c.putListener(listener));
    }

    /**
     * Request p2p group info.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when group info is available. Can be null.
     */
    public void requestGroupInfo(Channel c, GroupInfoListener listener) {
        if (c == null) return;
        c.mAsyncChannel.sendMessage(REQUEST_GROUP_INFO, 0, c.putListener(listener));
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

}
