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

package android.net;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.os.Binder;
import android.os.Build.VERSION_CODES;
import android.os.RemoteException;
import android.provider.Settings;

import java.net.InetAddress;

/**
 * Class that answers queries about the state of network connectivity. It also
 * notifies applications when network connectivity changes. Get an instance
 * of this class by calling
 * {@link android.content.Context#getSystemService(String) Context.getSystemService(Context.CONNECTIVITY_SERVICE)}.
 * <p>
 * The primary responsibilities of this class are to:
 * <ol>
 * <li>Monitor network connections (Wi-Fi, GPRS, UMTS, etc.)</li>
 * <li>Send broadcast intents when network connectivity changes</li>
 * <li>Attempt to "fail over" to another network when connectivity to a network
 * is lost</li>
 * <li>Provide an API that allows applications to query the coarse-grained or fine-grained
 * state of the available networks</li>
 * </ol>
 */
public class ConnectivityManager {
    private static final String TAG = "ConnectivityManager";

    /**
     * A change in network connectivity has occurred. A connection has either
     * been established or lost. The NetworkInfo for the affected network is
     * sent as an extra; it should be consulted to see what kind of
     * connectivity event occurred.
     * <p/>
     * If this is a connection that was the result of failing over from a
     * disconnected network, then the FAILOVER_CONNECTION boolean extra is
     * set to true.
     * <p/>
     * For a loss of connectivity, if the connectivity manager is attempting
     * to connect (or has already connected) to another network, the
     * NetworkInfo for the new network is also passed as an extra. This lets
     * any receivers of the broadcast know that they should not necessarily
     * tell the user that no data traffic will be possible. Instead, the
     * reciever should expect another broadcast soon, indicating either that
     * the failover attempt succeeded (and so there is still overall data
     * connectivity), or that the failover attempt failed, meaning that all
     * connectivity has been lost.
     * <p/>
     * For a disconnect event, the boolean extra EXTRA_NO_CONNECTIVITY
     * is set to {@code true} if there are no connected networks at all.
     */
    public static final String CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";

    /**
     * Identical to {@link #CONNECTIVITY_ACTION} broadcast, but sent without any
     * applicable {@link Settings.Secure#CONNECTIVITY_CHANGE_DELAY}.
     *
     * @hide
     */
    public static final String CONNECTIVITY_ACTION_IMMEDIATE =
            "android.net.conn.CONNECTIVITY_CHANGE_IMMEDIATE";

    /**
     * The lookup key for a {@link NetworkInfo} object. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     *
     * @deprecated Since {@link NetworkInfo} can vary based on UID, applications
     *             should always obtain network information through
     *             {@link #getActiveNetworkInfo()} or
     *             {@link #getAllNetworkInfo()}.
     */
    @Deprecated
    public static final String EXTRA_NETWORK_INFO = "networkInfo";

    /**
     * The lookup key for a boolean that indicates whether a connect event
     * is for a network to which the connectivity manager was failing over
     * following a disconnect on another network.
     * Retrieve it with {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     */
    public static final String EXTRA_IS_FAILOVER = "isFailover";
    /**
     * The lookup key for a {@link NetworkInfo} object. This is supplied when
     * there is another network that it may be possible to connect to. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_OTHER_NETWORK_INFO = "otherNetwork";
    /**
     * The lookup key for a boolean that indicates whether there is a
     * complete lack of connectivity, i.e., no network is available.
     * Retrieve it with {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     */
    public static final String EXTRA_NO_CONNECTIVITY = "noConnectivity";
    /**
     * The lookup key for a string that indicates why an attempt to connect
     * to a network failed. The string has no particular structure. It is
     * intended to be used in notifications presented to users. Retrieve
     * it with {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_REASON = "reason";
    /**
     * The lookup key for a string that provides optionally supplied
     * extra information about the network state. The information
     * may be passed up from the lower networking layers, and its
     * meaning may be specific to a particular network type. Retrieve
     * it with {@link android.content.Intent#getStringExtra(String)}.
     */
    public static final String EXTRA_EXTRA_INFO = "extraInfo";
    /**
     * The lookup key for an int that provides information about
     * our connection to the internet at large.  0 indicates no connection,
     * 100 indicates a great connection.  Retrieve it with
     * {@link android.content.Intent#getIntExtra(String, int)}.
     * {@hide}
     */
    public static final String EXTRA_INET_CONDITION = "inetCondition";

