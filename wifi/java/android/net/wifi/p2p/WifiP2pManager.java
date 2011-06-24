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
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.WorkSource;
import android.os.Messenger;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

/**
 * This class provides the API for managing Wi-Fi p2p
 * connectivity. Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.WIFI_P2P_SERVICE)}.
 *
 * It deals with the following:
 * <ul>
 * <li>Wi-Fi peer discovery and connection setup. Allows applications to initiate a discovery to
 * find available peers and then setup a connection </li>
 * <li>Configuration and status query. Allows applications to fetch the current list
 * of available and connected peers and query connection status </li>
 * <li>Intent actions that are broadcast to track operations
 * on a p2p connection</li>
 * </ul>
 * @hide
 */
public class WifiP2pManager {
    /**
     * Broadcast intent action to indicate whether Wi-Fi p2p is enabled or disabled.
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
     * @see #getWifiP2pState()
     */
    public static final int WIFI_P2P_STATE_DISABLED = 1;

    /**
     * Wi-Fi p2p is enabled.
     *
     * @see #WIFI_P2P_STATE_CHANGED_ACTION
     * @see #getWifiP2pState()
     */
    public static final int WIFI_P2P_STATE_ENABLED = 2;

    /**
     * Broadcast intent action indicating that the state of Wi-Fi p2p connectivity
     * has changed. One extra provides the new state
     * in the form of a {@link android.net.NetworkInfo} object.
     * @see #EXTRA_NETWORK_INFO
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_CONNECTION_CHANGED_ACTION =
        "android.net.wifi.CONNECTION_STATE_CHANGE";

    /**
     * The lookup key for a {@link android.net.NetworkInfo} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_INFO = "networkInfo";

    /**
     * Broadcast intent action indicating that the available peer list has changed
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_PEERS_CHANGED_ACTION =
        "android.net.wifi.PEERS_CHANGED";

    /**
     * Activity Action: Pick a Wi-Fi p2p network to connect to.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK_WIFI_P2P_NETWORK =
        "android.net.wifi.PICK_WIFI_P2P_NETWORK";

    IWifiP2pManager mService;

    /* For communication with WifiP2pService */
    private AsyncChannel mAsyncChannel = new AsyncChannel();

    /* AsyncChannel notifications to apps */
    public static final int HANDLER_CONNECTION = AsyncChannel.CMD_CHANNEL_HALF_CONNECTED;
    public static final int HANDLER_DISCONNECTION = AsyncChannel.CMD_CHANNEL_DISCONNECTED;

    private static final int BASE = Protocol.BASE_WIFI_P2P_MANAGER;

    public static final int ENABLE_P2P                              = BASE + 1;
    public static final int ENABLE_P2P_FAILED                       = BASE + 2;
    public static final int ENABLE_P2P_SUCCEEDED                    = BASE + 3;

    /* arg1 on ENABLE_P2P_FAILED indicates a reason for failure */
    public static final int P2P_UNSUPPORTED     = 1;

    public static final int DISABLE_P2P                             = BASE + 5;
    public static final int DISABLE_P2P_FAILED                      = BASE + 6;
    public static final int DISABLE_P2P_SUCCEEDED                   = BASE + 7;

    public static final int START_LISTEN_MODE                       = BASE + 9;
    public static final int START_LISTEN_FAILED                     = BASE + 10;
    public static final int START_LISTEN_SUCCEEDED                  = BASE + 11;

    public static final int DISCOVER_PEERS                          = BASE + 13;
    public static final int DISCOVER_PEERS_FAILED                   = BASE + 14;
    public static final int DISCOVER_PEERS_SUCCEDED                 = BASE + 15;

    public static final int CANCEL_DISCOVER_PEERS                   = BASE + 17;
    public static final int CANCEL_DISCOVER_PEERS_FAILED            = BASE + 18;
    public static final int CANCEL_DISCOVER_PEERS_SUCCEDED          = BASE + 19;

    public static final int CONNECT                                 = BASE + 21;
    public static final int CONNECT_FAILED                          = BASE + 22;
    public static final int CONNECT_SUCCEEDED                       = BASE + 23;

    public static final int CANCEL_CONNECT                          = BASE + 25;
    public static final int CANCEL_CONNECT_FAILED                   = BASE + 26;
    public static final int CANCEL_CONNECT_SUCCEDED                 = BASE + 27;

    public static final int REJECT                                  = BASE + 28;
    public static final int REJECT_FAILED                           = BASE + 29;
    public static final int REJECT_SUCCEEDED                        = BASE + 30;

    public static final int CREATE_GROUP                            = BASE + 31;
    public static final int CREATE_GROUP_FAILED                     = BASE + 32;
    public static final int CREATE_GROUP_SUCCEEDED                  = BASE + 33;

