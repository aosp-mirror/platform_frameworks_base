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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceResponse;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceResponse;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.CloseGuard;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p> Most application calls need a {@link ActionListener} instance for receiving callbacks
 * {@link ActionListener#onSuccess} or {@link ActionListener#onFailure}. Action callbacks
 * indicate whether the initiation of the action was a success or a failure.
 * Upon failure, the reason of failure can be one of {@link #ERROR}, {@link #P2P_UNSUPPORTED}
 * or {@link #BUSY}.
 *
 * <p> An application can initiate discovery of peers with {@link #discoverPeers}. An initiated
 * discovery request from an application stays active until the device starts connecting to a peer
 * ,forms a p2p group or there is an explicit {@link #stopPeerDiscovery}.
 * Applications can listen to {@link #WIFI_P2P_DISCOVERY_CHANGED_ACTION} to know if a peer-to-peer
 * discovery is running or stopped. Additionally, {@link #WIFI_P2P_PEERS_CHANGED_ACTION} indicates
 * if the peer list has changed.
 *
 * <p> When an application needs to fetch the current list of peers, it can request the list
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
 * the p2p group owner through a socket connection. If the current device is the p2p group owner,
 * {@link WifiP2pInfo#groupOwnerAddress} is anonymized unless the caller holds the
 * {@code android.Manifest.permission#LOCAL_MAC_ADDRESS} permission.
 *
 * <p> With peer discovery using {@link  #discoverPeers}, an application discovers the neighboring
 * peers, but has no good way to figure out which peer to establish a connection with. For example,
 * if a game application is interested in finding all the neighboring peers that are also running
 * the same game, it has no way to find out until after the connection is setup. Pre-association
 * service discovery is meant to address this issue of filtering the peers based on the running
 * services.
 *
 * <p>With pre-association service discovery, an application can advertise a service for a
 * application on a peer device prior to a connection setup between the devices.
 * Currently, DNS based service discovery (Bonjour) and Upnp are the higher layer protocols
 * supported. Get Bonjour resources at dns-sd.org and Upnp resources at upnp.org
 * As an example, a video application can discover a Upnp capable media renderer
 * prior to setting up a Wi-fi p2p connection with the device.
 *
 * <p> An application can advertise a Upnp or a Bonjour service with a call to
 * {@link #addLocalService}. After a local service is added,
 * the framework automatically responds to a peer application discovering the service prior
 * to establishing a p2p connection. A call to {@link #removeLocalService} removes a local
 * service and {@link #clearLocalServices} can be used to clear all local services.
 *
 * <p> An application that is looking for peer devices that support certain services
 * can do so with a call to  {@link #discoverServices}. Prior to initiating the discovery,
 * application can add service discovery request with a call to {@link #addServiceRequest},
 * remove a service discovery request with a call to {@link #removeServiceRequest} or clear
 * all requests with a call to {@link #clearServiceRequests}. When no service requests remain,
 * a previously running service discovery will stop.
 *
 * The application is notified of a result of service discovery request through listener callbacks
 * set through {@link #setDnsSdResponseListeners} for Bonjour or
 * {@link #setUpnpServiceResponseListener} for Upnp.
 *
 * <p class="note"><strong>Note:</strong>
 * Registering an application handler with {@link #initialize} requires the permissions
 * {@link android.Manifest.permission#ACCESS_WIFI_STATE} and
 * {@link android.Manifest.permission#CHANGE_WIFI_STATE} to perform any further peer-to-peer
 * operations.
 *
 * {@see WifiP2pConfig}
 * {@see WifiP2pInfo}
 * {@see WifiP2pGroup}
 * {@see WifiP2pDevice}
 * {@see WifiP2pDeviceList}
 * {@see android.net.wifi.WpsInfo}
 */
@SystemService(Context.WIFI_P2P_SERVICE)
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

    /** @hide */
    @IntDef({
            WIFI_P2P_STATE_DISABLED,
            WIFI_P2P_STATE_ENABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface WifiP2pState {
    }

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
     * the network info in the form of a {@link android.net.NetworkInfo}. A third extra provides
     * the details of the group and may contain a {@code null}.
     *
     * All of these permissions are required to receive this broadcast:
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} and
     * {@link android.Manifest.permission#ACCESS_WIFI_STATE}
     *
     * @see #EXTRA_WIFI_P2P_INFO
     * @see #EXTRA_NETWORK_INFO
     * @see #EXTRA_WIFI_P2P_GROUP
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
     * p2p network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_INFO = "networkInfo";

    /**
     * The lookup key for a {@link android.net.wifi.p2p.WifiP2pGroup} object
     * associated with the p2p network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_WIFI_P2P_GROUP = "p2pGroupInfo";

    /**
     * Broadcast intent action indicating that the available peer list has changed. This
     * can be sent as a result of peers being found, lost or updated.
     *
     * All of these permissions are required to receive this broadcast:
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} and
     * {@link android.Manifest.permission#ACCESS_WIFI_STATE}
     *
     * <p> An extra {@link #EXTRA_P2P_DEVICE_LIST} provides the full list of
     * current peers. The full list of peers can also be obtained any time with
     * {@link #requestPeers}.
     *
     * @see #EXTRA_P2P_DEVICE_LIST
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_PEERS_CHANGED_ACTION =
        "android.net.wifi.p2p.PEERS_CHANGED";

     /**
      * The lookup key for a {@link android.net.wifi.p2p.WifiP2pDeviceList} object representing
      * the new peer list when {@link #WIFI_P2P_PEERS_CHANGED_ACTION} broadcast is sent.
      *
      * <p>Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
      */
    public static final String EXTRA_P2P_DEVICE_LIST = "wifiP2pDeviceList";

    /**
     * Broadcast intent action indicating that peer discovery has either started or stopped.
     * One extra {@link #EXTRA_DISCOVERY_STATE} indicates whether discovery has started
     * or stopped.
     *
     * <p>Note that discovery will be stopped during a connection setup. If the application tries
     * to re-initiate discovery during this time, it can fail.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_DISCOVERY_CHANGED_ACTION =
        "android.net.wifi.p2p.DISCOVERY_STATE_CHANGE";

    /**
     * The lookup key for an int that indicates whether p2p discovery has started or stopped.
     * Retrieve it with {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_P2P_DISCOVERY_STARTED
     * @see #WIFI_P2P_DISCOVERY_STOPPED
     */
    public static final String EXTRA_DISCOVERY_STATE = "discoveryState";

    /** @hide */
    @IntDef({
            WIFI_P2P_DISCOVERY_STOPPED,
            WIFI_P2P_DISCOVERY_STARTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface WifiP2pDiscoveryState {
    }

    /**
     * p2p discovery has stopped
     *
     * @see #WIFI_P2P_DISCOVERY_CHANGED_ACTION
     */
    public static final int WIFI_P2P_DISCOVERY_STOPPED = 1;

    /**
     * p2p discovery has started
     *
     * @see #WIFI_P2P_DISCOVERY_CHANGED_ACTION
     */
    public static final int WIFI_P2P_DISCOVERY_STARTED = 2;

    /**
     * Broadcast intent action indicating that this device details have changed.
     *
     * <p> An extra {@link #EXTRA_WIFI_P2P_DEVICE} provides this device details.
     * The valid device details can also be obtained with
     * {@link #requestDeviceInfo(Channel, DeviceInfoListener)} when p2p is enabled.
     * To get information notifications on P2P getting enabled refers
     * {@link #WIFI_P2P_STATE_ENABLED}.
     *
     * <p> The {@link #EXTRA_WIFI_P2P_DEVICE} extra contains an anonymized version of the device's
     * MAC address. Callers holding the {@code android.Manifest.permission#LOCAL_MAC_ADDRESS}
     * permission can use {@link #requestDeviceInfo} to obtain the actual MAC address of this
     * device.
     *
     * All of these permissions are required to receive this broadcast:
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} and
     * {@link android.Manifest.permission#ACCESS_WIFI_STATE}
     *
     * @see #EXTRA_WIFI_P2P_DEVICE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_P2P_THIS_DEVICE_CHANGED_ACTION =
        "android.net.wifi.p2p.THIS_DEVICE_CHANGED";

    /**
     * The lookup key for a {@link android.net.wifi.p2p.WifiP2pDevice} object
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_WIFI_P2P_DEVICE = "wifiP2pDevice";

    /**
     * Broadcast intent action indicating that remembered persistent groups have changed.
     *
     * You can <em>not</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver,
     * android.content.IntentFilter) Context.registerReceiver()}.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_WIFI_P2P_PERSISTENT_GROUPS_CHANGED =
            "android.net.wifi.p2p.action.WIFI_P2P_PERSISTENT_GROUPS_CHANGED";

    /**
     * The lookup key for a handover message returned by the WifiP2pService.
     * @hide
     */
    public static final String EXTRA_HANDOVER_MESSAGE =
            "android.net.wifi.p2p.EXTRA_HANDOVER_MESSAGE";

    /**
     * The lookup key for a calling package name from WifiP2pManager
     * @hide
     */
    public static final String CALLING_PACKAGE =
            "android.net.wifi.p2p.CALLING_PACKAGE";

    /**
     * The lookup key for a calling feature id from WifiP2pManager
     * @hide
     */
    public static final String CALLING_FEATURE_ID =
            "android.net.wifi.p2p.CALLING_FEATURE_ID";

    /**
     * The lookup key for a calling package binder from WifiP2pManager
     * @hide
     */
    public static final String CALLING_BINDER =
            "android.net.wifi.p2p.CALLING_BINDER";

    IWifiP2pManager mService;

    private static final int BASE = Protocol.BASE_WIFI_P2P_MANAGER;

    /** @hide */
    public static final int DISCOVER_PEERS                          = BASE + 1;
    /** @hide */
    public static final int DISCOVER_PEERS_FAILED                   = BASE + 2;
    /** @hide */
    public static final int DISCOVER_PEERS_SUCCEEDED                = BASE + 3;

    /** @hide */
    public static final int STOP_DISCOVERY                          = BASE + 4;
    /** @hide */
    public static final int STOP_DISCOVERY_FAILED                   = BASE + 5;
    /** @hide */
    public static final int STOP_DISCOVERY_SUCCEEDED                = BASE + 6;

    /** @hide */
    public static final int CONNECT                                 = BASE + 7;
    /** @hide */
    public static final int CONNECT_FAILED                          = BASE + 8;
    /** @hide */
    public static final int CONNECT_SUCCEEDED                       = BASE + 9;

    /** @hide */
    public static final int CANCEL_CONNECT                          = BASE + 10;
    /** @hide */
    public static final int CANCEL_CONNECT_FAILED                   = BASE + 11;
    /** @hide */
    public static final int CANCEL_CONNECT_SUCCEEDED                = BASE + 12;

    /** @hide */
    @UnsupportedAppUsage
    public static final int CREATE_GROUP                            = BASE + 13;
    /** @hide */
    public static final int CREATE_GROUP_FAILED                     = BASE + 14;
    /** @hide */
    public static final int CREATE_GROUP_SUCCEEDED                  = BASE + 15;

    /** @hide */
    public static final int REMOVE_GROUP                            = BASE + 16;
    /** @hide */
    public static final int REMOVE_GROUP_FAILED                     = BASE + 17;
    /** @hide */
    public static final int REMOVE_GROUP_SUCCEEDED                  = BASE + 18;

    /** @hide */
    public static final int REQUEST_PEERS                           = BASE + 19;
    /** @hide */
    public static final int RESPONSE_PEERS                          = BASE + 20;

    /** @hide */
    public static final int REQUEST_CONNECTION_INFO                 = BASE + 21;
    /** @hide */
    public static final int RESPONSE_CONNECTION_INFO                = BASE + 22;

    /** @hide */
    public static final int REQUEST_GROUP_INFO                      = BASE + 23;
    /** @hide */
    public static final int RESPONSE_GROUP_INFO                     = BASE + 24;

    /** @hide */
    public static final int ADD_LOCAL_SERVICE                       = BASE + 28;
    /** @hide */
    public static final int ADD_LOCAL_SERVICE_FAILED                = BASE + 29;
    /** @hide */
    public static final int ADD_LOCAL_SERVICE_SUCCEEDED             = BASE + 30;

    /** @hide */
    public static final int REMOVE_LOCAL_SERVICE                    = BASE + 31;
    /** @hide */
    public static final int REMOVE_LOCAL_SERVICE_FAILED             = BASE + 32;
    /** @hide */
    public static final int REMOVE_LOCAL_SERVICE_SUCCEEDED          = BASE + 33;

    /** @hide */
    public static final int CLEAR_LOCAL_SERVICES                    = BASE + 34;
    /** @hide */
    public static final int CLEAR_LOCAL_SERVICES_FAILED             = BASE + 35;
    /** @hide */
    public static final int CLEAR_LOCAL_SERVICES_SUCCEEDED          = BASE + 36;

    /** @hide */
    public static final int ADD_SERVICE_REQUEST                     = BASE + 37;
    /** @hide */
    public static final int ADD_SERVICE_REQUEST_FAILED              = BASE + 38;
    /** @hide */
    public static final int ADD_SERVICE_REQUEST_SUCCEEDED           = BASE + 39;

    /** @hide */
    public static final int REMOVE_SERVICE_REQUEST                  = BASE + 40;
    /** @hide */
    public static final int REMOVE_SERVICE_REQUEST_FAILED           = BASE + 41;
    /** @hide */
    public static final int REMOVE_SERVICE_REQUEST_SUCCEEDED        = BASE + 42;

    /** @hide */
    public static final int CLEAR_SERVICE_REQUESTS                  = BASE + 43;
    /** @hide */
    public static final int CLEAR_SERVICE_REQUESTS_FAILED           = BASE + 44;
    /** @hide */
    public static final int CLEAR_SERVICE_REQUESTS_SUCCEEDED        = BASE + 45;

    /** @hide */
    public static final int DISCOVER_SERVICES                       = BASE + 46;
    /** @hide */
    public static final int DISCOVER_SERVICES_FAILED                = BASE + 47;
    /** @hide */
    public static final int DISCOVER_SERVICES_SUCCEEDED             = BASE + 48;

    /** @hide */
    public static final int PING                                    = BASE + 49;

    /** @hide */
    public static final int RESPONSE_SERVICE                        = BASE + 50;

    /** @hide */
    public static final int SET_DEVICE_NAME                         = BASE + 51;
    /** @hide */
    public static final int SET_DEVICE_NAME_FAILED                  = BASE + 52;
    /** @hide */
    public static final int SET_DEVICE_NAME_SUCCEEDED               = BASE + 53;

    /** @hide */
    public static final int DELETE_PERSISTENT_GROUP                 = BASE + 54;
    /** @hide */
    public static final int DELETE_PERSISTENT_GROUP_FAILED          = BASE + 55;
    /** @hide */
    public static final int DELETE_PERSISTENT_GROUP_SUCCEEDED       = BASE + 56;

    /** @hide */
    public static final int REQUEST_PERSISTENT_GROUP_INFO           = BASE + 57;
    /** @hide */
    public static final int RESPONSE_PERSISTENT_GROUP_INFO          = BASE + 58;

    /** @hide */
    public static final int SET_WFD_INFO                            = BASE + 59;
    /** @hide */
    public static final int SET_WFD_INFO_FAILED                     = BASE + 60;
    /** @hide */
    public static final int SET_WFD_INFO_SUCCEEDED                  = BASE + 61;

    /** @hide */
    public static final int START_WPS                               = BASE + 62;
    /** @hide */
    public static final int START_WPS_FAILED                        = BASE + 63;
    /** @hide */
    public static final int START_WPS_SUCCEEDED                     = BASE + 64;

    /** @hide */
    public static final int START_LISTEN                            = BASE + 65;
    /** @hide */
    public static final int START_LISTEN_FAILED                     = BASE + 66;
    /** @hide */
    public static final int START_LISTEN_SUCCEEDED                  = BASE + 67;

    /** @hide */
    public static final int STOP_LISTEN                             = BASE + 68;
    /** @hide */
    public static final int STOP_LISTEN_FAILED                      = BASE + 69;
    /** @hide */
    public static final int STOP_LISTEN_SUCCEEDED                   = BASE + 70;

    /** @hide */
    public static final int SET_CHANNEL                             = BASE + 71;
    /** @hide */
    public static final int SET_CHANNEL_FAILED                      = BASE + 72;
    /** @hide */
    public static final int SET_CHANNEL_SUCCEEDED                   = BASE + 73;

    /** @hide */
    public static final int GET_HANDOVER_REQUEST                    = BASE + 75;
    /** @hide */
    public static final int GET_HANDOVER_SELECT                     = BASE + 76;
    /** @hide */
    public static final int RESPONSE_GET_HANDOVER_MESSAGE           = BASE + 77;
    /** @hide */
    public static final int INITIATOR_REPORT_NFC_HANDOVER           = BASE + 78;
    /** @hide */
    public static final int RESPONDER_REPORT_NFC_HANDOVER           = BASE + 79;
    /** @hide */
    public static final int REPORT_NFC_HANDOVER_SUCCEEDED           = BASE + 80;
    /** @hide */
    public static final int REPORT_NFC_HANDOVER_FAILED              = BASE + 81;

    /** @hide */
    public static final int FACTORY_RESET                           = BASE + 82;
    /** @hide */
    public static final int FACTORY_RESET_FAILED                    = BASE + 83;
    /** @hide */
    public static final int FACTORY_RESET_SUCCEEDED                 = BASE + 84;

    /** @hide */
    public static final int REQUEST_ONGOING_PEER_CONFIG             = BASE + 85;
    /** @hide */
    public static final int RESPONSE_ONGOING_PEER_CONFIG            = BASE + 86;
    /** @hide */
    public static final int SET_ONGOING_PEER_CONFIG                 = BASE + 87;
    /** @hide */
    public static final int SET_ONGOING_PEER_CONFIG_FAILED          = BASE + 88;
    /** @hide */
    public static final int SET_ONGOING_PEER_CONFIG_SUCCEEDED       = BASE + 89;

    /** @hide */
    public static final int REQUEST_P2P_STATE                       = BASE + 90;
    /** @hide */
    public static final int RESPONSE_P2P_STATE                      = BASE + 91;

    /** @hide */
    public static final int REQUEST_DISCOVERY_STATE                 = BASE + 92;
    /** @hide */
    public static final int RESPONSE_DISCOVERY_STATE                = BASE + 93;

    /** @hide */
    public static final int REQUEST_NETWORK_INFO                    = BASE + 94;
    /** @hide */
    public static final int RESPONSE_NETWORK_INFO                   = BASE + 95;

    /** @hide */
    public static final int UPDATE_CHANNEL_INFO                     = BASE + 96;

    /** @hide */
    public static final int REQUEST_DEVICE_INFO                     = BASE + 97;
    /** @hide */
    public static final int RESPONSE_DEVICE_INFO                    = BASE + 98;

    /**
     * Create a new WifiP2pManager instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_P2P_SERVICE Context.WIFI_P2P_SERVICE}.
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type IWifiP2pManager, which
     * is a system private class.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the {@link #discoverServices} failed because no service
     * requests are added. Use {@link #addServiceRequest} to add a service
     * request.
     */
    public static final int NO_SERVICE_REQUESTS = 3;

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
    * Interface for callback invocation when service discovery response other than
    * Upnp or Bonjour is received
    */
    public interface ServiceResponseListener {

        /**
         * The requested service response is available.
         *
         * @param protocolType protocol type. currently only
         * {@link WifiP2pServiceInfo#SERVICE_TYPE_VENDOR_SPECIFIC}.
         * @param responseData service discovery response data based on the requested
         *  service protocol type. The format depends on the service type.
         * @param srcDevice source device.
         */
        public void onServiceAvailable(int protocolType,
                byte[] responseData, WifiP2pDevice srcDevice);
    }

    /**
     * Interface for callback invocation when Bonjour service discovery response
     * is received
     */
    public interface DnsSdServiceResponseListener {

        /**
         * The requested Bonjour service response is available.
         *
         * <p>This function is invoked when the device with the specified Bonjour
         * registration type returned the instance name.
         * @param instanceName instance name.<br>
         *  e.g) "MyPrinter".
         * @param registrationType <br>
         * e.g) "_ipp._tcp.local."
         * @param srcDevice source device.
         */
        public void onDnsSdServiceAvailable(String instanceName,
                String registrationType, WifiP2pDevice srcDevice);

   }

    /**
     * Interface for callback invocation when Bonjour TXT record is available
     * for a service
     */
   public interface DnsSdTxtRecordListener {
        /**
         * The requested Bonjour service response is available.
         *
         * <p>This function is invoked when the device with the specified full
         * service domain service returned TXT record.
         *
         * @param fullDomainName full domain name. <br>
         * e.g) "MyPrinter._ipp._tcp.local.".
         * @param txtRecordMap TXT record data as a map of key/value pairs
         * @param srcDevice source device.
         */
        public void onDnsSdTxtRecordAvailable(String fullDomainName,
                Map<String, String> txtRecordMap,
                WifiP2pDevice srcDevice);
   }

    /**
     * Interface for callback invocation when upnp service discovery response
     * is received
     * */
    public interface UpnpServiceResponseListener {

        /**
         * The requested upnp service response is available.
         *
         * <p>This function is invoked when the specified device or service is found.
         *
         * @param uniqueServiceNames The list of unique service names.<br>
         * e.g) uuid:6859dede-8574-59ab-9332-123456789012::urn:schemas-upnp-org:device:
         * MediaServer:1
         * @param srcDevice source device.
         */
        public void onUpnpServiceAvailable(List<String> uniqueServiceNames,
                WifiP2pDevice srcDevice);
    }


    /**
     * Interface for callback invocation when stored group info list is available
     *
     * @hide
     */
    @SystemApi
    public interface PersistentGroupInfoListener {
        /**
         * The requested stored p2p group info list is available
         * @param groups Wi-Fi p2p group info list
         */
        void onPersistentGroupInfoAvailable(@NonNull WifiP2pGroupList groups);
    }

    /**
     * Interface for callback invocation when Handover Request or Select Message is available
     * @hide
     */
    public interface HandoverMessageListener {
        public void onHandoverMessageAvailable(String handoverMessage);
    }

    /** Interface for callback invocation when p2p state is available
     *  in response to {@link #requestP2pState}.
     */
    public interface P2pStateListener {
        /**
         * The requested p2p state is available.
         * @param state Wi-Fi p2p state
         *        @see #WIFI_P2P_STATE_DISABLED
         *        @see #WIFI_P2P_STATE_ENABLED
         */
        void onP2pStateAvailable(@WifiP2pState int state);
    }

    /** Interface for callback invocation when p2p state is available
     *  in response to {@link #requestDiscoveryState}.
     */
    public interface DiscoveryStateListener {
        /**
         * The requested p2p discovery state is available.
         * @param state Wi-Fi p2p discovery state
         *        @see #WIFI_P2P_DISCOVERY_STARTED
         *        @see #WIFI_P2P_DISCOVERY_STOPPED
         */
        void onDiscoveryStateAvailable(@WifiP2pDiscoveryState int state);
    }

    /** Interface for callback invocation when {@link android.net.NetworkInfo} is available
     *  in response to {@link #requestNetworkInfo}.
     */
    public interface NetworkInfoListener {
        /**
         * The requested {@link android.net.NetworkInfo} is available
         * @param networkInfo Wi-Fi p2p {@link android.net.NetworkInfo}
         */
        void onNetworkInfoAvailable(@NonNull NetworkInfo networkInfo);
    }

    /**
     * Interface for callback invocation when ongoing peer info is available
     * @hide
     */
    public interface OngoingPeerInfoListener {
        /**
         * The requested ongoing WifiP2pConfig is available
         * @param peerConfig WifiP2pConfig for current connecting session
         */
        void onOngoingPeerAvailable(WifiP2pConfig peerConfig);
    }

    /** Interface for callback invocation when {@link android.net.wifi.p2p.WifiP2pDevice}
     *  is available in response to {@link #requestDeviceInfo(Channel, DeviceInfoListener)}.
     */
    public interface DeviceInfoListener {
        /**
         * The requested {@link android.net.wifi.p2p.WifiP2pDevice} is available.
         * @param wifiP2pDevice Wi-Fi p2p {@link android.net.wifi.p2p.WifiP2pDevice}
         */
        void onDeviceInfoAvailable(@Nullable WifiP2pDevice wifiP2pDevice);
    }

    /**
     * A channel that connects the application to the Wifi p2p framework.
     * Most p2p operations require a Channel as an argument. An instance of Channel is obtained
     * by doing a call on {@link #initialize}
     */
    public static class Channel implements AutoCloseable {
        /** @hide */
        public Channel(Context context, Looper looper, ChannelListener l, Binder binder,
                WifiP2pManager p2pManager) {
            mAsyncChannel = new AsyncChannel();
            mHandler = new P2pHandler(looper);
            mChannelListener = l;
            mContext = context;
            mBinder = binder;
            mP2pManager = p2pManager;

            mCloseGuard.open("close");
        }
        private final static int INVALID_LISTENER_KEY = 0;
        private final WifiP2pManager mP2pManager;
        private ChannelListener mChannelListener;
        private ServiceResponseListener mServRspListener;
        private DnsSdServiceResponseListener mDnsSdServRspListener;
        private DnsSdTxtRecordListener mDnsSdTxtListener;
        private UpnpServiceResponseListener mUpnpServRspListener;
        private HashMap<Integer, Object> mListenerMap = new HashMap<Integer, Object>();
        private final Object mListenerMapLock = new Object();
        private int mListenerKey = 0;

        private final CloseGuard mCloseGuard = new CloseGuard();

        /**
         * Close the current P2P connection and indicate to the P2P service that connections
         * created by the app can be removed.
         */
        public void close() {
            if (mP2pManager == null) {
                Log.w(TAG, "Channel.close(): Null mP2pManager!?");
            } else {
                try {
                    mP2pManager.mService.close(mBinder);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }

            mAsyncChannel.disconnect();
            mCloseGuard.close();
            Reference.reachabilityFence(this);
        }

        /** @hide */
        @Override
        protected void finalize() throws Throwable {
            try {
                if (mCloseGuard != null) {
                    mCloseGuard.warnIfOpen();
                }

                close();
            } finally {
                super.finalize();
            }
        }

        /* package */ final Binder mBinder;

        @UnsupportedAppUsage
        private AsyncChannel mAsyncChannel;
        private P2pHandler mHandler;
        Context mContext;
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
                    case DISCOVER_PEERS_FAILED:
                    case STOP_DISCOVERY_FAILED:
                    case DISCOVER_SERVICES_FAILED:
                    case CONNECT_FAILED:
                    case CANCEL_CONNECT_FAILED:
                    case CREATE_GROUP_FAILED:
                    case REMOVE_GROUP_FAILED:
                    case ADD_LOCAL_SERVICE_FAILED:
                    case REMOVE_LOCAL_SERVICE_FAILED:
                    case CLEAR_LOCAL_SERVICES_FAILED:
                    case ADD_SERVICE_REQUEST_FAILED:
                    case REMOVE_SERVICE_REQUEST_FAILED:
                    case CLEAR_SERVICE_REQUESTS_FAILED:
                    case SET_DEVICE_NAME_FAILED:
                    case DELETE_PERSISTENT_GROUP_FAILED:
                    case SET_WFD_INFO_FAILED:
                    case START_WPS_FAILED:
                    case START_LISTEN_FAILED:
                    case STOP_LISTEN_FAILED:
                    case SET_CHANNEL_FAILED:
                    case REPORT_NFC_HANDOVER_FAILED:
                    case FACTORY_RESET_FAILED:
                    case SET_ONGOING_PEER_CONFIG_FAILED:
                        if (listener != null) {
                            ((ActionListener) listener).onFailure(message.arg1);
                        }
                        break;
                    /* ActionListeners grouped together */
                    case DISCOVER_PEERS_SUCCEEDED:
                    case STOP_DISCOVERY_SUCCEEDED:
                    case DISCOVER_SERVICES_SUCCEEDED:
                    case CONNECT_SUCCEEDED:
                    case CANCEL_CONNECT_SUCCEEDED:
                    case CREATE_GROUP_SUCCEEDED:
                    case REMOVE_GROUP_SUCCEEDED:
                    case ADD_LOCAL_SERVICE_SUCCEEDED:
                    case REMOVE_LOCAL_SERVICE_SUCCEEDED:
                    case CLEAR_LOCAL_SERVICES_SUCCEEDED:
                    case ADD_SERVICE_REQUEST_SUCCEEDED:
                    case REMOVE_SERVICE_REQUEST_SUCCEEDED:
                    case CLEAR_SERVICE_REQUESTS_SUCCEEDED:
                    case SET_DEVICE_NAME_SUCCEEDED:
                    case DELETE_PERSISTENT_GROUP_SUCCEEDED:
                    case SET_WFD_INFO_SUCCEEDED:
                    case START_WPS_SUCCEEDED:
                    case START_LISTEN_SUCCEEDED:
                    case STOP_LISTEN_SUCCEEDED:
                    case SET_CHANNEL_SUCCEEDED:
                    case REPORT_NFC_HANDOVER_SUCCEEDED:
                    case FACTORY_RESET_SUCCEEDED:
                    case SET_ONGOING_PEER_CONFIG_SUCCEEDED:
                        if (listener != null) {
                            ((ActionListener) listener).onSuccess();
                        }
                        break;
                    case RESPONSE_PEERS:
                        WifiP2pDeviceList peers = (WifiP2pDeviceList) message.obj;
                        if (listener != null) {
                            ((PeerListListener) listener).onPeersAvailable(peers);
                        }
                        break;
                    case RESPONSE_CONNECTION_INFO:
                        WifiP2pInfo wifiP2pInfo = (WifiP2pInfo) message.obj;
                        if (listener != null) {
                            ((ConnectionInfoListener) listener).onConnectionInfoAvailable(wifiP2pInfo);
                        }
                        break;
                    case RESPONSE_GROUP_INFO:
                        WifiP2pGroup group = (WifiP2pGroup) message.obj;
                        if (listener != null) {
                            ((GroupInfoListener) listener).onGroupInfoAvailable(group);
                        }
                        break;
                    case RESPONSE_SERVICE:
                        WifiP2pServiceResponse resp = (WifiP2pServiceResponse) message.obj;
                        handleServiceResponse(resp);
                        break;
                    case RESPONSE_PERSISTENT_GROUP_INFO:
                        WifiP2pGroupList groups = (WifiP2pGroupList) message.obj;
                        if (listener != null) {
                            ((PersistentGroupInfoListener) listener).
                                onPersistentGroupInfoAvailable(groups);
                        }
                        break;
                    case RESPONSE_GET_HANDOVER_MESSAGE:
                        Bundle handoverBundle = (Bundle) message.obj;
                        if (listener != null) {
                            String handoverMessage = handoverBundle != null
                                    ? handoverBundle.getString(EXTRA_HANDOVER_MESSAGE)
                                    : null;
                            ((HandoverMessageListener) listener)
                                    .onHandoverMessageAvailable(handoverMessage);
                        }
                        break;
                    case RESPONSE_ONGOING_PEER_CONFIG:
                        WifiP2pConfig peerConfig = (WifiP2pConfig) message.obj;
                        if (listener != null) {
                            ((OngoingPeerInfoListener) listener)
                                    .onOngoingPeerAvailable(peerConfig);
                        }
                        break;
                    case RESPONSE_P2P_STATE:
                        if (listener != null) {
                            ((P2pStateListener) listener)
                                    .onP2pStateAvailable(message.arg1);
                        }
                        break;
                    case RESPONSE_DISCOVERY_STATE:
                        if (listener != null) {
                            ((DiscoveryStateListener) listener)
                                    .onDiscoveryStateAvailable(message.arg1);
                        }
                        break;
                    case RESPONSE_NETWORK_INFO:
                        if (listener != null) {
                            ((NetworkInfoListener) listener)
                                    .onNetworkInfoAvailable((NetworkInfo) message.obj);
                        }
                        break;
                    case RESPONSE_DEVICE_INFO:
                        if (listener != null) {
                            ((DeviceInfoListener) listener)
                                    .onDeviceInfoAvailable((WifiP2pDevice) message.obj);
                        }
                        break;
                    default:
                        Log.d(TAG, "Ignored " + message);
                        break;
                }
            }
        }

        private void handleServiceResponse(WifiP2pServiceResponse resp) {
            if (resp instanceof WifiP2pDnsSdServiceResponse) {
                handleDnsSdServiceResponse((WifiP2pDnsSdServiceResponse)resp);
            } else if (resp instanceof WifiP2pUpnpServiceResponse) {
                if (mUpnpServRspListener != null) {
                    handleUpnpServiceResponse((WifiP2pUpnpServiceResponse)resp);
                }
            } else {
                if (mServRspListener != null) {
                    mServRspListener.onServiceAvailable(resp.getServiceType(),
                            resp.getRawData(), resp.getSrcDevice());
                }
            }
        }

        private void handleUpnpServiceResponse(WifiP2pUpnpServiceResponse resp) {
            mUpnpServRspListener.onUpnpServiceAvailable(resp.getUniqueServiceNames(),
                    resp.getSrcDevice());
        }

        private void handleDnsSdServiceResponse(WifiP2pDnsSdServiceResponse resp) {
            if (resp.getDnsType() == WifiP2pDnsSdServiceInfo.DNS_TYPE_PTR) {
                if (mDnsSdServRspListener != null) {
                    mDnsSdServRspListener.onDnsSdServiceAvailable(
                            resp.getInstanceName(),
                            resp.getDnsQueryName(),
                            resp.getSrcDevice());
                }
            } else if (resp.getDnsType() == WifiP2pDnsSdServiceInfo.DNS_TYPE_TXT) {
                if (mDnsSdTxtListener != null) {
                    mDnsSdTxtListener.onDnsSdTxtRecordAvailable(
                            resp.getDnsQueryName(),
                            resp.getTxtRecord(),
                            resp.getSrcDevice());
                }
            } else {
                Log.e(TAG, "Unhandled resp " + resp);
            }
        }

        @UnsupportedAppUsage
        private int putListener(Object listener) {
            if (listener == null) return INVALID_LISTENER_KEY;
            int key;
            synchronized (mListenerMapLock) {
                do {
                    key = mListenerKey++;
                } while (key == INVALID_LISTENER_KEY);
                mListenerMap.put(key, listener);
            }
            return key;
        }

        private Object getListener(int key) {
            if (key == INVALID_LISTENER_KEY) return null;
            synchronized (mListenerMapLock) {
                return mListenerMap.remove(key);
            }
        }
    }

    private static void checkChannel(Channel c) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
    }

    private static void checkServiceInfo(WifiP2pServiceInfo info) {
        if (info == null) throw new IllegalArgumentException("service info is null");
    }

    private static void checkServiceRequest(WifiP2pServiceRequest req) {
        if (req == null) throw new IllegalArgumentException("service request is null");
    }

    private static void checkP2pConfig(WifiP2pConfig c) {
        if (c == null) throw new IllegalArgumentException("config cannot be null");
        if (TextUtils.isEmpty(c.deviceAddress)) {
            throw new IllegalArgumentException("deviceAddress cannot be empty");
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
        Binder binder = new Binder();
        Channel channel = initalizeChannel(srcContext, srcLooper, listener, getMessenger(binder),
                binder);
        return channel;
    }

    /**
     * Registers the application with the Wi-Fi framework. Enables system-only functionality.
     * @hide
     */
    public Channel initializeInternal(Context srcContext, Looper srcLooper,
                                      ChannelListener listener) {
        return initalizeChannel(srcContext, srcLooper, listener, getP2pStateMachineMessenger(),
                null);
    }

    private Channel initalizeChannel(Context srcContext, Looper srcLooper, ChannelListener listener,
                                     Messenger messenger, Binder binder) {
        if (messenger == null) return null;

        Channel c = new Channel(srcContext, srcLooper, listener, binder, this);
        if (c.mAsyncChannel.connectSync(srcContext, c.mHandler, messenger)
                == AsyncChannel.STATUS_SUCCESSFUL) {
            Bundle bundle = new Bundle();
            bundle.putString(CALLING_PACKAGE, c.mContext.getOpPackageName());
            bundle.putString(CALLING_FEATURE_ID, c.mContext.getFeatureId());
            bundle.putBinder(CALLING_BINDER, binder);
            c.mAsyncChannel.sendMessage(UPDATE_CHANNEL_INFO, 0,
                    c.putListener(null), bundle);
            return c;
        } else {
            c.close();
            return null;
        }
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
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void discoverPeers(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(DISCOVER_PEERS, 0, c.putListener(listener));
    }

    /**
     * Stop an ongoing peer discovery
     *
     * <p> The function call immediately returns after sending a stop request
     * to the framework. The application is notified of a success or failure to initiate
     * stop through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void stopPeerDiscovery(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(STOP_DISCOVERY, 0, c.putListener(listener));
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
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void connect(Channel c, WifiP2pConfig config, ActionListener listener) {
        checkChannel(c);
        checkP2pConfig(config);
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
        checkChannel(c);
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
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void createGroup(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(CREATE_GROUP, WifiP2pGroup.NETWORK_ID_PERSISTENT,
                c.putListener(listener));
    }

    /**
     * Create a p2p group with the current device as the group owner. This essentially creates
     * an access point that can accept connections from legacy clients as well as other p2p
     * devices.
     *
     * <p> An app should use {@link WifiP2pConfig.Builder} to build the configuration
     * for a group.
     *
     * <p class="note"><strong>Note:</strong>
     * This function would normally not be used unless the current device needs
     * to form a p2p group as a Group Owner and allow peers to join it as either
     * Group Clients or legacy Wi-Fi STAs.
     *
     * <p> The function call immediately returns after sending a group creation request
     * to the framework. The application is notified of a success or failure to initiate
     * group creation through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p> Application can request for the group details with {@link #requestGroupInfo}.
     *
     * @param c is the channel created at {@link #initialize}.
     * @param config the configuration of a p2p group.
     * @param listener for callbacks on success or failure. Can be null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void createGroup(@NonNull Channel c,
            @Nullable WifiP2pConfig config,
            @Nullable ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(CREATE_GROUP, 0,
                c.putListener(listener), config);
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
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REMOVE_GROUP, 0, c.putListener(listener));
    }

    /**
     * Force p2p to enter listen state
     *
     * @param c is the channel created at {@link #initialize(Context, Looper, ChannelListener)}
     * @param listener for callbacks on success or failure. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void startListening(@NonNull Channel c, @Nullable ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(START_LISTEN, 0, c.putListener(listener));
    }

    /**
     * Force p2p to exit listen state
     *
     * @param c is the channel created at {@link #initialize(Context, Looper, ChannelListener)}
     * @param listener for callbacks on success or failure. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void stopListening(@NonNull Channel c, @Nullable ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(STOP_LISTEN, 0, c.putListener(listener));
    }

    /**
     * Set P2P listening and operating channel.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listeningChannel the listening channel's Wifi channel number. e.g. 1, 6, 11.
     * @param operatingChannel the operating channel's Wifi channel number. e.g. 1, 6, 11.
     * @param listener for callbacks on success or failure. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.OVERRIDE_WIFI_CONFIG
    })
    public void setWifiP2pChannels(@NonNull Channel c, int listeningChannel, int operatingChannel,
            @Nullable ActionListener listener) {
        checkChannel(c);
        Bundle p2pChannels = new Bundle();
        p2pChannels.putInt("lc", listeningChannel);
        p2pChannels.putInt("oc", operatingChannel);
        c.mAsyncChannel.sendMessage(SET_CHANNEL, 0, c.putListener(listener), p2pChannels);
    }

    /**
     * Start a Wi-Fi Protected Setup (WPS) session.
     *
     * <p> The function call immediately returns after sending a request to start a
     * WPS session. Currently, this is only valid if the current device is running
     * as a group owner to allow any new clients to join the group. The application
     * is notified of a success or failure to initiate WPS through listener callbacks
     * {@link ActionListener#onSuccess} or {@link ActionListener#onFailure}.
     * @hide
     */
    @UnsupportedAppUsage
    public void startWps(Channel c, WpsInfo wps, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(START_WPS, 0, c.putListener(listener), wps);
    }

    /**
     * Register a local service for service discovery. If a local service is registered,
     * the framework automatically responds to a service discovery request from a peer.
     *
     * <p> The function call immediately returns after sending a request to add a local
     * service to the framework. The application is notified of a success or failure to
     * add service through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p>The service information is set through {@link WifiP2pServiceInfo}.<br>
     * or its subclass calls  {@link WifiP2pUpnpServiceInfo#newInstance} or
     *  {@link WifiP2pDnsSdServiceInfo#newInstance} for a Upnp or Bonjour service
     * respectively
     *
     * <p>The service information can be cleared with calls to
     *  {@link #removeLocalService} or {@link #clearLocalServices}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param servInfo is a local service information.
     * @param listener for callbacks on success or failure. Can be null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void addLocalService(Channel c, WifiP2pServiceInfo servInfo, ActionListener listener) {
        checkChannel(c);
        checkServiceInfo(servInfo);
        c.mAsyncChannel.sendMessage(ADD_LOCAL_SERVICE, 0, c.putListener(listener), servInfo);
    }

    /**
     * Remove a registered local service added with {@link #addLocalService}
     *
     * <p> The function call immediately returns after sending a request to remove a
     * local service to the framework. The application is notified of a success or failure to
     * add service through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param servInfo is the local service information.
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void removeLocalService(Channel c, WifiP2pServiceInfo servInfo,
            ActionListener listener) {
        checkChannel(c);
        checkServiceInfo(servInfo);
        c.mAsyncChannel.sendMessage(REMOVE_LOCAL_SERVICE, 0, c.putListener(listener), servInfo);
    }

    /**
     * Clear all registered local services of service discovery.
     *
     * <p> The function call immediately returns after sending a request to clear all
     * local services to the framework. The application is notified of a success or failure to
     * add service through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void clearLocalServices(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(CLEAR_LOCAL_SERVICES, 0, c.putListener(listener));
    }

    /**
     * Register a callback to be invoked on receiving service discovery response.
     * Used only for vendor specific protocol right now. For Bonjour or Upnp, use
     * {@link #setDnsSdResponseListeners} or {@link #setUpnpServiceResponseListener}
     * respectively.
     *
     * <p> see {@link #discoverServices} for the detail.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on receiving service discovery response.
     */
    public void setServiceResponseListener(Channel c,
            ServiceResponseListener listener) {
        checkChannel(c);
        c.mServRspListener = listener;
    }

    /**
     * Register a callback to be invoked on receiving Bonjour service discovery
     * response.
     *
     * <p> see {@link #discoverServices} for the detail.
     *
     * @param c
     * @param servListener is for listening to a Bonjour service response
     * @param txtListener is for listening to a Bonjour TXT record response
     */
    public void setDnsSdResponseListeners(Channel c,
            DnsSdServiceResponseListener servListener, DnsSdTxtRecordListener txtListener) {
        checkChannel(c);
        c.mDnsSdServRspListener = servListener;
        c.mDnsSdTxtListener = txtListener;
    }

    /**
     * Register a callback to be invoked on receiving upnp service discovery
     * response.
     *
     * <p> see {@link #discoverServices} for the detail.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on receiving service discovery response.
     */
    public void setUpnpServiceResponseListener(Channel c,
            UpnpServiceResponseListener listener) {
        checkChannel(c);
        c.mUpnpServRspListener = listener;
    }

    /**
     * Initiate service discovery. A discovery process involves scanning for
     * requested services for the purpose of establishing a connection to a peer
     * that supports an available service.
     *
     * <p> The function call immediately returns after sending a request to start service
     * discovery to the framework. The application is notified of a success or failure to initiate
     * discovery through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p> The services to be discovered are specified with calls to {@link #addServiceRequest}.
     *
     * <p>The application is notified of the response against the service discovery request
     * through listener callbacks registered by {@link #setServiceResponseListener} or
     * {@link #setDnsSdResponseListeners}, or {@link #setUpnpServiceResponseListener}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void discoverServices(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(DISCOVER_SERVICES, 0, c.putListener(listener));
    }

    /**
     * Add a service discovery request.
     *
     * <p> The function call immediately returns after sending a request to add service
     * discovery request to the framework. The application is notified of a success or failure to
     * add service through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p>After service discovery request is added, you can initiate service discovery by
     * {@link #discoverServices}.
     *
     * <p>The added service requests can be cleared with calls to
     * {@link #removeServiceRequest(Channel, WifiP2pServiceRequest, ActionListener)} or
     * {@link #clearServiceRequests(Channel, ActionListener)}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param req is the service discovery request.
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void addServiceRequest(Channel c,
            WifiP2pServiceRequest req, ActionListener listener) {
        checkChannel(c);
        checkServiceRequest(req);
        c.mAsyncChannel.sendMessage(ADD_SERVICE_REQUEST, 0,
                c.putListener(listener), req);
    }

    /**
     * Remove a specified service discovery request added with {@link #addServiceRequest}
     *
     * <p> The function call immediately returns after sending a request to remove service
     * discovery request to the framework. The application is notified of a success or failure to
     * add service through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param req is the service discovery request.
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void removeServiceRequest(Channel c, WifiP2pServiceRequest req,
            ActionListener listener) {
        checkChannel(c);
        checkServiceRequest(req);
        c.mAsyncChannel.sendMessage(REMOVE_SERVICE_REQUEST, 0,
                c.putListener(listener), req);
    }

    /**
     * Clear all registered service discovery requests.
     *
     * <p> The function call immediately returns after sending a request to clear all
     * service discovery requests to the framework. The application is notified of a success
     * or failure to add service through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callbacks on success or failure. Can be null.
     */
    public void clearServiceRequests(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(CLEAR_SERVICE_REQUESTS,
                0, c.putListener(listener));
    }

    /**
     * Request the current list of peers.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when peer list is available. Can be null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void requestPeers(Channel c, PeerListListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_PEERS, 0, c.putListener(listener));
    }

    /**
     * Request device connection info.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when connection info is available. Can be null.
     */
    public void requestConnectionInfo(Channel c, ConnectionInfoListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_CONNECTION_INFO, 0, c.putListener(listener));
    }

    /**
     * Request p2p group info.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when group info is available. Can be null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void requestGroupInfo(Channel c, GroupInfoListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_GROUP_INFO, 0, c.putListener(listener));
    }

    /**
     * Set p2p device name.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when group info is available. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.OVERRIDE_WIFI_CONFIG
    })
    public void setDeviceName(@NonNull Channel c, @NonNull String devName,
            @Nullable ActionListener listener) {
        checkChannel(c);
        WifiP2pDevice d = new WifiP2pDevice();
        d.deviceName = devName;
        c.mAsyncChannel.sendMessage(SET_DEVICE_NAME, 0, c.putListener(listener), d);
    }

    /**
     * Set Wifi Display information.
     *
     * @param c is the channel created at {@link #initialize}
     * @param wfdInfo the Wifi Display information to set
     * @param listener for callbacks on success or failure. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
    public void setWfdInfo(@NonNull Channel c, @NonNull WifiP2pWfdInfo wfdInfo,
            @Nullable ActionListener listener) {
        setWFDInfo(c, wfdInfo, listener);
    }

    /** @hide */
    @UnsupportedAppUsage
    @RequiresPermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
    public void setWFDInfo(@NonNull Channel c, @NonNull WifiP2pWfdInfo wfdInfo,
            @Nullable ActionListener listener) {
        checkChannel(c);
        try {
            mService.checkConfigureWifiDisplayPermission();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        c.mAsyncChannel.sendMessage(SET_WFD_INFO, 0, c.putListener(listener), wfdInfo);
    }


    /**
     * Delete a stored persistent group from the system settings.
     *
     * <p> The function call immediately returns after sending a persistent group removal request
     * to the framework. The application is notified of a success or failure to initiate
     * group removal through listener callbacks {@link ActionListener#onSuccess} or
     * {@link ActionListener#onFailure}.
     *
     * <p>The persistent p2p group list stored in the system can be obtained by
     * {@link #requestPersistentGroupInfo(Channel, PersistentGroupInfoListener)} and
     *  a network id can be obtained by {@link WifiP2pGroup#getNetworkId()}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param netId the network id of the p2p group.
     * @param listener for callbacks on success or failure. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.OVERRIDE_WIFI_CONFIG
    })
    public void deletePersistentGroup(@NonNull Channel c, int netId,
            @Nullable ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(DELETE_PERSISTENT_GROUP, netId, c.putListener(listener));
    }

    /**
     * Request a list of all the persistent p2p groups stored in system.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when persistent group info list is available. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.READ_WIFI_CREDENTIAL
    })
    public void requestPersistentGroupInfo(@NonNull Channel c,
            @Nullable PersistentGroupInfoListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_PERSISTENT_GROUP_INFO, 0, c.putListener(listener));
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"MIRACAST_"}, value = {
            MIRACAST_DISABLED,
            MIRACAST_SOURCE,
            MIRACAST_SINK})
    public @interface MiracastMode {}

    /**
     * Miracast is disabled.
     * @hide
     */
    @SystemApi
    public static final int MIRACAST_DISABLED = 0;
    /**
     * Device acts as a Miracast source.
     * @hide
     */
    @SystemApi
    public static final int MIRACAST_SOURCE   = 1;
    /**
     * Device acts as a Miracast sink.
     * @hide
     */
    @SystemApi
    public static final int MIRACAST_SINK     = 2;

    /**
     * This is used to provide information to drivers to optimize performance depending
     * on the current mode of operation.
     * {@link #MIRACAST_DISABLED} - disabled
     * {@link #MIRACAST_SOURCE} - source operation
     * {@link #MIRACAST_SINK} - sink operation
     *
     * As an example, the driver could reduce the channel dwell time during scanning
     * when acting as a source or sink to minimize impact on Miracast.
     *
     * @param mode mode of operation. One of {@link #MIRACAST_DISABLED}, {@link #MIRACAST_SOURCE},
     * or {@link #MIRACAST_SINK}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
    public void setMiracastMode(@MiracastMode int mode) {
        try {
            mService.setMiracastMode(mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a reference to WifiP2pService handler. This is used to establish
     * an AsyncChannel communication with WifiService
     *
     * @param binder A binder for the service to associate with this client.
     *
     * @return Messenger pointing to the WifiP2pService handler
     * @hide
     */
    public Messenger getMessenger(Binder binder) {
        try {
            return mService.getMessenger(binder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a reference to P2pStateMachine handler. This is used to establish
     * a priveleged AsyncChannel communication with WifiP2pService.
     *
     * @return Messenger pointing to the WifiP2pService handler
     * @hide
     */
    public Messenger getP2pStateMachineMessenger() {
        try {
            return mService.getP2pStateMachineMessenger();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a handover request message for use in WFA NFC Handover transfer.
     * @hide
     */
    public void getNfcHandoverRequest(Channel c, HandoverMessageListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(GET_HANDOVER_REQUEST, 0, c.putListener(listener));
    }


    /**
     * Get a handover select message for use in WFA NFC Handover transfer.
     * @hide
     */
    public void getNfcHandoverSelect(Channel c, HandoverMessageListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(GET_HANDOVER_SELECT, 0, c.putListener(listener));
    }

    /**
     * @hide
     */
    public void initiatorReportNfcHandover(Channel c, String handoverSelect,
                                              ActionListener listener) {
        checkChannel(c);
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_HANDOVER_MESSAGE, handoverSelect);
        c.mAsyncChannel.sendMessage(INITIATOR_REPORT_NFC_HANDOVER, 0,
                c.putListener(listener), bundle);
    }


    /**
     * @hide
     */
    public void responderReportNfcHandover(Channel c, String handoverRequest,
                                              ActionListener listener) {
        checkChannel(c);
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_HANDOVER_MESSAGE, handoverRequest);
        c.mAsyncChannel.sendMessage(RESPONDER_REPORT_NFC_HANDOVER, 0,
                c.putListener(listener), bundle);
    }

    /**
     * Removes all saved p2p groups.
     *
     * @param c is the channel created at {@link #initialize}.
     * @param listener for callback on success or failure. Can be null.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void factoryReset(@NonNull Channel c, @Nullable ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(FACTORY_RESET, 0, c.putListener(listener));
    }

    /**
     * Request saved WifiP2pConfig which used for an ongoing peer connection
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when ongoing peer config updated. Can't be null.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void requestOngoingPeerConfig(@NonNull Channel c,
            @NonNull OngoingPeerInfoListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_ONGOING_PEER_CONFIG,
                Binder.getCallingUid(), c.putListener(listener));
    }

     /**
     * Set saved WifiP2pConfig which used for an ongoing peer connection
     *
     * @param c is the channel created at {@link #initialize}
     * @param config used for change an ongoing peer connection
     * @param listener for callback when ongoing peer config updated. Can be null.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_STACK)
    public void setOngoingPeerConfig(@NonNull Channel c, @NonNull WifiP2pConfig config,
            @Nullable ActionListener listener) {
        checkChannel(c);
        checkP2pConfig(config);
        c.mAsyncChannel.sendMessage(SET_ONGOING_PEER_CONFIG, 0,
                c.putListener(listener), config);
    }

    /**
     * Request p2p enabled state.
     *
     * <p> This state indicates whether Wi-Fi p2p is enabled or disabled.
     * The valid value is one of {@link #WIFI_P2P_STATE_DISABLED} or
     * {@link #WIFI_P2P_STATE_ENABLED}. The state is returned using the
     * {@link P2pStateListener} listener.
     *
     * <p> This state is also included in the {@link #WIFI_P2P_STATE_CHANGED_ACTION}
     * broadcast event with extra {@link #EXTRA_WIFI_STATE}.
     *
     * @param c is the channel created at {@link #initialize}.
     * @param listener for callback when p2p state is available..
     */
    public void requestP2pState(@NonNull Channel c,
            @NonNull P2pStateListener listener) {
        checkChannel(c);
        if (listener == null) throw new IllegalArgumentException("This listener cannot be null.");
        c.mAsyncChannel.sendMessage(REQUEST_P2P_STATE, 0, c.putListener(listener));
    }

    /**
     * Request p2p discovery state.
     *
     * <p> This state indicates whether p2p discovery has started or stopped.
     * The valid value is one of {@link #WIFI_P2P_DISCOVERY_STARTED} or
     * {@link #WIFI_P2P_DISCOVERY_STOPPED}. The state is returned using the
     * {@link DiscoveryStateListener} listener.
     *
     * <p> This state is also included in the {@link #WIFI_P2P_DISCOVERY_CHANGED_ACTION}
     * broadcast event with extra {@link #EXTRA_DISCOVERY_STATE}.
     *
     * @param c is the channel created at {@link #initialize}.
     * @param listener for callback when discovery state is available..
     */
    public void requestDiscoveryState(@NonNull Channel c,
            @NonNull DiscoveryStateListener listener) {
        checkChannel(c);
        if (listener == null) throw new IllegalArgumentException("This listener cannot be null.");
        c.mAsyncChannel.sendMessage(REQUEST_DISCOVERY_STATE, 0, c.putListener(listener));
    }

    /**
     * Request network info.
     *
     * <p> This method provides the network info in the form of a {@link android.net.NetworkInfo}.
     * {@link android.net.NetworkInfo#isAvailable()} indicates the p2p availability and
     * {@link android.net.NetworkInfo#getDetailedState()} reports the current fine-grained state
     * of the network. This {@link android.net.NetworkInfo} is returned using the
     * {@link NetworkInfoListener} listener.
     *
     * <p> This information is also included in the {@link #WIFI_P2P_CONNECTION_CHANGED_ACTION}
     * broadcast event with extra {@link #EXTRA_NETWORK_INFO}.
     *
     * @param c is the channel created at {@link #initialize}.
     * @param listener for callback when network info is available..
     */
    public void requestNetworkInfo(@NonNull Channel c,
            @NonNull NetworkInfoListener listener) {
        checkChannel(c);
        if (listener == null) throw new IllegalArgumentException("This listener cannot be null.");
        c.mAsyncChannel.sendMessage(REQUEST_NETWORK_INFO, 0, c.putListener(listener));
    }

     /**
     * Request Device Info
     *
     * <p> This method provides the device info
     * in the form of a {@link android.net.wifi.p2p.WifiP2pDevice}.
     * Valid {@link android.net.wifi.p2p.WifiP2pDevice} is returned when p2p is enabled.
     * To get information notifications on P2P getting enabled refers
     * {@link #WIFI_P2P_STATE_ENABLED}.
     *
     * <p> This {@link android.net.wifi.p2p.WifiP2pDevice} is returned using the
     * {@link DeviceInfoListener} listener.
     *
     * <p> {@link android.net.wifi.p2p.WifiP2pDevice#deviceAddress} is only available if the caller
     * holds the {@code android.Manifest.permission#LOCAL_MAC_ADDRESS} permission, and holds the
     * anonymized MAC address (02:00:00:00:00:00) otherwise.
     *
     * <p> This information is also included in the {@link #WIFI_P2P_THIS_DEVICE_CHANGED_ACTION}
     * broadcast event with extra {@link #EXTRA_WIFI_P2P_DEVICE}.
     *
     * @param c is the channel created at {@link #initialize(Context, Looper, ChannelListener)}.
     * @param listener for callback when network info is available.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
    public void requestDeviceInfo(@NonNull Channel c, @NonNull DeviceInfoListener listener) {
        checkChannel(c);
        if (listener == null) throw new IllegalArgumentException("This listener cannot be null.");
        c.mAsyncChannel.sendMessage(REQUEST_DEVICE_INFO, 0, c.putListener(listener));
    }
}