    /**
     * Broadcast Action: The setting for background data usage has changed
     * values. Use {@link #getBackgroundDataSetting()} to get the current value.
     * <p>
     * If an application uses the network in the background, it should listen
     * for this broadcast and stop using the background data if the value is
     * {@code false}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_BACKGROUND_DATA_SETTING_CHANGED =
            "android.net.conn.BACKGROUND_DATA_SETTING_CHANGED";

    /**
     * Broadcast Action: The network connection may not be good
     * uses {@code ConnectivityManager.EXTRA_INET_CONDITION} and
     * {@code ConnectivityManager.EXTRA_NETWORK_INFO} to specify
     * the network and it's condition.
     * @hide
     */
    public static final String INET_CONDITION_ACTION =
            "android.net.conn.INET_CONDITION_ACTION";

    /**
     * Broadcast Action: A tetherable connection has come or gone
     * TODO - finish the doc
     * @hide
     */
    public static final String ACTION_TETHER_STATE_CHANGED =
            "android.net.conn.TETHER_STATE_CHANGED";

    /**
     * @hide
     * gives a String[]
     */
    public static final String EXTRA_AVAILABLE_TETHER = "availableArray";

    /**
     * @hide
     * gives a String[]
     */
    public static final String EXTRA_ACTIVE_TETHER = "activeArray";

    /**
     * @hide
     * gives a String[]
     */
    public static final String EXTRA_ERRORED_TETHER = "erroredArray";

    /**
     * The absence of APN..
     * @hide
     */
    public static final int TYPE_NONE        = -1;

    /**
     * The Default Mobile data connection.  When active, all data traffic
     * will use this connection by default.
     */
    public static final int TYPE_MOBILE      = 0;
    /**
     * The Default WIFI data connection.  When active, all data traffic
     * will use this connection by default.
     */
    public static final int TYPE_WIFI        = 1;
    /**
     * An MMS-specific Mobile data connection.  This connection may be the
     * same as {@link #TYPE_MOBILE} but it may be different.  This is used
     * by applications needing to talk to the carrier's Multimedia Messaging
     * Service servers.  It may coexist with default data connections.
     */
    public static final int TYPE_MOBILE_MMS  = 2;
    /**
     * A SUPL-specific Mobile data connection.  This connection may be the
     * same as {@link #TYPE_MOBILE} but it may be different.  This is used
     * by applications needing to talk to the carrier's Secure User Plane
     * Location servers for help locating the device.  It may coexist with
     * default data connections.
     */
    public static final int TYPE_MOBILE_SUPL = 3;
    /**
     * A DUN-specific Mobile data connection.  This connection may be the
     * same as {@link #TYPE_MOBILE} but it may be different.  This is used
     * by applicaitons performing a Dial Up Networking bridge so that
     * the carrier is aware of DUN traffic.  It may coexist with default data
     * connections.
     */
    public static final int TYPE_MOBILE_DUN  = 4;
    /**
     * A High Priority Mobile data connection.  This connection is typically
     * the same as {@link #TYPE_MOBILE} but the routing setup is different.
     * Only requesting processes will have access to the Mobile DNS servers
     * and only IP's explicitly requested via {@link #requestRouteToHost}
     * will route over this interface if a default route exists.
     */
    public static final int TYPE_MOBILE_HIPRI = 5;
    /**
     * The Default WiMAX data connection.  When active, all data traffic
     * will use this connection by default.
     */
    public static final int TYPE_WIMAX       = 6;

    /**
     * The Default Bluetooth data connection. When active, all data traffic
     * will use this connection by default.
     */
    public static final int TYPE_BLUETOOTH   = 7;

    /**
     * Dummy data connection.  This should not be used on shipping devices.
     */
    public static final int TYPE_DUMMY       = 8;

    /**
     * The Default Ethernet data connection.  When active, all data traffic
     * will use this connection by default.
     */
    public static final int TYPE_ETHERNET    = 9;

    /**
     * Over the air Adminstration.
     * {@hide}
     */
    public static final int TYPE_MOBILE_FOTA = 10;

    /**
     * IP Multimedia Subsystem
     * {@hide}
     */
    public static final int TYPE_MOBILE_IMS  = 11;

    /**
     * Carrier Branded Services
     * {@hide}
     */
    public static final int TYPE_MOBILE_CBS  = 12;

    /**
     * A Wi-Fi p2p connection. Only requesting processes will have access to
     * the peers connected.
     * {@hide}
     */
    public static final int TYPE_WIFI_P2P    = 13;

