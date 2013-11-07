/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.net.DhcpInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.WorkSource;
import android.os.Messenger;
import android.util.Log;
import android.util.SparseArray;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;

import com.android.internal.R;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.List;

/**
 * This class provides the primary API for managing all aspects of Wi-Fi
 * connectivity. Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(String) Context.getSystemService(Context.WIFI_SERVICE)}.

 * It deals with several categories of items:
 * <ul>
 * <li>The list of configured networks. The list can be viewed and updated,
 * and attributes of individual entries can be modified.</li>
 * <li>The currently active Wi-Fi network, if any. Connectivity can be
 * established or torn down, and dynamic information about the state of
 * the network can be queried.</li>
 * <li>Results of access point scans, containing enough information to
 * make decisions about what access point to connect to.</li>
 * <li>It defines the names of various Intent actions that are broadcast
 * upon any sort of change in Wi-Fi state.
 * </ul>
 * This is the API to use when performing Wi-Fi specific operations. To
 * perform operations that pertain to network connectivity at an abstract
 * level, use {@link android.net.ConnectivityManager}.
 */
public class WifiManager {

    private static final String TAG = "WifiManager";
    // Supplicant error codes:
    /**
     * The error code if there was a problem authenticating.
     */
    public static final int ERROR_AUTHENTICATING = 1;

    /**
     * Broadcast intent action indicating whether Wi-Fi scanning is allowed currently
     * @hide
     */
    public static final String WIFI_SCAN_AVAILABLE = "wifi_scan_available";

    /**
     * Extra int indicating scan availability, WIFI_STATE_ENABLED and WIFI_STATE_DISABLED
     * @hide
     */
     public static final String EXTRA_SCAN_AVAILABLE = "scan_enabled";

    /**
     * Broadcast intent action indicating that Wi-Fi has been enabled, disabled,
     * enabling, disabling, or unknown. One extra provides this state as an int.
     * Another extra provides the previous state, if available.
     *
     * @see #EXTRA_WIFI_STATE
     * @see #EXTRA_PREVIOUS_WIFI_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String WIFI_STATE_CHANGED_ACTION =
        "android.net.wifi.WIFI_STATE_CHANGED";
    /**
     * The lookup key for an int that indicates whether Wi-Fi is enabled,
     * disabled, enabling, disabling, or unknown.  Retrieve it with
     * {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_STATE_DISABLED
     * @see #WIFI_STATE_DISABLING
     * @see #WIFI_STATE_ENABLED
     * @see #WIFI_STATE_ENABLING
     * @see #WIFI_STATE_UNKNOWN
     */
    public static final String EXTRA_WIFI_STATE = "wifi_state";
    /**
     * The previous Wi-Fi state.
     *
     * @see #EXTRA_WIFI_STATE
     */
    public static final String EXTRA_PREVIOUS_WIFI_STATE = "previous_wifi_state";

    /**
     * Wi-Fi is currently being disabled. The state will change to {@link #WIFI_STATE_DISABLED} if
     * it finishes successfully.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_DISABLING = 0;
    /**
     * Wi-Fi is disabled.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_DISABLED = 1;
    /**
     * Wi-Fi is currently being enabled. The state will change to {@link #WIFI_STATE_ENABLED} if
     * it finishes successfully.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_ENABLING = 2;
    /**
     * Wi-Fi is enabled.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_ENABLED = 3;
    /**
     * Wi-Fi is in an unknown state. This state will occur when an error happens while enabling
     * or disabling.
     *
     * @see #WIFI_STATE_CHANGED_ACTION
     * @see #getWifiState()
     */
    public static final int WIFI_STATE_UNKNOWN = 4;

