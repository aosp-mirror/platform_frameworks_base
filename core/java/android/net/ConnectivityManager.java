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
import android.content.Context;
import android.os.Binder;
import android.os.Build.VERSION_CODES;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ResultReceiver;
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
     * receiver should expect another broadcast soon, indicating either that
     * the failover attempt succeeded (and so there is still overall data
     * connectivity), or that the failover attempt failed, meaning that all
     * connectivity has been lost.
     * <p/>
     * For a disconnect event, the boolean extra EXTRA_NO_CONNECTIVITY
     * is set to {@code true} if there are no connected networks at all.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";

    /**
     * Identical to {@link #CONNECTIVITY_ACTION} broadcast, but sent without any
     * applicable {@link Settings.Secure#CONNECTIVITY_CHANGE_DELAY}.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
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
     * @see #EXTRA_NETWORK_TYPE
     */
    @Deprecated
    public static final String EXTRA_NETWORK_INFO = "networkInfo";

    /**
     * Network type which triggered a {@link #CONNECTIVITY_ACTION} broadcast.
     * Can be used with {@link #getNetworkInfo(int)} to get {@link NetworkInfo}
     * state based on the calling application.
     *
     * @see android.content.Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_NETWORK_TYPE = "networkType";

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
     * Broadcast action to indicate the change of data activity status
     * (idle or active) on a network in a recent period.
     * The network becomes active when data transmission is started, or
     * idle if there is no data transmission for a period of time.
     * {@hide}
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DATA_ACTIVITY_CHANGE = "android.net.conn.DATA_ACTIVITY_CHANGE";
    /**
     * The lookup key for an enum that indicates the network device type on which this data activity
     * change happens.
     * {@hide}
     */
    public static final String EXTRA_DEVICE_TYPE = "deviceType";
    /**
     * The lookup key for a boolean that indicates the device is active or not. {@code true} means
     * it is actively sending or receiving data and {@code false} means it is idle.
     * {@hide}
     */
    public static final String EXTRA_IS_ACTIVE = "isActive";

    /**
     * Broadcast Action: The setting for background data usage has changed
     * values. Use {@link #getBackgroundDataSetting()} to get the current value.
     * <p>
     * If an application uses the network in the background, it should listen
     * for this broadcast and stop using the background data if the value is
     * {@code false}.
     * <p>
     *
     * @deprecated As of {@link VERSION_CODES#ICE_CREAM_SANDWICH}, availability
     *             of background data depends on several combined factors, and
     *             this broadcast is no longer sent. Instead, when background
     *             data is unavailable, {@link #getActiveNetworkInfo()} will now
     *             appear disconnected. During first boot after a platform
     *             upgrade, this broadcast will be sent once if
     *             {@link #getBackgroundDataSetting()} was {@code false} before
     *             the upgrade.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @Deprecated
    public static final String ACTION_BACKGROUND_DATA_SETTING_CHANGED =
            "android.net.conn.BACKGROUND_DATA_SETTING_CHANGED";

    /**
     * Broadcast Action: The network connection may not be good
     * uses {@code ConnectivityManager.EXTRA_INET_CONDITION} and
     * {@code ConnectivityManager.EXTRA_NETWORK_INFO} to specify
     * the network and it's condition.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INET_CONDITION_ACTION =
            "android.net.conn.INET_CONDITION_ACTION";

    /**
     * Broadcast Action: A tetherable connection has come or gone.
     * Uses {@code ConnectivityManager.EXTRA_AVAILABLE_TETHER},
     * {@code ConnectivityManager.EXTRA_ACTIVE_TETHER} and
     * {@code ConnectivityManager.EXTRA_ERRORED_TETHER} to indicate
     * the current state of tethering.  Each include a list of
     * interface names in that state (may be empty).
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TETHER_STATE_CHANGED =
            "android.net.conn.TETHER_STATE_CHANGED";

    /**
     * @hide
     * gives a String[] listing all the interfaces configured for
     * tethering and currently available for tethering.
     */
    public static final String EXTRA_AVAILABLE_TETHER = "availableArray";

    /**
     * @hide
     * gives a String[] listing all the interfaces currently tethered
     * (ie, has dhcp support and packets potentially forwarded/NATed)
     */
    public static final String EXTRA_ACTIVE_TETHER = "activeArray";

    /**
     * @hide
     * gives a String[] listing all the interfaces we tried to tether and
     * failed.  Use {@link #getLastTetherError} to find the error code
     * for any interfaces listed here.
     */
    public static final String EXTRA_ERRORED_TETHER = "erroredArray";

    /**
     * Broadcast Action: The captive portal tracker has finished its test.
     * Sent only while running Setup Wizard, in lieu of showing a user
     * notification.
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CAPTIVE_PORTAL_TEST_COMPLETED =
            "android.net.conn.CAPTIVE_PORTAL_TEST_COMPLETED";
    /**
     * The lookup key for a boolean that indicates whether a captive portal was detected.
     * Retrieve it with {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     * @hide
     */
    public static final String EXTRA_IS_CAPTIVE_PORTAL = "captivePortal";

    /**
     * The absence of a connection type.
     * @hide
     */
    public static final int TYPE_NONE        = -1;

    /**
     * The Mobile data connection.  When active, all data traffic
     * will use this network type's interface by default
     * (it has a default route)
     */
    public static final int TYPE_MOBILE      = 0;
    /**
     * The WIFI data connection.  When active, all data traffic
     * will use this network type's interface by default
     * (it has a default route).
     */
    public static final int TYPE_WIFI        = 1;
    /**
     * An MMS-specific Mobile data connection.  This network type may use the
     * same network interface as {@link #TYPE_MOBILE} or it may use a different
     * one.  This is used by applications needing to talk to the carrier's
     * Multimedia Messaging Service servers.
     */
    public static final int TYPE_MOBILE_MMS  = 2;
    /**
     * A SUPL-specific Mobile data connection.  This network type may use the
     * same network interface as {@link #TYPE_MOBILE} or it may use a different
     * one.  This is used by applications needing to talk to the carrier's
     * Secure User Plane Location servers for help locating the device.
     */
    public static final int TYPE_MOBILE_SUPL = 3;
    /**
     * A DUN-specific Mobile data connection.  This network type may use the
     * same network interface as {@link #TYPE_MOBILE} or it may use a different
     * one.  This is sometimes by the system when setting up an upstream connection
     * for tethering so that the carrier is aware of DUN traffic.
     */
    public static final int TYPE_MOBILE_DUN  = 4;
    /**
     * A High Priority Mobile data connection.  This network type uses the
     * same network interface as {@link #TYPE_MOBILE} but the routing setup
     * is different.  Only requesting processes will have access to the
     * Mobile DNS servers and only IP's explicitly requested via {@link #requestRouteToHost}
     * will route over this interface if no default route exists.
     */
    public static final int TYPE_MOBILE_HIPRI = 5;
    /**
     * The WiMAX data connection.  When active, all data traffic
     * will use this network type's interface by default
     * (it has a default route).
     */
    public static final int TYPE_WIMAX       = 6;

    /**
     * The Bluetooth data connection.  When active, all data traffic
     * will use this network type's interface by default
     * (it has a default route).
     */
    public static final int TYPE_BLUETOOTH   = 7;

    /**
     * Dummy data connection.  This should not be used on shipping devices.
     */
    public static final int TYPE_DUMMY       = 8;

    /**
     * The Ethernet data connection.  When active, all data traffic
     * will use this network type's interface by default
     * (it has a default route).
     */
    public static final int TYPE_ETHERNET    = 9;

    /**
     * Over the air Administration.
     * {@hide}
     */
    public static final int TYPE_MOBILE_FOTA = 10;

    /**
     * IP Multimedia Subsystem.
     * {@hide}
     */
    public static final int TYPE_MOBILE_IMS  = 11;

    /**
     * Carrier Branded Services.
     * {@hide}
     */
    public static final int TYPE_MOBILE_CBS  = 12;

    /**
     * A Wi-Fi p2p connection. Only requesting processes will have access to
     * the peers connected.
     * {@hide}
     */
    public static final int TYPE_WIFI_P2P    = 13;

    /**
     * The network to use for initially attaching to the network
     * {@hide}
     */
    public static final int TYPE_MOBILE_IA = 14;

    /** {@hide} */
    public static final int MAX_RADIO_TYPE   = TYPE_MOBILE_IA;

    /** {@hide} */
    public static final int MAX_NETWORK_TYPE = TYPE_MOBILE_IA;

    /**
     * If you want to set the default network preference,you can directly
     * change the networkAttributes array in framework's config.xml.
     *
     * @deprecated Since we support so many more networks now, the single
     *             network default network preference can't really express
     *             the hierarchy.  Instead, the default is defined by the
     *             networkAttributes in config.xml.  You can determine
     *             the current value by calling {@link #getNetworkPreference()}
     *             from an App.
     */
    @Deprecated
    public static final int DEFAULT_NETWORK_PREFERENCE = TYPE_WIFI;

    /**
     * Default value for {@link Settings.Global#CONNECTIVITY_CHANGE_DELAY} in
     * milliseconds.  This was introduced because IPv6 routes seem to take a
     * moment to settle - trying network activity before the routes are adjusted
     * can lead to packets using the wrong interface or having the wrong IP address.
     * This delay is a bit crude, but in the future hopefully we will have kernel
     * notifications letting us know when it's safe to use the new network.
     *
     * @hide
     */
    public static final int CONNECTIVITY_CHANGE_DELAY_DEFAULT = 3000;

    private final IConnectivityManager mService;

    /**
     * Tests if a given integer represents a valid network type.
     * @param networkType the type to be tested
     * @return a boolean.  {@code true} if the type is valid, else {@code false}
     */
    public static boolean isNetworkTypeValid(int networkType) {
        return networkType >= 0 && networkType <= MAX_NETWORK_TYPE;
    }

    /**
     * Returns a non-localized string representing a given network type.
     * ONLY used for debugging output.
     * @param type the type needing naming
     * @return a String for the given type, or a string version of the type ("87")
     * if no name is known.
     * {@hide}
     */
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
            case TYPE_MOBILE_IA:
                return "MOBILE_IA";
            default:
                return Integer.toString(type);
        }
    }

    /**
     * Checks if a given type uses the cellular data connection.
     * This should be replaced in the future by a network property.
     * @param networkType the type to check
     * @return a boolean - {@code true} if uses cellular network, else {@code false}
     * {@hide}
     */
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
            case TYPE_MOBILE_IA:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if the given network type is backed by a Wi-Fi radio.
     *
     * @hide
     */
    public static boolean isNetworkTypeWifi(int networkType) {
        switch (networkType) {
            case TYPE_WIFI:
            case TYPE_WIFI_P2P:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if the given network type should be exempt from VPN routing rules
     *
     * @hide
     */
    public static boolean isNetworkTypeExempt(int networkType) {
        switch (networkType) {
            case TYPE_MOBILE_MMS:
            case TYPE_MOBILE_SUPL:
            case TYPE_MOBILE_HIPRI:
            case TYPE_MOBILE_IA:
                return true;
            default:
                return false;
        }
    }

    /**
     * Specifies the preferred network type.  When the device has more
     * than one type available the preferred network type will be used.
     * Note that this made sense when we only had 2 network types,
     * but with more and more default networks we need an array to list
     * their ordering.  This will be deprecated soon.
     *
     * @param preference the network type to prefer over all others.  It is
     *         unspecified what happens to the old preferred network in the
     *         overall ordering.
     */
    public void setNetworkPreference(int preference) {
        try {
            mService.setNetworkPreference(preference);
        } catch (RemoteException e) {
        }
    }

    /**
     * Retrieves the current preferred network type.
     * Note that this made sense when we only had 2 network types,
     * but with more and more default networks we need an array to list
     * their ordering.  This will be deprecated soon.
     *
     * @return an integer representing the preferred network type
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     */
    public int getNetworkPreference() {
        try {
            return mService.getNetworkPreference();
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Returns details about the currently active default data network. When
     * connected, this network is the default route for outgoing connections.
     * You should always check {@link NetworkInfo#isConnected()} before initiating
     * network traffic. This may return {@code null} when there is no default
     * network.
     *
     * @return a {@link NetworkInfo} object for the current default network
     *        or {@code null} if no network default network is currently active
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     */
    public NetworkInfo getActiveNetworkInfo() {
        try {
            return mService.getActiveNetworkInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns details about the currently active default data network
     * for a given uid.  This is for internal use only to avoid spying
     * other apps.
     *
     * @return a {@link NetworkInfo} object for the current default network
     *        for the given uid or {@code null} if no default network is
     *        available for the specified uid.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}
     * {@hide}
     */
    public NetworkInfo getActiveNetworkInfoForUid(int uid) {
        try {
            return mService.getActiveNetworkInfoForUid(uid);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns connection status information about a particular
     * network type.
     *
     * @param networkType integer specifying which networkType in
     *        which you're interested.
     * @return a {@link NetworkInfo} object for the requested
     *        network type or {@code null} if the type is not
     *        supported by the device.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     */
    public NetworkInfo getNetworkInfo(int networkType) {
        try {
            return mService.getNetworkInfo(networkType);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns connection status information about all network
     * types supported by the device.
     *
     * @return an array of {@link NetworkInfo} objects.  Check each
     * {@link NetworkInfo#getType} for which type each applies.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     */
    public NetworkInfo[] getAllNetworkInfo() {
        try {
            return mService.getAllNetworkInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns details about the Provisioning or currently active default data network. When
     * connected, this network is the default route for outgoing connections.
     * You should always check {@link NetworkInfo#isConnected()} before initiating
     * network traffic. This may return {@code null} when there is no default
     * network.
     *
     * @return a {@link NetworkInfo} object for the current default network
     *        or {@code null} if no network default network is currently active
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     *
     * {@hide}
     */
    public NetworkInfo getProvisioningOrActiveNetworkInfo() {
        try {
            return mService.getProvisioningOrActiveNetworkInfo();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns the IP information for the current default network.
     *
     * @return a {@link LinkProperties} object describing the IP info
     *        for the current default network, or {@code null} if there
     *        is no current default network.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     * {@hide}
     */
    public LinkProperties getActiveLinkProperties() {
        try {
            return mService.getActiveLinkProperties();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns the IP information for a given network type.
     *
     * @param networkType the network type of interest.
     * @return a {@link LinkProperties} object describing the IP info
     *        for the given networkType, or {@code null} if there is
     *        no current default network.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     * {@hide}
     */
    public LinkProperties getLinkProperties(int networkType) {
        try {
            return mService.getLinkProperties(networkType);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Tells each network type to set its radio power state as directed.
     *
     * @param turnOn a boolean, {@code true} to turn the radios on,
     *        {@code false} to turn them off.
     * @return a boolean, {@code true} indicating success.  All network types
     *        will be tried, even if some fail.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
     * {@hide}
     */
    public boolean setRadios(boolean turnOn) {
        try {
            return mService.setRadios(turnOn);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Tells a given networkType to set its radio power state as directed.
     *
     * @param networkType the int networkType of interest.
     * @param turnOn a boolean, {@code true} to turn the radio on,
     *        {@code} false to turn it off.
     * @return a boolean, {@code true} indicating success.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
     * {@hide}
     */
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
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
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
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
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
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
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
     * @deprecated As of {@link VERSION_CODES#ICE_CREAM_SANDWICH}, availability of
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
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     *
     * @hide
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
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * @param enabled Whether the user wants the mobile data connection used
     *            or not.
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

    /** {@hide} */
    public static ConnectivityManager from(Context context) {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Get the set of tetherable, available interfaces.  This list is limited by
     * device configuration and current interface existence.
     *
     * @return an array of 0 or more Strings of tetherable interface names.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Get the set of tethered interfaces.
     *
     * @return an array of 0 or more String of currently tethered interface names.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Get the set of interface names which attempted to tether but
     * failed.  Re-attempting to tether may cause them to reset to the Tethered
     * state.  Alternatively, causing the interface to be destroyed and recreated
     * may cause them to reset to the available state.
     * {@link ConnectivityManager#getLastTetherError} can be used to get more
     * information on the cause of the errors.
     *
     * @return an array of 0 or more String indicating the interface names
     *        which failed to tether.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Attempt to tether the named interface.  This will setup a dhcp server
     * on the interface, forward and NAT IP packets and forward DNS requests
     * to the best active upstream network interface.  Note that if no upstream
     * IP network interface is available, dhcp will still run and traffic will be
     * allowed between the tethered devices and this device, though upstream net
     * access will of course fail until an upstream network interface becomes
     * active.
     *
     * @param iface the interface name to tether.
     * @return error a {@code TETHER_ERROR} value indicating success or failure type
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
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
     * Stop tethering the named interface.
     *
     * @param iface the interface name to untether.
     * @return error a {@code TETHER_ERROR} value indicating success or failure type
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
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
     * Check if the device allows for tethering.  It may be disabled via
     * {@code ro.tether.denied} system property, {@link Settings#TETHER_SUPPORTED} or
     * due to device configuration.
     *
     * @return a boolean - {@code true} indicating Tethering is supported.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Get the list of regular expressions that define any tetherable
     * USB network interfaces.  If USB tethering is not supported by the
     * device, this list should be empty.
     *
     * @return an array of 0 or more regular expression Strings defining
     *        what interfaces are considered tetherable usb interfaces.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Get the list of regular expressions that define any tetherable
     * Wifi network interfaces.  If Wifi tethering is not supported by the
     * device, this list should be empty.
     *
     * @return an array of 0 or more regular expression Strings defining
     *        what interfaces are considered tetherable wifi interfaces.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Get the list of regular expressions that define any tetherable
     * Bluetooth network interfaces.  If Bluetooth tethering is not supported by the
     * device, this list should be empty.
     *
     * @return an array of 0 or more regular expression Strings defining
     *        what interfaces are considered tetherable bluetooth interfaces.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Attempt to both alter the mode of USB and Tethering of USB.  A
     * utility method to deal with some of the complexity of USB - will
     * attempt to switch to Rndis and subsequently tether the resulting
     * interface on {@code true} or turn off tethering and switch off
     * Rndis on {@code false}.
     *
     * @param enable a boolean - {@code true} to enable tethering
     * @return error a {@code TETHER_ERROR} value indicating success or failure type
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
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
     * Get a more detailed error code after a Tethering or Untethering
     * request asynchronously failed.
     *
     * @param iface The name of the interface of interest
     * @return error The error code of the last error tethering or untethering the named
     *               interface
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Try to ensure the device stays awake until we connect with the next network.
     * Actually just holds a wakelock for a number of seconds while we try to connect
     * to any default networks.  This will expire if the timeout passes or if we connect
     * to a default after this is called.  For internal use only.
     *
     * @param forWhom the name of the network going down for logging purposes
     * @return {@code true} on success, {@code false} on failure
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}.
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
     * Report network connectivity status.  This is currently used only
     * to alter status bar UI.
     *
     * @param networkType The type of network you want to report on
     * @param percentage The quality of the connection 0 is bad, 100 is good
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#STATUS_BAR}.
     * {@hide}
     */
    public void reportInetCondition(int networkType, int percentage) {
        try {
            mService.reportInetCondition(networkType, percentage);
        } catch (RemoteException e) {
        }
    }

    /**
     * Set a network-independent global http proxy.  This is not normally what you want
     * for typical HTTP proxies - they are general network dependent.  However if you're
     * doing something unusual like general internal filtering this may be useful.  On
     * a private network where the proxy is not accessible, you may break HTTP using this.
     *
     * @param proxyProperties The a {@link ProxyProperites} object defining the new global
     *        HTTP proxy.  A {@code null} value will clear the global HTTP proxy.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}.
     * {@hide}
     */
    public void setGlobalProxy(ProxyProperties p) {
        try {
            mService.setGlobalProxy(p);
        } catch (RemoteException e) {
        }
    }

    /**
     * Retrieve any network-independent global HTTP proxy.
     *
     * @return {@link ProxyProperties} for the current global HTTP proxy or {@code null}
     *        if no global HTTP proxy is set.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Get the HTTP proxy settings for the current default network.  Note that
     * if a global proxy is set, it will override any per-network setting.
     *
     * @return the {@link ProxyProperties} for the current HTTP proxy, or {@code null} if no
     *        HTTP proxy is active.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
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
     * Sets a secondary requirement bit for the given networkType.
     * This requirement bit is generally under the control of the carrier
     * or its agents and is not directly controlled by the user.
     *
     * @param networkType The network who's dependence has changed
     * @param met Boolean - true if network use is OK, false if not
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}.
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
     * hardware supports it.  For example a GSM phone without a SIM
     * should still return {@code true} for mobile data, but a wifi only
     * tablet would return {@code false}.
     *
     * @param networkType The network type we'd like to check
     * @return {@code true} if supported, else {@code false}
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     * @hide
     */
    public boolean isNetworkSupported(int networkType) {
        try {
            return mService.isNetworkSupported(networkType);
        } catch (RemoteException e) {}
        return false;
    }

    /**
     * Returns if the currently active data network is metered. A network is
     * classified as metered when the user is sensitive to heavy data usage on
     * that connection due to monetary costs, data limitations or
     * battery/performance issues. You should check this before doing large
     * data transfers, and warn the user or delay the operation until another
     * network is available.
     *
     * @return {@code true} if large transfers should be avoided, otherwise
     *        {@code false}.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     */
    public boolean isActiveNetworkMetered() {
        try {
            return mService.isActiveNetworkMetered();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * If the LockdownVpn mechanism is enabled, updates the vpn
     * with a reload of its profile.
     *
     * @return a boolean with {@code} indicating success
     *
     * <p>This method can only be called by the system UID
     * {@hide}
     */
    public boolean updateLockdownVpn() {
        try {
            return mService.updateLockdownVpn();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Signal that the captive portal check on the indicated network
     * is complete and we can turn the network on for general use.
     *
     * @param info the {@link NetworkInfo} object for the networkType
     *        in question.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}.
     * {@hide}
     */
    public void captivePortalCheckComplete(NetworkInfo info) {
        try {
            mService.captivePortalCheckComplete(info);
        } catch (RemoteException e) {
        }
    }

    /**
     * Signal that the captive portal check on the indicated network
     * is complete and whether its a captive portal or not.
     *
     * @param info the {@link NetworkInfo} object for the networkType
     *        in question.
     * @param isCaptivePortal true/false.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}.
     * {@hide}
     */
    public void captivePortalCheckCompleted(NetworkInfo info, boolean isCaptivePortal) {
        try {
            mService.captivePortalCheckCompleted(info, isCaptivePortal);
        } catch (RemoteException e) {
        }
    }

    /**
     * Supply the backend messenger for a network tracker
     *
     * @param type NetworkType to set
     * @param messenger {@link Messenger}
     * {@hide}
     */
    public void supplyMessenger(int networkType, Messenger messenger) {
        try {
            mService.supplyMessenger(networkType, messenger);
        } catch (RemoteException e) {
        }
    }

    /**
     * Check mobile provisioning.
     *
     * @param suggestedTimeOutMs, timeout in milliseconds
     *
     * @return time out that will be used, maybe less that suggestedTimeOutMs
     * -1 if an error.
     *
     * {@hide}
     */
    public int checkMobileProvisioning(int suggestedTimeOutMs) {
        int timeOutMs = -1;
        try {
            timeOutMs = mService.checkMobileProvisioning(suggestedTimeOutMs);
        } catch (RemoteException e) {
        }
        return timeOutMs;
    }

    /**
     * Get the mobile provisioning url.
     * {@hide}
     */
    public String getMobileProvisioningUrl() {
        try {
            return mService.getMobileProvisioningUrl();
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Get the mobile redirected provisioning url.
     * {@hide}
     */
    public String getMobileRedirectedProvisioningUrl() {
        try {
            return mService.getMobileRedirectedProvisioningUrl();
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * get the information about a specific network link
     * @hide
     */
    public LinkQualityInfo getLinkQualityInfo(int networkType) {
        try {
            LinkQualityInfo li = mService.getLinkQualityInfo(networkType);
            return li;
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * get the information of currently active network link
     * @hide
     */
    public LinkQualityInfo getActiveLinkQualityInfo() {
        try {
            LinkQualityInfo li = mService.getActiveLinkQualityInfo();
            return li;
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * get the information of all network links
     * @hide
     */
    public LinkQualityInfo[] getAllLinkQualityInfo() {
        try {
            LinkQualityInfo[] li = mService.getAllLinkQualityInfo();
            return li;
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Set sign in error notification to visible or in visible
     *
     * @param visible
     * @param networkType
     *
     * {@hide}
     */
    public void setProvisioningNotificationVisible(boolean visible, int networkType,
            String extraInfo, String url) {
        try {
            mService.setProvisioningNotificationVisible(visible, networkType, extraInfo, url);
        } catch (RemoteException e) {
        }
    }

    /**
     * Set the value for enabling/disabling airplane mode
     *
     * @param enable whether to enable airplane mode or not
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#CONNECTIVITY_INTERNAL}.
     * @hide
     */
    public void setAirplaneMode(boolean enable) {
        try {
            mService.setAirplaneMode(enable);
        } catch (RemoteException e) {
        }
    }
}
