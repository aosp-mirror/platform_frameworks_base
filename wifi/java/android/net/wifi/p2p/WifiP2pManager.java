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
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceResponse;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceResponse;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

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
 * the p2p group owner through a socket connection.
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
     * Broadcast intent action indicating that peer discovery has either started or stopped.
     * One extra {@link #EXTRA_DISCOVERY_STATE} indicates whether discovery has started
     * or stopped.
     *
     * Note that discovery will be stopped during a connection setup. If the application tries
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
     * @hide
     */
    public static final String WIFI_P2P_PERSISTENT_GROUPS_CHANGED_ACTION =
        "android.net.wifi.p2p.PERSISTENT_GROUPS_CHANGED";

    /**
     * The lookup key for a {@link #String} object.
     * Retrieve with {@link android.os.Bundle#getString(String)}.
     * @hide
     */
    public static final String APP_PKG_BUNDLE_KEY = "appPkgName";

    /**
     * The lookup key for a {@link #Boolean} object.
     * Retrieve with {@link android.os.Bundle#getBoolean(String)}.
     * @hide
     */
    public static final String RESET_DIALOG_LISTENER_BUNDLE_KEY = "dialogResetFlag";

    /**
     * The lookup key for a {@link #String} object.
     * Retrieve with {@link android.os.Bundle#getString(String)}.
     * @hide
     */
    public static final String WPS_PIN_BUNDLE_KEY = "wpsPin";

    /**
     * The lookup key for a {@link android.net.wifi.p2p.WifiP2pDevice} object
     * Retrieve with {@link android.os.Bundle#getParcelable(String)}.
     * @hide
     */
    public static final String P2P_DEV_BUNDLE_KEY = "wifiP2pDevice";

    /**
     * The lookup key for a {@link android.net.wifi.p2p.WifiP2pConfig} object
     * Retrieve with {@link android.os.Bundle#getParcelable(String)}.
     * @hide
     */
    public static final String P2P_CONFIG_BUNDLE_KEY = "wifiP2pConfig";

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
    public static final int SET_DIALOG_LISTENER                     = BASE + 54;
    /** @hide */
    public static final int DIALOG_LISTENER_DETACHED                = BASE + 55;
    /** @hide */
    public static final int DIALOG_LISTENER_ATTACHED                = BASE + 56;

    /** @hide */
    public static final int CONNECTION_REQUESTED                    = BASE + 57;
    /** @hide */
    public static final int SHOW_PIN_REQUESTED                      = BASE + 58;

    /** @hide */
    public static final int DELETE_PERSISTENT_GROUP                 = BASE + 59;
    /** @hide */
    public static final int DELETE_PERSISTENT_GROUP_FAILED          = BASE + 60;
    /** @hide */
    public static final int DELETE_PERSISTENT_GROUP_SUCCEEDED       = BASE + 61;

    /** @hide */
    public static final int REQUEST_PERSISTENT_GROUP_INFO           = BASE + 62;
    /** @hide */
    public static final int RESPONSE_PERSISTENT_GROUP_INFO          = BASE + 63;

    /** @hide */
    public static final int SET_WFD_INFO                            = BASE + 64;
    /** @hide */
    public static final int SET_WFD_INFO_FAILED                     = BASE + 65;
    /** @hide */
    public static final int SET_WFD_INFO_SUCCEEDED                  = BASE + 66;

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

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the {@link #discoverServices} failed because no service
     * requests are added. Use {@link #addServiceRequest} to add a service
     * request.
     */
    public static final int NO_SERVICE_REQUESTS = 3;

    /**
     * Passed with {@link DialogListener#onDetached}.
     * Indicates that the registered listener was detached from the system because
     * the application went into background.
     * @hide
     */
    public static final int NOT_IN_FOREGROUND   = 4;

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
     * Interface for callback invocation when dialog events are received.
     * see {@link #setDialogListener}.
     * @hide
     */
    public interface DialogListener {

        /**
         * Called by the system when a request to show WPS pin is received.
         *
         * @param pin WPS pin.
         */
        public void onShowPinRequested(String pin);

        /**
         * Called by the system when a request to establish the connection is received.
         *
         * Application can then call {@link #connect} with the given config if the request
         * is acceptable.
         *
         * @param device the source device.
         * @param config p2p configuration.
         */
        public void onConnectionRequested(WifiP2pDevice device, WifiP2pConfig config);

        /**
         * Called by the system when this listener was attached to the system.
         */
        public void onAttached();

        /**
         * Called by the system when this listener was detached from the system or
         * failed to attach.
         *
         * Application can request again using {@link #setDialogListener} when it is
         * in the foreground.
         *
         * @param reason The reason for failure could be one of {@link #ERROR},
         * {@link #BUSY}, {@link #P2P_UNSUPPORTED} or {@link #NOT_IN_FOREGROUND}
         */
        public void onDetached(int reason);
    }

    /** Interface for callback invocation when stored group info list is available {@hide}*/
    public interface PersistentGroupInfoListener {
        /**
         * The requested stored p2p group info list is available
         * @param groups Wi-Fi p2p group info list
         */
        public void onPersistentGroupInfoAvailable(WifiP2pGroupList groups);
    }

    /**
     * A channel that connects the application to the Wifi p2p framework.
     * Most p2p operations require a Channel as an argument. An instance of Channel is obtained
     * by doing a call on {@link #initialize}
     */
    public static class Channel {
        Channel(Context context, Looper looper, ChannelListener l) {
            mAsyncChannel = new AsyncChannel();
            mHandler = new P2pHandler(looper);
            mChannelListener = l;
            mContext = context;
        }
        private final static int INVALID_LISTENER_KEY = 0;
        private ChannelListener mChannelListener;
        private ServiceResponseListener mServRspListener;
        private DnsSdServiceResponseListener mDnsSdServRspListener;
        private DnsSdTxtRecordListener mDnsSdTxtListener;
        private UpnpServiceResponseListener mUpnpServRspListener;
        private HashMap<Integer, Object> mListenerMap = new HashMap<Integer, Object>();
        private Object mListenerMapLock = new Object();
        private int mListenerKey = 0;
        private DialogListener mDialogListener;

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
                    case WifiP2pManager.DISCOVER_PEERS_FAILED:
                    case WifiP2pManager.STOP_DISCOVERY_FAILED:
                    case WifiP2pManager.DISCOVER_SERVICES_FAILED:
                    case WifiP2pManager.CONNECT_FAILED:
                    case WifiP2pManager.CANCEL_CONNECT_FAILED:
                    case WifiP2pManager.CREATE_GROUP_FAILED:
                    case WifiP2pManager.REMOVE_GROUP_FAILED:
                    case WifiP2pManager.ADD_LOCAL_SERVICE_FAILED:
                    case WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED:
                    case WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED:
                    case WifiP2pManager.ADD_SERVICE_REQUEST_FAILED:
                    case WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED:
                    case WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED:
                    case WifiP2pManager.SET_DEVICE_NAME_FAILED:
                    case WifiP2pManager.DELETE_PERSISTENT_GROUP_FAILED:
                    case WifiP2pManager.SET_WFD_INFO_FAILED:
                        if (listener != null) {
                            ((ActionListener) listener).onFailure(message.arg1);
                        }
                        break;
                    /* ActionListeners grouped together */
                    case WifiP2pManager.DISCOVER_PEERS_SUCCEEDED:
                    case WifiP2pManager.STOP_DISCOVERY_SUCCEEDED:
                    case WifiP2pManager.DISCOVER_SERVICES_SUCCEEDED:
                    case WifiP2pManager.CONNECT_SUCCEEDED:
                    case WifiP2pManager.CANCEL_CONNECT_SUCCEEDED:
                    case WifiP2pManager.CREATE_GROUP_SUCCEEDED:
                    case WifiP2pManager.REMOVE_GROUP_SUCCEEDED:
                    case WifiP2pManager.ADD_LOCAL_SERVICE_SUCCEEDED:
                    case WifiP2pManager.REMOVE_LOCAL_SERVICE_SUCCEEDED:
                    case WifiP2pManager.CLEAR_LOCAL_SERVICES_SUCCEEDED:
                    case WifiP2pManager.ADD_SERVICE_REQUEST_SUCCEEDED:
                    case WifiP2pManager.REMOVE_SERVICE_REQUEST_SUCCEEDED:
                    case WifiP2pManager.CLEAR_SERVICE_REQUESTS_SUCCEEDED:
                    case WifiP2pManager.SET_DEVICE_NAME_SUCCEEDED:
                    case WifiP2pManager.DELETE_PERSISTENT_GROUP_SUCCEEDED:
                    case WifiP2pManager.SET_WFD_INFO_SUCCEEDED:
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
                    case WifiP2pManager.RESPONSE_SERVICE:
                        WifiP2pServiceResponse resp = (WifiP2pServiceResponse) message.obj;
                        handleServiceResponse(resp);
                        break;
                    case WifiP2pManager.CONNECTION_REQUESTED:
                        if (mDialogListener != null) {
                            Bundle bundle = message.getData();
                            mDialogListener.onConnectionRequested(
                                    (WifiP2pDevice)bundle.getParcelable(
                                            P2P_DEV_BUNDLE_KEY),
                                    (WifiP2pConfig)bundle.getParcelable(
                                            P2P_CONFIG_BUNDLE_KEY));
                        }
                        break;
                    case WifiP2pManager.SHOW_PIN_REQUESTED:
                        if (mDialogListener != null) {
                            Bundle bundle = message.getData();
                            mDialogListener.onShowPinRequested(
                                    bundle.getString(WPS_PIN_BUNDLE_KEY));
                        }
                        break;
                    case WifiP2pManager.DIALOG_LISTENER_ATTACHED:
                        if (mDialogListener != null) {
                            mDialogListener.onAttached();
                        }
                        break;
                    case WifiP2pManager.DIALOG_LISTENER_DETACHED:
                        if (mDialogListener != null) {
                            mDialogListener.onDetached(message.arg1);
                            mDialogListener = null;
                        }
                        break;
                    case WifiP2pManager.RESPONSE_PERSISTENT_GROUP_INFO:
                        WifiP2pGroupList groups = (WifiP2pGroupList) message.obj;
                        if (listener != null) {
                            ((PersistentGroupInfoListener) listener).
                                onPersistentGroupInfoAvailable(groups);
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

        private void setDialogListener(DialogListener listener) {
            mDialogListener = listener;
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

        Channel c = new Channel(srcContext, srcLooper, listener);
        if (c.mAsyncChannel.connectSync(srcContext, c.mHandler, messenger)
                == AsyncChannel.STATUS_SUCCESSFUL) {
            return c;
        } else {
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
    public void connect(Channel c, WifiP2pConfig config, ActionListener listener) {
        checkChannel(c);
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
    public void createGroup(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(CREATE_GROUP, WifiP2pGroup.PERSISTENT_NET_ID,
                c.putListener(listener));
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
    public void requestGroupInfo(Channel c, GroupInfoListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_GROUP_INFO, 0, c.putListener(listener));
    }

    /**
     * Set p2p device name.
     * @hide
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when group info is available. Can be null.
     */
    public void setDeviceName(Channel c, String devName, ActionListener listener) {
        checkChannel(c);
        WifiP2pDevice d = new WifiP2pDevice();
        d.deviceName = devName;
        c.mAsyncChannel.sendMessage(SET_DEVICE_NAME, 0, c.putListener(listener), d);
    }

    /** @hide */
    public void setWFDInfo(
            Channel c, WifiP2pWfdInfo wfdInfo,
            ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(SET_WFD_INFO, 0, c.putListener(listener), wfdInfo);
    }

    /**
     * Set dialog listener to over-ride system dialogs on p2p events. This function
     * allows an application to receive notifications on connection requests from
     * peers so that it can customize the user experience for connection with
     * peers.
     *
     * <p> The function call immediately returns after sending a request
     * to the framework. The application is notified of a success or failure to attach
     * to the system through listener callbacks {@link DialogListener#onAttached} or
     * {@link DialogListener#onDetached}.
     *
     * <p> Note that only foreground application will be successful in overriding the
     * system dialogs.
     * @hide
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback on a dialog event.
     */
    public void setDialogListener(Channel c, DialogListener listener) {
        checkChannel(c);
        c.setDialogListener(listener);

        /**
         * mAsyncChannel should always stay private and inaccessible from the app
         * to prevent an app from sending a message with a fake app name to gain
         * control over the dialogs
         */
        Message msg = Message.obtain();
        Bundle bundle = new Bundle();
        bundle.putString(APP_PKG_BUNDLE_KEY, c.mContext.getPackageName());
        bundle.putBoolean(RESET_DIALOG_LISTENER_BUNDLE_KEY, listener == null);
        msg.what = SET_DIALOG_LISTENER;
        msg.setData(bundle);
        c.mAsyncChannel.sendMessage(msg);
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
     * @param netId he network id of the p2p group.
     * @param listener for callbacks on success or failure. Can be null.
     * @hide
     */
    public void deletePersistentGroup(Channel c, int netId, ActionListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(DELETE_PERSISTENT_GROUP, netId, c.putListener(listener));
    }

    /**
     * Request a list of all the persistent p2p groups stored in system.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener for callback when persistent group info list is available. Can be null.
     * @hide
     */
    public void requestPersistentGroupInfo(Channel c, PersistentGroupInfoListener listener) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(REQUEST_PERSISTENT_GROUP_INFO, 0, c.putListener(listener));
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