    /** {@hide} */
    public static final int MAX_RADIO_TYPE   = TYPE_WIFI_P2P;

    /** {@hide} */
    public static final int MAX_NETWORK_TYPE = TYPE_WIFI_P2P;

    public static final int DEFAULT_NETWORK_PREFERENCE = TYPE_WIFI;

    private final IConnectivityManager mService;

    public static boolean isNetworkTypeValid(int networkType) {
        return networkType >= 0 && networkType <= MAX_NETWORK_TYPE;
    }

    /** {@hide} */
    public static String getNetworkTypeName(int type) {
        switch (type) {
            case TYPE_MOBILE:
                return "MOBILE";
            case TYPE_WIFI:
                return "WIFI";
            case TYPE_MOBILE_MMS:
                return "MOBILE_MMS";
            case TYPE_MOBILE_SUPL:
                return "MOBILE_SUPL";
            case TYPE_MOBILE_DUN:
                return "MOBILE_DUN";
            case TYPE_MOBILE_HIPRI:
                return "MOBILE_HIPRI";
            case TYPE_WIMAX:
                return "WIMAX";
            case TYPE_BLUETOOTH:
                return "BLUETOOTH";
            case TYPE_DUMMY:
                return "DUMMY";
            case TYPE_ETHERNET:
                return "ETHERNET";
            case TYPE_MOBILE_FOTA:
                return "MOBILE_FOTA";
            case TYPE_MOBILE_IMS:
                return "MOBILE_IMS";
            case TYPE_MOBILE_CBS:
                return "MOBILE_CBS";
            case TYPE_WIFI_P2P:
                return "WIFI_P2P";
            default:
                return Integer.toString(type);
        }
    }

    /** {@hide} */
    public static boolean isNetworkTypeMobile(int networkType) {
        switch (networkType) {
            case TYPE_MOBILE:
            case TYPE_MOBILE_MMS:
            case TYPE_MOBILE_SUPL:
            case TYPE_MOBILE_DUN:
            case TYPE_MOBILE_HIPRI:
            case TYPE_MOBILE_FOTA:
            case TYPE_MOBILE_IMS:
            case TYPE_MOBILE_CBS:
                return true;
            default:
                return false;
        }
    }

    public void setNetworkPreference(int preference) {
        try {
            mService.setNetworkPreference(preference);
        } catch (RemoteException e) {
        }
    }

    public int getNetworkPreference() {
        try {
            return mService.getNetworkPreference();
        } catch (RemoteException e) {
            return -1;
        }
    }