    public static final int REMOVE_GROUP                            = BASE + 34;
    public static final int REMOVE_GROUP_FAILED                     = BASE + 35;
    public static final int REMOVE_GROUP_SUCCEEDED                  = BASE + 36;

    public static final int REQUEST_SETTINGS                        = BASE + 37;
    public static final int RESPONSE_SETTINGS                       = BASE + 38;

    public static final int REQUEST_PEERS                           = BASE + 39;
    public static final int RESPONSE_PEERS                          = BASE + 40;

    public static final int REQUEST_CONNECTION_STATUS               = BASE + 41;
    public static final int RESPONSE_CONNECTION_STATUS              = BASE + 42;

    public static final int WPS_PBC                                 = BASE + 43;
    public static final int WPS_PIN                                 = BASE + 44;
    public static final int WPS_PIN_AVAILABLE                       = BASE + 45;

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
     * Registers the application handler with the Wi-Fi framework.
     * This function must be the first to be called before any p2p control
     * or query operations can be performed.
     * @param srcContext is the context of the source
     * @param srcHandler is the handler on which the source receives messages
     * @return {@code true} if the operation succeeded
     */
    public boolean connectHandler(Context srcContext, Handler srcHandler) {
        Messenger messenger = getMessenger();
        if (messenger == null) return false;
        return mAsyncChannel.connectSync(srcContext, srcHandler, messenger)
                == AsyncChannel.STATUS_SUCCESSFUL;
    }

    public boolean isP2pSupported() {
        try {
            return mService.isP2pSupported();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sends in a request to the system to enable p2p. This will pop up a dialog
     * to the user and upon authorization will enable p2p.
     */
    public void enableP2p() {
        mAsyncChannel.sendMessage(ENABLE_P2P);
    }

    /**
     * Sends in a request to the system to disable p2p. This will pop up a dialog
     * to the user and upon authorization will enable p2p.
     */
    public void disableP2p() {
        mAsyncChannel.sendMessage(DISABLE_P2P);
    }

    /**
     * Set device in listen mode. This will make the device discoverable by
     * another peer.
     * A dialog to the user is thrown to request his permission since it can
     * have a significant impact on power consumption
     */
     public void setListenState(int timeout) {
        mAsyncChannel.sendMessage(START_LISTEN_MODE, timeout);
     }

    /**
     * Initiates peer discovery
     */
    public void discoverPeers() {
        mAsyncChannel.sendMessage(DISCOVER_PEERS);
    }

    /**
     * Initiates peer discovery with a timeout
     */
    public void discoverPeers(int timeout) {
        mAsyncChannel.sendMessage(DISCOVER_PEERS, timeout);
    }

    /**
     * Cancel any existing peer discovery operation
     */
    public void cancelPeerDiscovery() {
        mAsyncChannel.sendMessage(CANCEL_DISCOVER_PEERS);
    }

    /**
     * Start a p2p connection
     *
     * @param peer Configuration described in a {@link WifiP2pConfig} object.
     */
    public void connect(WifiP2pConfig config) {
        mAsyncChannel.sendMessage(CONNECT, config);
    }

    /**
     * Cancel any ongoing negotiation or disconnect from an existing group
     */
    public void disconnect() {
        mAsyncChannel.sendMessage(CANCEL_CONNECT);
    }

    /**
     * Create a p2p group. This is essentially an access point that can accept
     * client connections.
     */
    public void createGroup() {
        mAsyncChannel.sendMessage(CREATE_GROUP);
    }

    /**
     * Remove the current group. This also removes the p2p interface created
     * during group formation.
     */
    public void removeGroup() {
        mAsyncChannel.sendMessage(REMOVE_GROUP);
    }

    /**
     * Request current p2p settings. This returns a RESPONSE_SETTINGS on the source
     * handler.
     */
    public void requestP2pSettings() {
        mAsyncChannel.sendMessage(REQUEST_SETTINGS);
    }

    /**
     * Request the list of peers. This returns a RESPONSE_PEERS on the source
     * handler.
     */
    public void requestPeers() {
        mAsyncChannel.sendMessage(REQUEST_PEERS);
    }

    /**
     * Fetch device list from a RESPONSE_PEERS message
     */
    public WifiP2pDeviceList peersInResponse(Message msg) {
        return (WifiP2pDeviceList) msg.obj;
    }

    /**
     * Request device connection status. This returns a RESPONSE_CONNECTION_STATUS on
     * the source handler.
     */
    public void requestConnectionStatus() {
        mAsyncChannel.sendMessage(REQUEST_CONNECTION_STATUS);
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