    /**
     * Broadcast intent action indicating that Wi-Fi AP has been enabled, disabled,
     * enabling, disabling, or failed.
     *
     * @hide
     */
    public static final String WIFI_AP_STATE_CHANGED_ACTION =
        "android.net.wifi.WIFI_AP_STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether Wi-Fi AP is enabled,
     * disabled, enabling, disabling, or failed.  Retrieve it with
     * {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #WIFI_AP_STATE_DISABLED
     * @see #WIFI_AP_STATE_DISABLING
     * @see #WIFI_AP_STATE_ENABLED
     * @see #WIFI_AP_STATE_ENABLING
     * @see #WIFI_AP_STATE_FAILED
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_STATE = "wifi_state";
    /**
     * The previous Wi-Fi state.
     *
     * @see #EXTRA_WIFI_AP_STATE
     *
     * @hide
     */
    public static final String EXTRA_PREVIOUS_WIFI_AP_STATE = "previous_wifi_state";
    /**
     * Wi-Fi AP is currently being disabled. The state will change to
     * {@link #WIFI_AP_STATE_DISABLED} if it finishes successfully.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    public static final int WIFI_AP_STATE_DISABLING = 10;
    /**
     * Wi-Fi AP is disabled.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiState()
     *
     * @hide
     */
    public static final int WIFI_AP_STATE_DISABLED = 11;
    /**
     * Wi-Fi AP is currently being enabled. The state will change to
     * {@link #WIFI_AP_STATE_ENABLED} if it finishes successfully.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    public static final int WIFI_AP_STATE_ENABLING = 12;
    /**
     * Wi-Fi AP is enabled.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    public static final int WIFI_AP_STATE_ENABLED = 13;
    /**
     * Wi-Fi AP is in a failed state. This state will occur when an error occurs during
     * enabling or disabling
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    public static final int WIFI_AP_STATE_FAILED = 14;

    /**
     * Broadcast intent action indicating that a connection to the supplicant has
     * been established (and it is now possible
     * to perform Wi-Fi operations) or the connection to the supplicant has been
     * lost. One extra provides the connection state as a boolean, where {@code true}
     * means CONNECTED.
     * @see #EXTRA_SUPPLICANT_CONNECTED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUPPLICANT_CONNECTION_CHANGE_ACTION =
        "android.net.wifi.supplicant.CONNECTION_CHANGE";
    /**
     * The lookup key for a boolean that indicates whether a connection to
     * the supplicant daemon has been gained or lost. {@code true} means
     * a connection now exists.
     * Retrieve it with {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     */
    public static final String EXTRA_SUPPLICANT_CONNECTED = "connected";
    /**
     * Broadcast intent action indicating that the state of Wi-Fi connectivity
     * has changed. One extra provides the new state
     * in the form of a {@link android.net.NetworkInfo} object. If the new
     * state is CONNECTED, additional extras may provide the BSSID and WifiInfo of
     * the access point.
     * as a {@code String}.
     * @see #EXTRA_NETWORK_INFO
     * @see #EXTRA_BSSID
     * @see #EXTRA_WIFI_INFO
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NETWORK_STATE_CHANGED_ACTION = "android.net.wifi.STATE_CHANGE";
    /**
     * The lookup key for a {@link android.net.NetworkInfo} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_INFO = "networkInfo";
    /**
     * The lookup key for a String giving the BSSID of the access point to which
     * we are connected. Only present when the new state is CONNECTED.
     * Retrieve with
     * {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_BSSID = "bssid";
    /**
     * The lookup key for a {@link android.net.wifi.WifiInfo} object giving the
     * information about the access point to which we are connected. Only present
     * when the new state is CONNECTED.  Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_WIFI_INFO = "wifiInfo";
    /**
     * Broadcast intent action indicating that the state of establishing a connection to
     * an access point has changed.One extra provides the new
     * {@link SupplicantState}. Note that the supplicant state is Wi-Fi specific, and
     * is not generally the most useful thing to look at if you are just interested in
     * the overall state of connectivity.
     * @see #EXTRA_NEW_STATE
     * @see #EXTRA_SUPPLICANT_ERROR
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUPPLICANT_STATE_CHANGED_ACTION =
        "android.net.wifi.supplicant.STATE_CHANGE";
    /**
     * The lookup key for a {@link SupplicantState} describing the new state
     * Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NEW_STATE = "newState";

    /**
     * The lookup key for a {@link SupplicantState} describing the supplicant
     * error code if any
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String, int)}.
     * @see #ERROR_AUTHENTICATING
     */
    public static final String EXTRA_SUPPLICANT_ERROR = "supplicantError";

    /**
     * Broadcast intent action indicating that the configured networks changed.
     * This can be as a result of adding/updating/deleting a network. If
     * {@link #EXTRA_MULTIPLE_NETWORKS_CHANGED} is set to true the new configuration
     * can be retreived with the {@link #EXTRA_WIFI_CONFIGURATION} extra. If multiple
     * Wi-Fi configurations changed, {@link #EXTRA_WIFI_CONFIGURATION} will not be present.
     * @hide
     */
    public static final String CONFIGURED_NETWORKS_CHANGED_ACTION =
        "android.net.wifi.CONFIGURED_NETWORKS_CHANGE";
    /**
     * The lookup key for a (@link android.net.wifi.WifiConfiguration} object representing
     * the changed Wi-Fi configuration when the {@link #CONFIGURED_NETWORKS_CHANGED_ACTION}
     * broadcast is sent.
     * @hide
     */
    public static final String EXTRA_WIFI_CONFIGURATION = "wifiConfiguration";
    /**
     * Multiple network configurations have changed.
     * @see #CONFIGURED_NETWORKS_CHANGED_ACTION
     *
     * @hide
     */
    public static final String EXTRA_MULTIPLE_NETWORKS_CHANGED = "multipleChanges";
    /**
     * The lookup key for an integer indicating the reason a Wi-Fi network configuration
     * has changed. Only present if {@link #EXTRA_MULTIPLE_NETWORKS_CHANGED} is {@code false}
     * @see #CONFIGURED_NETWORKS_CHANGED_ACTION
     * @hide
     */
    public static final String EXTRA_CHANGE_REASON = "changeReason";
    /**
     * The configuration is new and was added.
     * @hide
     */
    public static final int CHANGE_REASON_ADDED = 0;
    /**
     * The configuration was removed and is no longer present in the system's list of
     * configured networks.
     * @hide
     */
    public static final int CHANGE_REASON_REMOVED = 1;
    /**
     * The configuration has changed as a result of explicit action or because the system
     * took an automated action such as disabling a malfunctioning configuration.
     * @hide
     */
    public static final int CHANGE_REASON_CONFIG_CHANGE = 2;
    /**
     * An access point scan has completed, and results are available from the supplicant.
     * Call {@link #getScanResults()} to obtain the results.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SCAN_RESULTS_AVAILABLE_ACTION = "android.net.wifi.SCAN_RESULTS";
    /**
     * A batch of access point scans has been completed and the results areavailable.
     * Call {@link #getBatchedScanResults()} to obtain the results.
     * @hide pending review
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String BATCHED_SCAN_RESULTS_AVAILABLE_ACTION =
            "android.net.wifi.BATCHED_RESULTS";
    /**
     * The RSSI (signal strength) has changed.
     * @see #EXTRA_NEW_RSSI
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String RSSI_CHANGED_ACTION = "android.net.wifi.RSSI_CHANGED";
    /**
     * The lookup key for an {@code int} giving the new RSSI in dBm.
     */
    public static final String EXTRA_NEW_RSSI = "newRssi";

    /**
     * Broadcast intent action indicating that the link configuration
     * changed on wifi.
     * @hide
     */
    public static final String LINK_CONFIGURATION_CHANGED_ACTION =
        "android.net.wifi.LINK_CONFIGURATION_CHANGED";

    /**
     * The lookup key for a {@link android.net.LinkProperties} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_LINK_PROPERTIES = "linkProperties";

    /**
     * The lookup key for a {@link android.net.LinkCapabilities} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_LINK_CAPABILITIES = "linkCapabilities";

    /**
     * The network IDs of the configured networks could have changed.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NETWORK_IDS_CHANGED_ACTION = "android.net.wifi.NETWORK_IDS_CHANGED";

    /**
     * Activity Action: Show a system activity that allows the user to enable
     * scans to be available even with Wi-Fi turned off.
     *
     * <p>Notification of the result of this activity is posted using the
     * {@link android.app.Activity#onActivityResult} callback. The
     * <code>resultCode</code>
     * will be {@link android.app.Activity#RESULT_OK} if scan always mode has
     * been turned on or {@link android.app.Activity#RESULT_CANCELED} if the user
     * has rejected the request or an error has occurred.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE =
            "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE";

    /**
     * Activity Action: Pick a Wi-Fi network to connect to.
     * <p>Input: Nothing.
     * <p>Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PICK_WIFI_NETWORK = "android.net.wifi.PICK_WIFI_NETWORK";

    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active,
     * and will behave normally, i.e., it will attempt to automatically
     * establish a connection to a remembered access point that is
     * within range, and will do periodic scans if there are remembered
     * access points but none are in range.
     */
    public static final int WIFI_MODE_FULL = 1;
    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active,
     * but the only operation that will be supported is initiation of
     * scans, and the subsequent reporting of scan results. No attempts
     * will be made to automatically connect to remembered access points,
     * nor will periodic scans be automatically performed looking for
     * remembered access points. Scans must be explicitly requested by
     * an application in this mode.
     */
    public static final int WIFI_MODE_SCAN_ONLY = 2;
    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active as in mode
     * {@link #WIFI_MODE_FULL} but it operates at high performance
     * with minimum packet loss and low packet latency even when
     * the device screen is off. This mode will consume more power
     * and hence should be used only when there is a need for such
     * an active connection.
     * <p>
     * An example use case is when a voice connection needs to be
     * kept active even after the device screen goes off. Holding the
     * regular {@link #WIFI_MODE_FULL} lock will keep the wifi
     * connection active, but the connection can be lossy.
     * Holding a {@link #WIFI_MODE_FULL_HIGH_PERF} lock for the
     * duration of the voice call will improve the call quality.
     * <p>
     * When there is no support from the hardware, this lock mode
     * will have the same behavior as {@link #WIFI_MODE_FULL}
     */
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;

    /** Anything worse than or equal to this will show 0 bars. */
    private static final int MIN_RSSI = -100;

    /** Anything better than or equal to this will show the max bars. */
    private static final int MAX_RSSI = -55;

    /**
     * Number of RSSI levels used in the framework to initiate
     * {@link #RSSI_CHANGED_ACTION} broadcast
     * @hide
     */
    public static final int RSSI_LEVELS = 5;

    /**
     * Auto settings in the driver. The driver could choose to operate on both
     * 2.4 GHz and 5 GHz or make a dynamic decision on selecting the band.
     * @hide
     */
    public static final int WIFI_FREQUENCY_BAND_AUTO = 0;

    /**
     * Operation on 5 GHz alone
     * @hide
     */
    public static final int WIFI_FREQUENCY_BAND_5GHZ = 1;

    /**
     * Operation on 2.4 GHz alone
     * @hide
     */
    public static final int WIFI_FREQUENCY_BAND_2GHZ = 2;

    /** List of asyncronous notifications
     * @hide
     */
    public static final int DATA_ACTIVITY_NOTIFICATION = 1;

    //Lowest bit indicates data reception and the second lowest
    //bit indicates data transmitted
    /** @hide */
    public static final int DATA_ACTIVITY_NONE         = 0x00;
    /** @hide */
    public static final int DATA_ACTIVITY_IN           = 0x01;
    /** @hide */
    public static final int DATA_ACTIVITY_OUT          = 0x02;
    /** @hide */
    public static final int DATA_ACTIVITY_INOUT        = 0x03;

    /* Maximum number of active locks we allow.
     * This limit was added to prevent apps from creating a ridiculous number
     * of locks and crashing the system by overflowing the global ref table.
     */
    private static final int MAX_ACTIVE_LOCKS = 50;

    /* Number of currently active WifiLocks and MulticastLocks */
    private int mActiveLockCount;

    private Context mContext;
    IWifiManager mService;

    private static final int INVALID_KEY = 0;
    private static int sListenerKey = 1;
    private static final SparseArray sListenerMap = new SparseArray();
    private static final Object sListenerMapLock = new Object();

    private static AsyncChannel sAsyncChannel;
    private static CountDownLatch sConnected;

    private static final Object sThreadRefLock = new Object();
    private static int sThreadRefCount;
    private static HandlerThread sHandlerThread;

    /**
     * Create a new WifiManager instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_SERVICE Context.WIFI_SERVICE}.
     * @param context the application context
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type IWifiManager, which
     * is a system private class.
     */
    public WifiManager(Context context, IWifiManager service) {
        mContext = context;
        mService = service;
        init();
    }

    /**
     * Return a list of all the networks configured in the supplicant.
     * Not all fields of WifiConfiguration are returned. Only the following
     * fields are filled in:
     * <ul>
     * <li>networkId</li>
     * <li>SSID</li>
     * <li>BSSID</li>
     * <li>priority</li>
     * <li>allowedProtocols</li>
     * <li>allowedKeyManagement</li>
     * <li>allowedAuthAlgorithms</li>
     * <li>allowedPairwiseCiphers</li>
     * <li>allowedGroupCiphers</li>
     * </ul>
     * @return a list of network configurations in the form of a list
     * of {@link WifiConfiguration} objects. Upon failure to fetch or
     * when when Wi-Fi is turned off, it can be null.
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        try {
            return mService.getConfiguredNetworks();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Add a new network description to the set of configured networks.
     * The {@code networkId} field of the supplied configuration object
     * is ignored.
     * <p/>
     * The new network will be marked DISABLED by default. To enable it,
     * called {@link #enableNetwork}.
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @return the ID of the newly created network description. This is used in
     *         other operations to specified the network to be acted upon.
     *         Returns {@code -1} on failure.
     */
    public int addNetwork(WifiConfiguration config) {
        if (config == null) {
            return -1;
        }
        config.networkId = -1;
        return addOrUpdateNetwork(config);
    }

    /**
     * Update the network description of an existing configured network.
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object. It may
     *            be sparse, so that only the items that are being changed
     *            are non-<code>null</code>. The {@code networkId} field
     *            must be set to the ID of the existing network being updated.
     * @return Returns the {@code networkId} of the supplied
     *         {@code WifiConfiguration} on success.
     *         <br/>
     *         Returns {@code -1} on failure, including when the {@code networkId}
     *         field of the {@code WifiConfiguration} does not refer to an
     *         existing network.
     */
    public int updateNetwork(WifiConfiguration config) {
        if (config == null || config.networkId < 0) {
            return -1;
        }
        return addOrUpdateNetwork(config);
    }

    /**
     * Internal method for doing the RPC that creates a new network description
     * or updates an existing one.
     *
     * @param config The possibly sparse object containing the variables that
     *         are to set or updated in the network description.
     * @return the ID of the network on success, {@code -1} on failure.
     */
    private int addOrUpdateNetwork(WifiConfiguration config) {
        try {
            return mService.addOrUpdateNetwork(config);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Remove the specified network from the list of configured networks.
     * This may result in the asynchronous delivery of state change
     * events.
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean removeNetwork(int netId) {
        try {
            return mService.removeNetwork(netId);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Allow a previously configured network to be associated with. If
     * <code>disableOthers</code> is true, then all other configured
     * networks are disabled, and an attempt to connect to the selected
     * network is initiated. This may result in the asynchronous delivery
     * of state change events.
     * @param netId the ID of the network in the list of configured networks
     * @param disableOthers if true, disable all other networks. The way to
     * select a particular network to connect to is specify {@code true}
     * for this parameter.
     * @return {@code true} if the operation succeeded
     */
    public boolean enableNetwork(int netId, boolean disableOthers) {
        try {
            return mService.enableNetwork(netId, disableOthers);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Disable a configured network. The specified network will not be
     * a candidate for associating. This may result in the asynchronous
     * delivery of state change events.
     * @param netId the ID of the network as returned by {@link #addNetwork}.
     * @return {@code true} if the operation succeeded
     */
    public boolean disableNetwork(int netId) {
        try {
            return mService.disableNetwork(netId);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Disassociate from the currently active access point. This may result
     * in the asynchronous delivery of state change events.
     * @return {@code true} if the operation succeeded
     */
    public boolean disconnect() {
        try {
            mService.disconnect();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Reconnect to the currently active access point, if we are currently
     * disconnected. This may result in the asynchronous delivery of state
     * change events.
     * @return {@code true} if the operation succeeded
     */
    public boolean reconnect() {
        try {
            mService.reconnect();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Reconnect to the currently active access point, even if we are already
     * connected. This may result in the asynchronous delivery of state
     * change events.
     * @return {@code true} if the operation succeeded
     */
    public boolean reassociate() {
        try {
            mService.reassociate();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Check that the supplicant daemon is responding to requests.
     * @return {@code true} if we were able to communicate with the supplicant and
     * it returned the expected response to the PING message.
     */
    public boolean pingSupplicant() {
        if (mService == null)
            return false;
        try {
            return mService.pingSupplicant();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Request a scan for access points. Returns immediately. The availability
     * of the results is made known later by means of an asynchronous event sent
     * on completion of the scan.
     * @return {@code true} if the operation succeeded, i.e., the scan was initiated
     */
    public boolean startScan() {
        try {
            final WorkSource workSource = null;
            mService.startScan(workSource);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /** @hide */
    public boolean startScan(WorkSource workSource) {
        try {
            mService.startScan(workSource);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Request a batched scan for access points.  To end your requested batched scan,
     * call stopBatchedScan with the same Settings.
     *
     * If there are mulitple requests for batched scans, the more demanding settings will
     * take precidence.
     *
     * @param requested {@link BatchedScanSettings} the scan settings requested.
     * @return false on known error
     * @hide
     */
    public boolean requestBatchedScan(BatchedScanSettings requested) {
        try {
            return mService.requestBatchedScan(requested, new Binder(), null);
        } catch (RemoteException e) { return false; }
    }
    /** @hide */
    public boolean requestBatchedScan(BatchedScanSettings requested, WorkSource workSource) {
        try {
            return mService.requestBatchedScan(requested, new Binder(), workSource);
        } catch (RemoteException e) { return false; }
    }

    /**
     * Check if the Batched Scan feature is supported.
     *
     * @return false if not supported.
     * @hide
     */
    public boolean isBatchedScanSupported() {
        try {
            return mService.isBatchedScanSupported();
        } catch (RemoteException e) { return false; }
    }

    /**
     * End a requested batch scan for this applicaiton.  Note that batched scan may
     * still occur if other apps are using them.
     *
     * @param requested {@link BatchedScanSettings} the scan settings you previously requested
     *        and now wish to stop.  A value of null here will stop all scans requested by the
     *        calling App.
     * @hide
     */
    public void stopBatchedScan(BatchedScanSettings requested) {
        try {
            mService.stopBatchedScan(requested);
        } catch (RemoteException e) {}
    }

    /**
     * Retrieve the latest batched scan result.  This should be called immediately after
     * {@link BATCHED_SCAN_RESULTS_AVAILABLE_ACTION} is received.
     * @hide
     */
    public List<BatchedScanResult> getBatchedScanResults() {
        try {
            return mService.getBatchedScanResults(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Force a re-reading of batched scan results.  This will attempt
     * to read more information from the chip, but will do so at the expense
     * of previous data.  Rate limited to the current scan frequency.
     *
     * pollBatchedScan will always wait 1 period from the start of the batch
     * before trying to read from the chip, so if your #scans/batch == 1 this will
     * have no effect.
     *
     * If you had already waited 1 period before calling, this should have
     * immediate (though async) effect.
     *
     * If you call before that 1 period is up this will set up a timer and fetch
     * results when the 1 period is up.
     *
     * Servicing a pollBatchedScan request (immediate or after timed delay) starts a
     * new batch, so if you were doing 10 scans/batch and called in the 4th scan, you
     * would get data in the 4th and then again 10 scans later.
     * @hide
     */
    public void pollBatchedScan() {
        try {
            mService.pollBatchedScan();
        } catch (RemoteException e) { }
    }

    /**
     * Return dynamic information about the current Wi-Fi connection, if any is active.
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    public WifiInfo getConnectionInfo() {
        try {
            return mService.getConnectionInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Return the results of the latest access point scan.
     * @return the list of access points found in the most recent scan.
     */
    public List<ScanResult> getScanResults() {
        try {
            return mService.getScanResults(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Check if scanning is always available.
     *
     * If this return {@code true}, apps can issue {@link #startScan} and fetch scan results
     * even when Wi-Fi is turned off.
     *
     * To change this setting, see {@link #ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE}.
     */
    public boolean isScanAlwaysAvailable() {
        try {
            return mService.isScanAlwaysAvailable();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Tell the supplicant to persist the current list of configured networks.
     * <p>
     * Note: It is possible for this method to change the network IDs of
     * existing networks. You should assume the network IDs can be different
     * after calling this method.
     *
     * @return {@code true} if the operation succeeded
     */
    public boolean saveConfiguration() {
        try {
            return mService.saveConfiguration();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Set the country code.
     * @param countryCode country code in ISO 3166 format.
     * @param persist {@code true} if this needs to be remembered
     *
     * @hide
     */
    public void setCountryCode(String country, boolean persist) {
        try {
            mService.setCountryCode(country, persist);
        } catch (RemoteException e) { }
    }

    /**
     * Set the operational frequency band.
     * @param band  One of
     *     {@link #WIFI_FREQUENCY_BAND_AUTO},
     *     {@link #WIFI_FREQUENCY_BAND_5GHZ},
     *     {@link #WIFI_FREQUENCY_BAND_2GHZ},
     * @param persist {@code true} if this needs to be remembered
     * @hide
     */
    public void setFrequencyBand(int band, boolean persist) {
        try {
            mService.setFrequencyBand(band, persist);
        } catch (RemoteException e) { }
    }

    /**
     * Get the operational frequency band.
     * @return One of
     *     {@link #WIFI_FREQUENCY_BAND_AUTO},
     *     {@link #WIFI_FREQUENCY_BAND_5GHZ},
     *     {@link #WIFI_FREQUENCY_BAND_2GHZ} or
     *     {@code -1} on failure.
     * @hide
     */
    public int getFrequencyBand() {
        try {
            return mService.getFrequencyBand();
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Check if the chipset supports dual frequency band (2.4 GHz and 5 GHz)
     * @return {@code true} if supported, {@code false} otherwise.
     * @hide
     */
    public boolean isDualBandSupported() {
        try {
            return mService.isDualBandSupported();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     */
    public DhcpInfo getDhcpInfo() {
        try {
            return mService.getDhcpInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Enable or disable Wi-Fi.
     * @param enabled {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the operation succeeds (or if the existing state
     *         is the same as the requested state).
     */
    public boolean setWifiEnabled(boolean enabled) {
        try {
            return mService.setWifiEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the Wi-Fi enabled state.
     * @return One of {@link #WIFI_STATE_DISABLED},
     *         {@link #WIFI_STATE_DISABLING}, {@link #WIFI_STATE_ENABLED},
     *         {@link #WIFI_STATE_ENABLING}, {@link #WIFI_STATE_UNKNOWN}
     * @see #isWifiEnabled()
     */
    public int getWifiState() {
        try {
            return mService.getWifiEnabledState();
        } catch (RemoteException e) {
            return WIFI_STATE_UNKNOWN;
        }
    }

    /**
     * Return whether Wi-Fi is enabled or disabled.
     * @return {@code true} if Wi-Fi is enabled
     * @see #getWifiState()
     */
    public boolean isWifiEnabled() {
        return getWifiState() == WIFI_STATE_ENABLED;
    }

    /**
     * Return TX packet counter, for CTS test of WiFi watchdog.
     * @param listener is the interface to receive result
     *
     * @hide for CTS test only
     */
    public void getTxPacketCount(TxPacketCountListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(RSSI_PKTCNT_FETCH, 0, putListener(listener));
    }

    /**
     * Calculates the level of the signal. This should be used any time a signal
     * is being shown.
     *
     * @param rssi The power of the signal measured in RSSI.
     * @param numLevels The number of levels to consider in the calculated
     *            level.
     * @return A level of the signal, given in the range of 0 to numLevels-1
     *         (both inclusive).
     */
    public static int calculateSignalLevel(int rssi, int numLevels) {
        if (rssi <= MIN_RSSI) {
            return 0;
        } else if (rssi >= MAX_RSSI) {
            return numLevels - 1;
        } else {
            float inputRange = (MAX_RSSI - MIN_RSSI);
            float outputRange = (numLevels - 1);
            return (int)((float)(rssi - MIN_RSSI) * outputRange / inputRange);
        }
    }

    /**
     * Compares two signal strengths.
     *
     * @param rssiA The power of the first signal measured in RSSI.
     * @param rssiB The power of the second signal measured in RSSI.
     * @return Returns <0 if the first signal is weaker than the second signal,
     *         0 if the two signals have the same strength, and >0 if the first
     *         signal is stronger than the second signal.
     */
    public static int compareSignalLevel(int rssiA, int rssiB) {
        return rssiA - rssiB;
    }

    /**
     * Start AccessPoint mode with the specified
     * configuration. If the radio is already running in
     * AP mode, update the new configuration
     * Note that starting in access point mode disables station
     * mode operation
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * @hide Dont open up yet
     */
    public boolean setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        try {
            mService.setWifiApEnabled(wifiConfig, enabled);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the Wi-Fi enabled state.
     * @return One of {@link #WIFI_AP_STATE_DISABLED},
     *         {@link #WIFI_AP_STATE_DISABLING}, {@link #WIFI_AP_STATE_ENABLED},
     *         {@link #WIFI_AP_STATE_ENABLING}, {@link #WIFI_AP_STATE_FAILED}
     * @see #isWifiApEnabled()
     *
     * @hide Dont open yet
     */
    public int getWifiApState() {
        try {
            return mService.getWifiApEnabledState();
        } catch (RemoteException e) {
            return WIFI_AP_STATE_FAILED;
        }
    }

    /**
     * Return whether Wi-Fi AP is enabled or disabled.
     * @return {@code true} if Wi-Fi AP is enabled
     * @see #getWifiApState()
     *
     * @hide Dont open yet
     */
    public boolean isWifiApEnabled() {
        return getWifiApState() == WIFI_AP_STATE_ENABLED;
    }

    /**
     * Gets the Wi-Fi AP Configuration.
     * @return AP details in WifiConfiguration
     *
     * @hide Dont open yet
     */
    public WifiConfiguration getWifiApConfiguration() {
        try {
            return mService.getWifiApConfiguration();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Sets the Wi-Fi AP Configuration.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * @hide Dont open yet
     */
    public boolean setWifiApConfiguration(WifiConfiguration wifiConfig) {
        try {
            mService.setWifiApConfiguration(wifiConfig);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

   /**
     * Start the driver and connect to network.
     *
     * This function will over-ride WifiLock and device idle status. For example,
     * even if the device is idle or there is only a scan-only lock held,
     * a start wifi would mean that wifi connection is kept active until
     * a stopWifi() is sent.
     *
     * This API is used by WifiStateTracker
     *
     * @return {@code true} if the operation succeeds else {@code false}
     * @hide
     */
    public boolean startWifi() {
        try {
            mService.startWifi();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Disconnect from a network (if any) and stop the driver.
     *
     * This function will over-ride WifiLock and device idle status. Wi-Fi
     * stays inactive until a startWifi() is issued.
     *
     * This API is used by WifiStateTracker
     *
     * @return {@code true} if the operation succeeds else {@code false}
     * @hide
     */
    public boolean stopWifi() {
        try {
            mService.stopWifi();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Add a bssid to the supplicant blacklist
     *
     * This API is used by WifiWatchdogService
     *
     * @return {@code true} if the operation succeeds else {@code false}
     * @hide
     */
    public boolean addToBlacklist(String bssid) {
        try {
            mService.addToBlacklist(bssid);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Clear the supplicant blacklist
     *
     * This API is used by WifiWatchdogService
     *
     * @return {@code true} if the operation succeeds else {@code false}
     * @hide
     */
    public boolean clearBlacklist() {
        try {
            mService.clearBlacklist();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }


    /**
     * Enable/Disable TDLS on a specific local route.
     *
     * <p>
     * TDLS enables two wireless endpoints to talk to each other directly
     * without going through the access point that is managing the local
     * network. It saves bandwidth and improves quality of the link.
     * </p>
     * <p>
     * This API enables/disables the option of using TDLS. If enabled, the
     * underlying hardware is free to use TDLS or a hop through the access
     * point. If disabled, existing TDLS session is torn down and
     * hardware is restricted to use access point for transferring wireless
     * packets. Default value for all routes is 'disabled', meaning restricted
     * to use access point for transferring packets.
     * </p>
     *
     * @param remoteIPAddress IP address of the endpoint to setup TDLS with
     * @param enable true = setup and false = tear down TDLS
     */
    public void setTdlsEnabled(InetAddress remoteIPAddress, boolean enable) {
        try {
            mService.enableTdls(remoteIPAddress.getHostAddress(), enable);
        } catch (RemoteException e) {
            // Just ignore the exception
        }
    }

    /**
     * Similar to {@link #setTdlsEnabled(InetAddress, boolean) }, except
     * this version allows you to specify remote endpoint with a MAC address.
     * @param remoteMacAddress MAC address of the remote endpoint such as 00:00:0c:9f:f2:ab
     * @param enable true = setup and false = tear down TDLS
     */
    public void setTdlsEnabledWithMacAddress(String remoteMacAddress, boolean enable) {
        try {
            mService.enableTdlsWithMacAddress(remoteMacAddress, enable);
        } catch (RemoteException e) {
            // Just ignore the exception
        }
    }

    /* TODO: deprecate synchronous API and open up the following API */

    private static final int BASE = Protocol.BASE_WIFI_MANAGER;

    /* Commands to WifiService */
    /** @hide */
    public static final int CONNECT_NETWORK                 = BASE + 1;
    /** @hide */
    public static final int CONNECT_NETWORK_FAILED          = BASE + 2;
    /** @hide */
    public static final int CONNECT_NETWORK_SUCCEEDED       = BASE + 3;

    /** @hide */
    public static final int FORGET_NETWORK                  = BASE + 4;
    /** @hide */
    public static final int FORGET_NETWORK_FAILED           = BASE + 5;
    /** @hide */
    public static final int FORGET_NETWORK_SUCCEEDED        = BASE + 6;

    /** @hide */
    public static final int SAVE_NETWORK                    = BASE + 7;
    /** @hide */
    public static final int SAVE_NETWORK_FAILED             = BASE + 8;
    /** @hide */
    public static final int SAVE_NETWORK_SUCCEEDED          = BASE + 9;

    /** @hide */
    public static final int START_WPS                       = BASE + 10;
    /** @hide */
    public static final int START_WPS_SUCCEEDED             = BASE + 11;
    /** @hide */
    public static final int WPS_FAILED                      = BASE + 12;
    /** @hide */
    public static final int WPS_COMPLETED                   = BASE + 13;

    /** @hide */
    public static final int CANCEL_WPS                      = BASE + 14;
    /** @hide */
    public static final int CANCEL_WPS_FAILED               = BASE + 15;
    /** @hide */
    public static final int CANCEL_WPS_SUCCEDED             = BASE + 16;

    /** @hide */
    public static final int DISABLE_NETWORK                 = BASE + 17;
    /** @hide */
    public static final int DISABLE_NETWORK_FAILED          = BASE + 18;
    /** @hide */
    public static final int DISABLE_NETWORK_SUCCEEDED       = BASE + 19;

    /** @hide */
    public static final int RSSI_PKTCNT_FETCH               = BASE + 20;
    /** @hide */
    public static final int RSSI_PKTCNT_FETCH_SUCCEEDED     = BASE + 21;
    /** @hide */
    public static final int RSSI_PKTCNT_FETCH_FAILED        = BASE + 22;


    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to an internal error.
     * @hide
     */
    public static final int ERROR                       = 0;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation is already in progress
     * @hide
     */
    public static final int IN_PROGRESS                 = 1;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed because the framework is busy and
     * unable to service the request
     * @hide
     */
    public static final int BUSY                        = 2;

    /* WPS specific errors */
    /** WPS overlap detected {@hide} */
    public static final int WPS_OVERLAP_ERROR           = 3;
    /** WEP on WPS is prohibited {@hide} */
    public static final int WPS_WEP_PROHIBITED          = 4;
    /** TKIP only prohibited {@hide} */
    public static final int WPS_TKIP_ONLY_PROHIBITED    = 5;
    /** Authentication failure on WPS {@hide} */
    public static final int WPS_AUTH_FAILURE            = 6;
    /** WPS timed out {@hide} */
    public static final int WPS_TIMED_OUT               = 7;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to invalid inputs
     * @hide
     */
    public static final int INVALID_ARGS                = 8;

    /** Interface for callback invocation on an application action {@hide} */
    public interface ActionListener {
        /** The operation succeeded */
        public void onSuccess();
        /**
         * The operation failed
         * @param reason The reason for failure could be one of
         * {@link #ERROR}, {@link #IN_PROGRESS} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    /** Interface for callback invocation on a start WPS action {@hide} */
    public interface WpsListener {
        /** WPS start succeeded */
        public void onStartSuccess(String pin);

        /** WPS operation completed succesfully */
        public void onCompletion();

        /**
         * WPS operation failed
         * @param reason The reason for failure could be one of
         * {@link #IN_PROGRESS}, {@link #WPS_OVERLAP_ERROR},{@link #ERROR} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    /** Interface for callback invocation on a TX packet count poll action {@hide} */
    public interface TxPacketCountListener {
        /**
         * The operation succeeded
         * @param count TX packet counter
         */
        public void onSuccess(int count);
        /**
         * The operation failed
         * @param reason The reason for failure could be one of
         * {@link #ERROR}, {@link #IN_PROGRESS} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    private static class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            Object listener = removeListener(message.arg2);
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        sAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    } else {
                        Log.e(TAG, "Failed to set up channel connection");
                        // This will cause all further async API calls on the WifiManager
                        // to fail and throw an exception
                        sAsyncChannel = null;
                    }
                    sConnected.countDown();
                    break;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    // Ignore
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel connection lost");
                    // This will cause all further async API calls on the WifiManager
                    // to fail and throw an exception
                    sAsyncChannel = null;
                    getLooper().quit();
                    break;
                    /* ActionListeners grouped together */
                case WifiManager.CONNECT_NETWORK_FAILED:
                case WifiManager.FORGET_NETWORK_FAILED:
                case WifiManager.SAVE_NETWORK_FAILED:
                case WifiManager.CANCEL_WPS_FAILED:
                case WifiManager.DISABLE_NETWORK_FAILED:
                    if (listener != null) {
                        ((ActionListener) listener).onFailure(message.arg1);
                    }
                    break;
                    /* ActionListeners grouped together */
                case WifiManager.CONNECT_NETWORK_SUCCEEDED:
                case WifiManager.FORGET_NETWORK_SUCCEEDED:
                case WifiManager.SAVE_NETWORK_SUCCEEDED:
                case WifiManager.CANCEL_WPS_SUCCEDED:
                case WifiManager.DISABLE_NETWORK_SUCCEEDED:
                    if (listener != null) {
                        ((ActionListener) listener).onSuccess();
                    }
                    break;
                case WifiManager.START_WPS_SUCCEEDED:
                    if (listener != null) {
                        WpsResult result = (WpsResult) message.obj;
                        ((WpsListener) listener).onStartSuccess(result.pin);
                        //Listener needs to stay until completion or failure
                        synchronized(sListenerMapLock) {
                            sListenerMap.put(message.arg2, listener);
                        }
                    }
                    break;
                case WifiManager.WPS_COMPLETED:
                    if (listener != null) {
                        ((WpsListener) listener).onCompletion();
                    }
                    break;
                case WifiManager.WPS_FAILED:
                    if (listener != null) {
                        ((WpsListener) listener).onFailure(message.arg1);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH_SUCCEEDED:
                    if (listener != null) {
                        RssiPacketCountInfo info = (RssiPacketCountInfo) message.obj;
                        if (info != null)
                            ((TxPacketCountListener) listener).onSuccess(info.txgood + info.txbad);
                        else
                            ((TxPacketCountListener) listener).onFailure(ERROR);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH_FAILED:
                    if (listener != null) {
                        ((TxPacketCountListener) listener).onFailure(message.arg1);
                    }
                    break;
                default:
                    //ignore
                    break;
            }
        }
    }

    private static int putListener(Object listener) {
        if (listener == null) return INVALID_KEY;
        int key;
        synchronized (sListenerMapLock) {
            do {
                key = sListenerKey++;
            } while (key == INVALID_KEY);
            sListenerMap.put(key, listener);
        }
        return key;
    }

    private static Object removeListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (sListenerMapLock) {
            Object listener = sListenerMap.get(key);
            sListenerMap.remove(key);
            return listener;
        }
    }

    private void init() {
        synchronized (sThreadRefLock) {
            if (++sThreadRefCount == 1) {
                Messenger messenger = getWifiServiceMessenger();
                if (messenger == null) {
                    sAsyncChannel = null;
                    return;
                }

                sHandlerThread = new HandlerThread("WifiManager");
                sAsyncChannel = new AsyncChannel();
                sConnected = new CountDownLatch(1);

                sHandlerThread.start();
                Handler handler = new ServiceHandler(sHandlerThread.getLooper());
                sAsyncChannel.connect(mContext, handler, messenger);
                try {
                    sConnected.await();
                } catch (InterruptedException e) {
                    Log.e(TAG, "interrupted wait at init");
                }
            }
        }
    }

    private void validateChannel() {
        if (sAsyncChannel == null) throw new IllegalStateException(
                "No permission to access and change wifi or a bad initialization");
    }

    /**
     * Connect to a network with the given configuration. The network also
     * gets added to the supplicant configuration.
     *
     * For a new network, this function is used instead of a
     * sequence of addNetwork(), enableNetwork(), saveConfiguration() and
     * reconnect()
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     *
     * @hide
     */
    public void connect(WifiConfiguration config, ActionListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        validateChannel();
        // Use INVALID_NETWORK_ID for arg1 when passing a config object
        // arg1 is used to pass network id when the network already exists
        sAsyncChannel.sendMessage(CONNECT_NETWORK, WifiConfiguration.INVALID_NETWORK_ID,
                putListener(listener), config);
    }

    /**
     * Connect to a network with the given networkId.
     *
     * This function is used instead of a enableNetwork(), saveConfiguration() and
     * reconnect()
     *
     * @param networkId the network id identifiying the network in the
     *                supplicant configuration list
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void connect(int networkId, ActionListener listener) {
        if (networkId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        validateChannel();
        sAsyncChannel.sendMessage(CONNECT_NETWORK, networkId, putListener(listener));
    }

    /**
     * Save the given network in the supplicant config. If the network already
     * exists, the configuration is updated. A new network is enabled
     * by default.
     *
     * For a new network, this function is used instead of a
     * sequence of addNetwork(), enableNetwork() and saveConfiguration().
     *
     * For an existing network, it accomplishes the task of updateNetwork()
     * and saveConfiguration()
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void save(WifiConfiguration config, ActionListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        validateChannel();
        sAsyncChannel.sendMessage(SAVE_NETWORK, 0, putListener(listener), config);
    }

    /**
     * Delete the network in the supplicant config.
     *
     * This function is used instead of a sequence of removeNetwork()
     * and saveConfiguration().
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void forget(int netId, ActionListener listener) {
        if (netId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        validateChannel();
        sAsyncChannel.sendMessage(FORGET_NETWORK, netId, putListener(listener));
    }

    /**
     * Disable network
     *
     * @param netId is the network Id
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void disable(int netId, ActionListener listener) {
        if (netId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        validateChannel();
        sAsyncChannel.sendMessage(DISABLE_NETWORK, netId, putListener(listener));
    }

    /**
     * Start Wi-fi Protected Setup
     *
     * @param config WPS configuration
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void startWps(WpsInfo config, WpsListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        validateChannel();
        sAsyncChannel.sendMessage(START_WPS, 0, putListener(listener), config);
    }

    /**
     * Cancel any ongoing Wi-fi Protected Setup
     *
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    public void cancelWps(ActionListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CANCEL_WPS, 0, putListener(listener));
    }

    /**
     * Get a reference to WifiService handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     *
     * @return Messenger pointing to the WifiService handler
     * @hide
     */
    public Messenger getWifiServiceMessenger() {
        try {
            return mService.getWifiServiceMessenger();
        } catch (RemoteException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * Get a reference to WifiStateMachine handler.
     * @return Messenger pointing to the WifiService handler
     * @hide
     */
    public Messenger getWifiStateMachineMessenger() {
        try {
            return mService.getWifiStateMachineMessenger();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns the file in which IP and proxy configuration data is stored
     * @hide
     */
    public String getConfigFile() {
        try {
            return mService.getConfigFile();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Allows an application to keep the Wi-Fi radio awake.
     * Normally the Wi-Fi radio may turn off when the user has not used the device in a while.
     * Acquiring a WifiLock will keep the radio on until the lock is released.  Multiple
     * applications may hold WifiLocks, and the radio will only be allowed to turn off when no
     * WifiLocks are held in any application.
     * <p>
     * Before using a WifiLock, consider carefully if your application requires Wi-Fi access, or
     * could function over a mobile network, if available.  A program that needs to download large
     * files should hold a WifiLock to ensure that the download will complete, but a program whose
     * network usage is occasional or low-bandwidth should not hold a WifiLock to avoid adversely
     * affecting battery life.
     * <p>
     * Note that WifiLocks cannot override the user-level "Wi-Fi Enabled" setting, nor Airplane
     * Mode.  They simply keep the radio from turning off when Wi-Fi is already on but the device
     * is idle.
     * <p>
     * Any application using a WifiLock must request the {@code android.permission.WAKE_LOCK}
     * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
     */
    public class WifiLock {
        private String mTag;
        private final IBinder mBinder;
        private int mRefCount;
        int mLockType;
        private boolean mRefCounted;
        private boolean mHeld;
        private WorkSource mWorkSource;

        private WifiLock(int lockType, String tag) {
            mTag = tag;
            mLockType = lockType;
            mBinder = new Binder();
            mRefCount = 0;
            mRefCounted = true;
            mHeld = false;
        }

        /**
         * Locks the Wi-Fi radio on until {@link #release} is called.
         *
         * If this WifiLock is reference-counted, each call to {@code acquire} will increment the
         * reference count, and the radio will remain locked as long as the reference count is
         * above zero.
         *
         * If this WifiLock is not reference-counted, the first call to {@code acquire} will lock
         * the radio, but subsequent calls will be ignored.  Only one call to {@link #release}
         * will be required, regardless of the number of times that {@code acquire} is called.
         */
        public void acquire() {
            synchronized (mBinder) {
                if (mRefCounted ? (++mRefCount == 1) : (!mHeld)) {
                    try {
                        mService.acquireWifiLock(mBinder, mLockType, mTag, mWorkSource);
                        synchronized (WifiManager.this) {
                            if (mActiveLockCount >= MAX_ACTIVE_LOCKS) {
                                mService.releaseWifiLock(mBinder);
                                throw new UnsupportedOperationException(
                                            "Exceeded maximum number of wifi locks");
                            }
                            mActiveLockCount++;
                        }
                    } catch (RemoteException ignore) {
                    }
                    mHeld = true;
                }
            }
        }

        /**
         * Unlocks the Wi-Fi radio, allowing it to turn off when the device is idle.
         *
         * If this WifiLock is reference-counted, each call to {@code release} will decrement the
         * reference count, and the radio will be unlocked only when the reference count reaches
         * zero.  If the reference count goes below zero (that is, if {@code release} is called
         * a greater number of times than {@link #acquire}), an exception is thrown.
         *
         * If this WifiLock is not reference-counted, the first call to {@code release} (after
         * the radio was locked using {@link #acquire}) will unlock the radio, and subsequent
         * calls will be ignored.
         */
        public void release() {
            synchronized (mBinder) {
                if (mRefCounted ? (--mRefCount == 0) : (mHeld)) {
                    try {
                        mService.releaseWifiLock(mBinder);
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException ignore) {
                    }
                    mHeld = false;
                }
                if (mRefCount < 0) {
                    throw new RuntimeException("WifiLock under-locked " + mTag);
                }
            }
        }

        /**
         * Controls whether this is a reference-counted or non-reference-counted WifiLock.
         *
         * Reference-counted WifiLocks keep track of the number of calls to {@link #acquire} and
         * {@link #release}, and only allow the radio to sleep when every call to {@link #acquire}
         * has been balanced with a call to {@link #release}.  Non-reference-counted WifiLocks
         * lock the radio whenever {@link #acquire} is called and it is unlocked, and unlock the
         * radio whenever {@link #release} is called and it is locked.
         *
         * @param refCounted true if this WifiLock should keep a reference count
         */
        public void setReferenceCounted(boolean refCounted) {
            mRefCounted = refCounted;
        }

        /**
         * Checks whether this WifiLock is currently held.
         *
         * @return true if this WifiLock is held, false otherwise
         */
        public boolean isHeld() {
            synchronized (mBinder) {
                return mHeld;
            }
        }

        public void setWorkSource(WorkSource ws) {
            synchronized (mBinder) {
                if (ws != null && ws.size() == 0) {
                    ws = null;
                }
                boolean changed = true;
                if (ws == null) {
                    mWorkSource = null;
                } else {
                    ws.clearNames();
                    if (mWorkSource == null) {
                        changed = mWorkSource != null;
                        mWorkSource = new WorkSource(ws);
                    } else {
                        changed = mWorkSource.diff(ws);
                        if (changed) {
                            mWorkSource.set(ws);
                        }
                    }
                }
                if (changed && mHeld) {
                    try {
                        mService.updateWifiLockWorkSource(mBinder, mWorkSource);
                    } catch (RemoteException e) {
                    }
                }
            }
        }

        public String toString() {
            String s1, s2, s3;
            synchronized (mBinder) {
                s1 = Integer.toHexString(System.identityHashCode(this));
                s2 = mHeld ? "held; " : "";
                if (mRefCounted) {
                    s3 = "refcounted: refcount = " + mRefCount;
                } else {
                    s3 = "not refcounted";
                }
                return "WifiLock{ " + s1 + "; " + s2 + s3 + " }";
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            synchronized (mBinder) {
                if (mHeld) {
                    try {
                        mService.releaseWifiLock(mBinder);
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException ignore) {
                    }
                }
            }
        }
    }

    /**
     * Creates a new WifiLock.
     *
     * @param lockType the type of lock to create. See {@link #WIFI_MODE_FULL},
     * {@link #WIFI_MODE_FULL_HIGH_PERF} and {@link #WIFI_MODE_SCAN_ONLY} for
     * descriptions of the types of Wi-Fi locks.
     * @param tag a tag for the WifiLock to identify it in debugging messages.  This string is
     *            never shown to the user under normal conditions, but should be descriptive
     *            enough to identify your application and the specific WifiLock within it, if it
     *            holds multiple WifiLocks.
     *
     * @return a new, unacquired WifiLock with the given tag.
     *
     * @see WifiLock
     */
    public WifiLock createWifiLock(int lockType, String tag) {
        return new WifiLock(lockType, tag);
    }

    /**
     * Creates a new WifiLock.
     *
     * @param tag a tag for the WifiLock to identify it in debugging messages.  This string is
     *            never shown to the user under normal conditions, but should be descriptive
     *            enough to identify your application and the specific WifiLock within it, if it
     *            holds multiple WifiLocks.
     *
     * @return a new, unacquired WifiLock with the given tag.
     *
     * @see WifiLock
     */
    public WifiLock createWifiLock(String tag) {
        return new WifiLock(WIFI_MODE_FULL, tag);
    }


    /**
     * Create a new MulticastLock
     *
     * @param tag a tag for the MulticastLock to identify it in debugging
     *            messages.  This string is never shown to the user under
     *            normal conditions, but should be descriptive enough to
     *            identify your application and the specific MulticastLock
     *            within it, if it holds multiple MulticastLocks.
     *
     * @return a new, unacquired MulticastLock with the given tag.
     *
     * @see MulticastLock
     */
    public MulticastLock createMulticastLock(String tag) {
        return new MulticastLock(tag);
    }

    /**
     * Allows an application to receive Wifi Multicast packets.
     * Normally the Wifi stack filters out packets not explicitly
     * addressed to this device.  Acquring a MulticastLock will
     * cause the stack to receive packets addressed to multicast
     * addresses.  Processing these extra packets can cause a noticable
     * battery drain and should be disabled when not needed.
     */
    public class MulticastLock {
        private String mTag;
        private final IBinder mBinder;
        private int mRefCount;
        private boolean mRefCounted;
        private boolean mHeld;

        private MulticastLock(String tag) {
            mTag = tag;
            mBinder = new Binder();
            mRefCount = 0;
            mRefCounted = true;
            mHeld = false;
        }

        /**
         * Locks Wifi Multicast on until {@link #release} is called.
         *
         * If this MulticastLock is reference-counted each call to
         * {@code acquire} will increment the reference count, and the
         * wifi interface will receive multicast packets as long as the
         * reference count is above zero.
         *
         * If this MulticastLock is not reference-counted, the first call to
         * {@code acquire} will turn on the multicast packets, but subsequent
         * calls will be ignored.  Only one call to {@link #release} will
         * be required, regardless of the number of times that {@code acquire}
         * is called.
         *
         * Note that other applications may also lock Wifi Multicast on.
         * Only they can relinquish their lock.
         *
         * Also note that applications cannot leave Multicast locked on.
         * When an app exits or crashes, any Multicast locks will be released.
         */
        public void acquire() {
            synchronized (mBinder) {
                if (mRefCounted ? (++mRefCount == 1) : (!mHeld)) {
                    try {
                        mService.acquireMulticastLock(mBinder, mTag);
                        synchronized (WifiManager.this) {
                            if (mActiveLockCount >= MAX_ACTIVE_LOCKS) {
                                mService.releaseMulticastLock();
                                throw new UnsupportedOperationException(
                                        "Exceeded maximum number of wifi locks");
                            }
                            mActiveLockCount++;
                        }
                    } catch (RemoteException ignore) {
                    }
                    mHeld = true;
                }
            }
        }

        /**
         * Unlocks Wifi Multicast, restoring the filter of packets
         * not addressed specifically to this device and saving power.
         *
         * If this MulticastLock is reference-counted, each call to
         * {@code release} will decrement the reference count, and the
         * multicast packets will only stop being received when the reference
         * count reaches zero.  If the reference count goes below zero (that
         * is, if {@code release} is called a greater number of times than
         * {@link #acquire}), an exception is thrown.
         *
         * If this MulticastLock is not reference-counted, the first call to
         * {@code release} (after the radio was multicast locked using
         * {@link #acquire}) will unlock the multicast, and subsequent calls
         * will be ignored.
         *
         * Note that if any other Wifi Multicast Locks are still outstanding
         * this {@code release} call will not have an immediate effect.  Only
         * when all applications have released all their Multicast Locks will
         * the Multicast filter be turned back on.
         *
         * Also note that when an app exits or crashes all of its Multicast
         * Locks will be automatically released.
         */
        public void release() {
            synchronized (mBinder) {
                if (mRefCounted ? (--mRefCount == 0) : (mHeld)) {
                    try {
                        mService.releaseMulticastLock();
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException ignore) {
                    }
                    mHeld = false;
                }
                if (mRefCount < 0) {
                    throw new RuntimeException("MulticastLock under-locked "
                            + mTag);
                }
            }
        }

        /**
         * Controls whether this is a reference-counted or non-reference-
         * counted MulticastLock.
         *
         * Reference-counted MulticastLocks keep track of the number of calls
         * to {@link #acquire} and {@link #release}, and only stop the
         * reception of multicast packets when every call to {@link #acquire}
         * has been balanced with a call to {@link #release}.  Non-reference-
         * counted MulticastLocks allow the reception of multicast packets
         * whenever {@link #acquire} is called and stop accepting multicast
         * packets whenever {@link #release} is called.
         *
         * @param refCounted true if this MulticastLock should keep a reference
         * count
         */
        public void setReferenceCounted(boolean refCounted) {
            mRefCounted = refCounted;
        }

        /**
         * Checks whether this MulticastLock is currently held.
         *
         * @return true if this MulticastLock is held, false otherwise
         */
        public boolean isHeld() {
            synchronized (mBinder) {
                return mHeld;
            }
        }

        public String toString() {
            String s1, s2, s3;
            synchronized (mBinder) {
                s1 = Integer.toHexString(System.identityHashCode(this));
                s2 = mHeld ? "held; " : "";
                if (mRefCounted) {
                    s3 = "refcounted: refcount = " + mRefCount;
                } else {
                    s3 = "not refcounted";
                }
                return "MulticastLock{ " + s1 + "; " + s2 + s3 + " }";
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            setReferenceCounted(false);
            release();
        }
    }

    /**
     * Check multicast filter status.
     *
     * @return true if multicast packets are allowed.
     *
     * @hide pending API council approval
     */
    public boolean isMulticastEnabled() {
        try {
            return mService.isMulticastEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Initialize the multicast filtering to 'on'
     * @hide no intent to publish
     */
    public boolean initializeMulticastFiltering() {
        try {
            mService.initializeMulticastFiltering();
            return true;
        } catch (RemoteException e) {
             return false;
        }
    }

    /** @hide */
    public void captivePortalCheckComplete() {
        try {
            mService.captivePortalCheckComplete();
        } catch (RemoteException e) {}
    }

    protected void finalize() throws Throwable {
        try {
            synchronized (sThreadRefLock) {
                if (--sThreadRefCount == 0 && sAsyncChannel != null) {
                    sAsyncChannel.disconnect();
                }
            }
        } finally {
            super.finalize();
        }
    }
}