    public NetworkInfo getActiveNetworkInfo() {
        try {
            return mService.getActiveNetworkInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /** {@hide} */
    public NetworkInfo getActiveNetworkInfoForUid(int uid) {
        try {
            return mService.getActiveNetworkInfoForUid(uid);
        } catch (RemoteException e) {
            return null;
        }
    }

    public NetworkInfo getNetworkInfo(int networkType) {
        try {
            return mService.getNetworkInfo(networkType);
        } catch (RemoteException e) {
            return null;
        }
    }

    public NetworkInfo[] getAllNetworkInfo() {
        try {
            return mService.getAllNetworkInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /** {@hide} */
    public LinkProperties getActiveLinkProperties() {
        try {
            return mService.getActiveLinkProperties();
        } catch (RemoteException e) {
            return null;
        }
    }

    /** {@hide} */
    public LinkProperties getLinkProperties(int networkType) {
        try {
            return mService.getLinkProperties(networkType);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** {@hide} */
    public boolean setRadios(boolean turnOn) {
        try {
            return mService.setRadios(turnOn);
        } catch (RemoteException e) {
            return false;
        }
    }

    /** {@hide} */
    public boolean setRadio(int networkType, boolean turnOn) {
        try {
            return mService.setRadio(networkType, turnOn);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Tells the underlying networking system that the caller wants to
     * begin using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param networkType specifies which network the request pertains to
     * @param feature the name of the feature to be used
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     */
    public int startUsingNetworkFeature(int networkType, String feature) {
        try {
            return mService.startUsingNetworkFeature(networkType, feature,
                    new Binder());
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Tells the underlying networking system that the caller is finished
     * using the named feature. The interpretation of {@code feature}
     * is completely up to each networking implementation.
     * @param networkType specifies which network the request pertains to
     * @param feature the name of the feature that is no longer needed
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is specific to each networking
     * implementation+feature combination, except that the value {@code -1}
     * always indicates failure.
     */
    public int stopUsingNetworkFeature(int networkType, String feature) {
        try {
            return mService.stopUsingNetworkFeature(networkType, feature);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface. An attempt to add a route that
     * already exists is ignored, but treated as successful.
     * @param networkType the type of the network over which traffic to the specified
     * host is to be routed
     * @param hostAddress the IP address of the host to which the route is desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHost(int networkType, int hostAddress) {
        InetAddress inetAddress = NetworkUtils.intToInetAddress(hostAddress);

        if (inetAddress == null) {
            return false;
        }

        return requestRouteToHostAddress(networkType, inetAddress);
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface. An attempt to add a route that
     * already exists is ignored, but treated as successful.
     * @param networkType the type of the network over which traffic to the specified
     * host is to be routed
     * @param hostAddress the IP address of the host to which the route is desired
     * @return {@code true} on success, {@code false} on failure
     * @hide
     */
    public boolean requestRouteToHostAddress(int networkType, InetAddress hostAddress) {
        byte[] address = hostAddress.getAddress();
        try {
            return mService.requestRouteToHostAddress(networkType, address);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns the value of the setting for background data usage. If false,
     * applications should not use the network if the application is not in the
     * foreground. Developers should respect this setting, and check the value
     * of this before performing any background data operations.
     * <p>
     * All applications that have background services that use the network
     * should listen to {@link #ACTION_BACKGROUND_DATA_SETTING_CHANGED}.
     * <p>
     * As of {@link VERSION_CODES#ICE_CREAM_SANDWICH}, availability of
     * background data depends on several combined factors, and this method will
     * always return {@code true}. Instead, when background data is unavailable,
     * {@link #getActiveNetworkInfo()} will now appear disconnected.
     *
     * @return Whether background data usage is allowed.
     */
    @Deprecated
    public boolean getBackgroundDataSetting() {
        // assume that background data is allowed; final authority is
        // NetworkInfo which may be blocked.
        return true;
    }

    /**
     * Sets the value of the setting for background data usage.
     *
     * @param allowBackgroundData Whether an application should use data while
     *            it is in the background.
     *
     * @attr ref android.Manifest.permission#CHANGE_BACKGROUND_DATA_SETTING
     * @see #getBackgroundDataSetting()
     * @hide
     */
    @Deprecated
    public void setBackgroundDataSetting(boolean allowBackgroundData) {
        // ignored
    }

    /**
     * Return quota status for the current active network, or {@code null} if no
     * network is active. Quota status can change rapidly, so these values
     * shouldn't be cached.
     */
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        try {
            return mService.getActiveNetworkQuotaInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the value of the setting for enabling Mobile data.
     *
     * @return Whether mobile data is enabled.
     * @hide
     */
    public boolean getMobileDataEnabled() {
        try {
            return mService.getMobileDataEnabled();
        } catch (RemoteException e) {
            return true;
        }
    }

    /**
     * Sets the persisted value for enabling/disabling Mobile data.
     *
     * @param enabled Whether the mobile data connection should be
     *            used or not.
     * @hide
     */
    public void setMobileDataEnabled(boolean enabled) {
        try {
            mService.setMobileDataEnabled(enabled);
        } catch (RemoteException e) {
        }
    }

    /**
     * {@hide}
     */
    public ConnectivityManager(IConnectivityManager service) {
        mService = checkNotNull(service, "missing IConnectivityManager");
    }

    /**
     * {@hide}
     */
    public String[] getTetherableIfaces() {
        try {
            return mService.getTetherableIfaces();
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * {@hide}
     */
    public String[] getTetheredIfaces() {
        try {
            return mService.getTetheredIfaces();
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * {@hide}
     */
    public String[] getTetheringErroredIfaces() {
        try {
            return mService.getTetheringErroredIfaces();
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * @return error A TETHER_ERROR value indicating success or failure type
     * {@hide}
     */
    public int tether(String iface) {
        try {
            return mService.tether(iface);
        } catch (RemoteException e) {
            return TETHER_ERROR_SERVICE_UNAVAIL;
        }
    }

    /**
     * @return error A TETHER_ERROR value indicating success or failure type
     * {@hide}
     */
    public int untether(String iface) {
        try {
            return mService.untether(iface);
        } catch (RemoteException e) {
            return TETHER_ERROR_SERVICE_UNAVAIL;
        }
    }

    /**
     * {@hide}
     */
    public boolean isTetheringSupported() {
        try {
            return mService.isTetheringSupported();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * {@hide}
     */
    public String[] getTetherableUsbRegexs() {
        try {
            return mService.getTetherableUsbRegexs();
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * {@hide}
     */
    public String[] getTetherableWifiRegexs() {
        try {
            return mService.getTetherableWifiRegexs();
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * {@hide}
     */
    public String[] getTetherableBluetoothRegexs() {
        try {
            return mService.getTetherableBluetoothRegexs();
        } catch (RemoteException e) {
            return new String[0];
        }
    }

    /**
     * {@hide}
     */
    public int setUsbTethering(boolean enable) {
        try {
            return mService.setUsbTethering(enable);
        } catch (RemoteException e) {
            return TETHER_ERROR_SERVICE_UNAVAIL;
        }
    }

    /** {@hide} */
    public static final int TETHER_ERROR_NO_ERROR           = 0;
    /** {@hide} */
    public static final int TETHER_ERROR_UNKNOWN_IFACE      = 1;
    /** {@hide} */
    public static final int TETHER_ERROR_SERVICE_UNAVAIL    = 2;
    /** {@hide} */
    public static final int TETHER_ERROR_UNSUPPORTED        = 3;
    /** {@hide} */
    public static final int TETHER_ERROR_UNAVAIL_IFACE      = 4;
    /** {@hide} */
    public static final int TETHER_ERROR_MASTER_ERROR       = 5;
    /** {@hide} */
    public static final int TETHER_ERROR_TETHER_IFACE_ERROR = 6;
    /** {@hide} */
    public static final int TETHER_ERROR_UNTETHER_IFACE_ERROR = 7;
    /** {@hide} */
    public static final int TETHER_ERROR_ENABLE_NAT_ERROR     = 8;
    /** {@hide} */
    public static final int TETHER_ERROR_DISABLE_NAT_ERROR    = 9;
    /** {@hide} */
    public static final int TETHER_ERROR_IFACE_CFG_ERROR      = 10;

    /**
     * @param iface The name of the interface we're interested in
     * @return error The error code of the last error tethering or untethering the named
     *               interface
     * {@hide}
     */
    public int getLastTetherError(String iface) {
        try {
            return mService.getLastTetherError(iface);
        } catch (RemoteException e) {
            return TETHER_ERROR_SERVICE_UNAVAIL;
        }
    }

    /**
     * Ensure the device stays awake until we connect with the next network
     * @param forWhome The name of the network going down for logging purposes
     * @return {@code true} on success, {@code false} on failure
     * {@hide}
     */
    public boolean requestNetworkTransitionWakelock(String forWhom) {
        try {
            mService.requestNetworkTransitionWakelock(forWhom);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * @param networkType The type of network you want to report on
     * @param percentage The quality of the connection 0 is bad, 100 is good
     * {@hide}
     */
    public void reportInetCondition(int networkType, int percentage) {
        try {
            mService.reportInetCondition(networkType, percentage);
        } catch (RemoteException e) {
        }
    }

    /**
     * @param proxyProperties The definition for the new global http proxy
     * {@hide}
     */
    public void setGlobalProxy(ProxyProperties p) {
        try {
            mService.setGlobalProxy(p);
        } catch (RemoteException e) {
        }
    }

    /**
     * @return proxyProperties for the current global proxy
     * {@hide}
     */
    public ProxyProperties getGlobalProxy() {
        try {
            return mService.getGlobalProxy();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * @return proxyProperties for the current proxy (global if set, network specific if not)
     * {@hide}
     */
    public ProxyProperties getProxy() {
        try {
            return mService.getProxy();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * @param networkType The network who's dependence has changed
     * @param met Boolean - true if network use is ok, false if not
     * {@hide}
     */
    public void setDataDependency(int networkType, boolean met) {
        try {
            mService.setDataDependency(networkType, met);
        } catch (RemoteException e) {
        }
    }

    /**
     * Returns true if the hardware supports the given network type
     * else it returns false.  This doesn't indicate we have coverage
     * or are authorized onto a network, just whether or not the
     * hardware supports it.  For example a gsm phone without a sim
     * should still return true for mobile data, but a wifi only tablet
     * would return false.
     * @param networkType The nework type we'd like to check
     * @return true if supported, else false
     * @hide
     */
    public boolean isNetworkSupported(int networkType) {
        try {
            return mService.isNetworkSupported(networkType);
        } catch (RemoteException e) {}
        return false;
    }
}
