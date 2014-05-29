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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkActivityListener;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.Protocol;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;

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
 * <li>Provide an API that allows applications to request and select networks for their data
 * traffic</li>
 * </ol>
 */
public class ConnectivityManager {
    private static final String TAG = "ConnectivityManager";

    /**
     * A change in network connectivity has occurred. A default connection has either
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
     * applicable {@link Settings.Global#CONNECTIVITY_CHANGE_DELAY}.
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
     * The lookup key for a long that contains the timestamp (nanos) of the radio state change.
     * {@hide}
     */
    public static final String EXTRA_REALTIME_NS = "tsNanos";

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

    /**
     * The network that uses proxy to achieve connectivity.
     * {@hide}
     */
    public static final int TYPE_PROXY = 16;

    /** {@hide} */
    public static final int MAX_RADIO_TYPE   = TYPE_PROXY;

    /** {@hide} */
    public static final int MAX_NETWORK_TYPE = TYPE_PROXY;

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

    /**
     * @hide
     */
    public final static int INVALID_NET_ID = 0;

    private final IConnectivityManager mService;

    private final String mPackageName;

    private INetworkManagementService mNMService;

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
            case TYPE_PROXY:
                return "PROXY";
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
     *
     * @param preference the network type to prefer over all others.  It is
     *         unspecified what happens to the old preferred network in the
     *         overall ordering.
     * @deprecated Functionality has been removed as it no longer makes sense,
     *             with many more than two networks - we'd need an array to express
     *             preference.  Instead we use dynamic network properties of
     *             the networks to describe their precedence.
     */
    public void setNetworkPreference(int preference) {
    }

    /**
     * Retrieves the current preferred network type.
     *
     * @return an integer representing the preferred network type
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     * @deprecated Functionality has been removed as it no longer makes sense,
     *             with many more than two networks - we'd need an array to express
     *             preference.  Instead we use dynamic network properties of
     *             the networks to describe their precedence.
     */
    public int getNetworkPreference() {
        return TYPE_NONE;
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
            return mService.getLinkPropertiesForType(networkType);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get the {@link LinkProperties} for the given {@link Network}.  This
     * will return {@code null} if the network is unknown.
     *
     * @param network The {@link Network} object identifying the network in question.
     * @return The {@link LinkProperties} for the network, or {@code null}.
     **/
    public LinkProperties getLinkProperties(Network network) {
        try {
            return mService.getLinkProperties(network);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get the {@link NetworkCapabilities} for the given {@link Network}.  This
     * will return {@code null} if the network is unknown.
     *
     * @param network The {@link Network} object identifying the network in question.
     * @return The {@link NetworkCapabilities} for the network, or {@code null}.
     */
    public NetworkCapabilities getNetworkCapabilities(Network network) {
        try {
            return mService.getNetworkCapabilities(network);
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
// TODO - check for any callers and remove
//    public boolean setRadios(boolean turnOn) {
//        try {
//            return mService.setRadios(turnOn);
//        } catch (RemoteException e) {
//            return false;
//        }
//    }

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
// TODO - check for any callers and remove
//    public boolean setRadio(int networkType, boolean turnOn) {
//        try {
//            return mService.setRadio(networkType, turnOn);
//        } catch (RemoteException e) {
//            return false;
//        }
//    }

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
     *
     * @deprecated Deprecated in favor of the cleaner {@link #requestNetwork} api.
     */
    public int startUsingNetworkFeature(int networkType, String feature) {
        NetworkCapabilities netCap = networkCapabilitiesForFeature(networkType, feature);
        if (netCap == null) {
            Log.d(TAG, "Can't satisfy startUsingNetworkFeature for " + networkType + ", " +
                    feature);
            return PhoneConstants.APN_REQUEST_FAILED;
        }

        NetworkRequest request = null;
        synchronized (sLegacyRequests) {
            LegacyRequest l = sLegacyRequests.get(netCap);
            if (l != null) {
                Log.d(TAG, "renewing startUsingNetworkFeature request " + l.networkRequest);
                renewRequestLocked(l);
                if (l.currentNetwork != null) {
                    return PhoneConstants.APN_ALREADY_ACTIVE;
                } else {
                    return PhoneConstants.APN_REQUEST_STARTED;
                }
            }

            request = requestNetworkForFeatureLocked(netCap);
        }
        if (request != null) {
            Log.d(TAG, "starting startUsingNeworkFeature for request " + request);
            return PhoneConstants.APN_REQUEST_STARTED;
        } else {
            Log.d(TAG, " request Failed");
            return PhoneConstants.APN_REQUEST_FAILED;
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
     *
     * @deprecated Deprecated in favor of the cleaner {@link #requestNetwork} api.
     */
    public int stopUsingNetworkFeature(int networkType, String feature) {
        NetworkCapabilities netCap = networkCapabilitiesForFeature(networkType, feature);
        if (netCap == null) {
            Log.d(TAG, "Can't satisfy stopUsingNetworkFeature for " + networkType + ", " +
                    feature);
            return -1;
        }

        NetworkRequest request = removeRequestForFeature(netCap);
        if (request != null) {
            Log.d(TAG, "stopUsingNetworkFeature for " + networkType + ", " + feature);
            releaseNetworkRequest(request);
        }
        return 1;
    }

    /**
     * Removes the NET_CAPABILITY_NOT_RESTRICTED capability from the given
     * NetworkCapabilities object if all the capabilities it provides are
     * typically provided by restricted networks.
     *
     * TODO: consider:
     * - Moving to NetworkCapabilities
     * - Renaming it to guessRestrictedCapability and make it set the
     *   restricted capability bit in addition to clearing it.
     * @hide
     */
    public static void maybeMarkCapabilitiesRestricted(NetworkCapabilities nc) {
        for (Integer capability : nc.getNetworkCapabilities()) {
            switch (capability.intValue()) {
                case NetworkCapabilities.NET_CAPABILITY_CBS:
                case NetworkCapabilities.NET_CAPABILITY_DUN:
                case NetworkCapabilities.NET_CAPABILITY_EIMS:
                case NetworkCapabilities.NET_CAPABILITY_FOTA:
                case NetworkCapabilities.NET_CAPABILITY_IA:
                case NetworkCapabilities.NET_CAPABILITY_IMS:
                case NetworkCapabilities.NET_CAPABILITY_RCS:
                case NetworkCapabilities.NET_CAPABILITY_XCAP:
                case NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED: //there by default
                    continue;
                default:
                    // At least one capability usually provided by unrestricted
                    // networks. Conclude that this network is unrestricted.
                    return;
            }
        }
        // All the capabilities are typically provided by restricted networks.
        // Conclude that this network is restricted.
        nc.removeNetworkCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    }

    private NetworkCapabilities networkCapabilitiesForFeature(int networkType, String feature) {
        if (networkType == TYPE_MOBILE) {
            int cap = -1;
            if ("enableMMS".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_MMS;
            } else if ("enableSUPL".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_SUPL;
            } else if ("enableDUN".equals(feature) || "enableDUNAlways".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_DUN;
            } else if ("enableHIPRI".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_INTERNET;
            } else if ("enableFOTA".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_FOTA;
            } else if ("enableIMS".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_IMS;
            } else if ("enableCBS".equals(feature)) {
                cap = NetworkCapabilities.NET_CAPABILITY_CBS;
            } else {
                return null;
            }
            NetworkCapabilities netCap = new NetworkCapabilities();
            netCap.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
            netCap.addNetworkCapability(cap);
            maybeMarkCapabilitiesRestricted(netCap);
            return netCap;
        } else if (networkType == TYPE_WIFI) {
            if ("p2p".equals(feature)) {
                NetworkCapabilities netCap = new NetworkCapabilities();
                netCap.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
                netCap.addNetworkCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P);
                maybeMarkCapabilitiesRestricted(netCap);
                return netCap;
            }
        }
        return null;
    }

    private int legacyTypeForNetworkCapabilities(NetworkCapabilities netCap) {
        if (netCap == null) return TYPE_NONE;
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS)) {
            return TYPE_MOBILE_CBS;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
            return TYPE_MOBILE_IMS;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)) {
            return TYPE_MOBILE_FOTA;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN)) {
            return TYPE_MOBILE_DUN;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
            return TYPE_MOBILE_SUPL;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
            return TYPE_MOBILE_MMS;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return TYPE_MOBILE_HIPRI;
        }
        if (netCap.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P)) {
            return TYPE_WIFI_P2P;
        }
        return TYPE_NONE;
    }

    private static class LegacyRequest {
        NetworkCapabilities networkCapabilities;
        NetworkRequest networkRequest;
        int expireSequenceNumber;
        Network currentNetwork;
        int delay = -1;
        NetworkCallbackListener networkCallbackListener = new NetworkCallbackListener() {
            @Override
            public void onAvailable(NetworkRequest request, Network network) {
                currentNetwork = network;
                Log.d(TAG, "startUsingNetworkFeature got Network:" + network);
                setProcessDefaultNetworkForHostResolution(network);
            }
            @Override
            public void onLost(NetworkRequest request, Network network) {
                if (network.equals(currentNetwork)) {
                    currentNetwork = null;
                    setProcessDefaultNetworkForHostResolution(null);
                }
                Log.d(TAG, "startUsingNetworkFeature lost Network:" + network);
            }
        };
    }

    private HashMap<NetworkCapabilities, LegacyRequest> sLegacyRequests =
            new HashMap<NetworkCapabilities, LegacyRequest>();

    private NetworkRequest findRequestForFeature(NetworkCapabilities netCap) {
        synchronized (sLegacyRequests) {
            LegacyRequest l = sLegacyRequests.get(netCap);
            if (l != null) return l.networkRequest;
        }
        return null;
    }

    private void renewRequestLocked(LegacyRequest l) {
        l.expireSequenceNumber++;
        Log.d(TAG, "renewing request to seqNum " + l.expireSequenceNumber);
        sendExpireMsgForFeature(l.networkCapabilities, l.expireSequenceNumber, l.delay);
    }

    private void expireRequest(NetworkCapabilities netCap, int sequenceNum) {
        int ourSeqNum = -1;
        synchronized (sLegacyRequests) {
            LegacyRequest l = sLegacyRequests.get(netCap);
            if (l == null) return;
            ourSeqNum = l.expireSequenceNumber;
            if (l.expireSequenceNumber == sequenceNum) {
                releaseNetworkRequest(l.networkRequest);
                sLegacyRequests.remove(netCap);
            }
        }
        Log.d(TAG, "expireRequest with " + ourSeqNum + ", " + sequenceNum);
    }

    private NetworkRequest requestNetworkForFeatureLocked(NetworkCapabilities netCap) {
        int delay = -1;
        int type = legacyTypeForNetworkCapabilities(netCap);
        try {
            delay = mService.getRestoreDefaultNetworkDelay(type);
        } catch (RemoteException e) {}
        LegacyRequest l = new LegacyRequest();
        l.networkCapabilities = netCap;
        l.delay = delay;
        l.expireSequenceNumber = 0;
        l.networkRequest = sendRequestForNetwork(netCap, l.networkCallbackListener, 0,
                REQUEST, type);
        if (l.networkRequest == null) return null;
        sLegacyRequests.put(netCap, l);
        sendExpireMsgForFeature(netCap, l.expireSequenceNumber, delay);
        return l.networkRequest;
    }

    private void sendExpireMsgForFeature(NetworkCapabilities netCap, int seqNum, int delay) {
        if (delay >= 0) {
            Log.d(TAG, "sending expire msg with seqNum " + seqNum + " and delay " + delay);
            Message msg = sCallbackHandler.obtainMessage(EXPIRE_LEGACY_REQUEST, seqNum, 0, netCap);
            sCallbackHandler.sendMessageDelayed(msg, delay);
        }
    }

    private NetworkRequest removeRequestForFeature(NetworkCapabilities netCap) {
        synchronized (sLegacyRequests) {
            LegacyRequest l = sLegacyRequests.remove(netCap);
            if (l == null) return null;
            return l.networkRequest;
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
     *
     * @deprecated Deprecated in favor of the {@link #requestNetwork},
     *             {@link #setProcessDefaultNetwork} and {@link Network#getSocketFactory} api.
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
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE}.
     * @param networkType the type of the network over which traffic to the specified
     * host is to be routed
     * @param hostAddress the IP address of the host to which the route is desired
     * @return {@code true} on success, {@code false} on failure
     * @hide
     * @deprecated Deprecated in favor of the {@link #requestNetwork} and
     *             {@link #setProcessDefaultNetwork} api.
     */
    public boolean requestRouteToHostAddress(int networkType, InetAddress hostAddress) {
        byte[] address = hostAddress.getAddress();
        try {
            return mService.requestRouteToHostAddress(networkType, address, mPackageName);
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
     * @hide
     * @deprecated Talk to TelephonyManager directly
     */
    public boolean getMobileDataEnabled() {
        IBinder b = ServiceManager.getService(Context.TELEPHONY_SERVICE);
        if (b != null) {
            try {
                ITelephony it = ITelephony.Stub.asInterface(b);
                return it.getDataEnabled();
            } catch (RemoteException e) { }
        }
        return false;
    }

    /**
     * Callback for use with {@link ConnectivityManager#registerNetworkActiveListener} to
     * find out when the current network has gone in to a high power state.
     */
    public interface OnNetworkActiveListener {
        /**
         * Called on the main thread of the process to report that the current data network
         * has become active, and it is now a good time to perform any pending network
         * operations.  Note that this listener only tells you when the network becomes
         * active; if at any other time you want to know whether it is active (and thus okay
         * to initiate network traffic), you can retrieve its instantaneous state with
         * {@link ConnectivityManager#isNetworkActive}.
         */
        public void onNetworkActive();
    }

    private INetworkManagementService getNetworkManagementService() {
        synchronized (this) {
            if (mNMService != null) {
                return mNMService;
            }
            IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
            mNMService = INetworkManagementService.Stub.asInterface(b);
            return mNMService;
        }
    }

    private final ArrayMap<OnNetworkActiveListener, INetworkActivityListener>
            mNetworkActivityListeners
                    = new ArrayMap<OnNetworkActiveListener, INetworkActivityListener>();

    /**
     * Start listening to reports when the data network is active, meaning it is
     * a good time to perform network traffic.  Use {@link #isNetworkActive()}
     * to determine the current state of the network after registering the listener.
     *
     * @param l The listener to be told when the network is active.
     */
    public void registerNetworkActiveListener(final OnNetworkActiveListener l) {
        INetworkActivityListener rl = new INetworkActivityListener.Stub() {
            @Override
            public void onNetworkActive() throws RemoteException {
                l.onNetworkActive();
            }
        };

        try {
            getNetworkManagementService().registerNetworkActivityListener(rl);
            mNetworkActivityListeners.put(l, rl);
        } catch (RemoteException e) {
        }
    }

    /**
     * Remove network active listener previously registered with
     * {@link #registerNetworkActiveListener}.
     *
     * @param l Previously registered listener.
     */
    public void unregisterNetworkActiveListener(OnNetworkActiveListener l) {
        INetworkActivityListener rl = mNetworkActivityListeners.get(l);
        if (rl == null) {
            throw new IllegalArgumentException("Listener not registered: " + l);
        }
        try {
            getNetworkManagementService().unregisterNetworkActivityListener(rl);
        } catch (RemoteException e) {
        }
    }

    /**
     * Return whether the data network is currently active.  An active network means that
     * it is currently in a high power state for performing data transmission.  On some
     * types of networks, it may be expensive to move and stay in such a state, so it is
     * more power efficient to batch network traffic together when the radio is already in
     * this state.  This method tells you whether right now is currently a good time to
     * initiate network traffic, as the network is already active.
     */
    public boolean isNetworkActive() {
        try {
            return getNetworkManagementService().isNetworkActive();
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * {@hide}
     */
    public ConnectivityManager(IConnectivityManager service, String packageName) {
        mService = checkNotNull(service, "missing IConnectivityManager");
        mPackageName = checkNotNull(packageName, "missing package name");
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
     * {@code ro.tether.denied} system property, Settings.TETHER_SUPPORTED or
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
     * Report a problem network to the framework.  This provides a hint to the system
     * that there might be connectivity problems on this network and may cause 
     * the framework to re-evaluate network connectivity and/or switch to another
     * network.
     *
     * @param network The {@link Network} the application was attempting to use
     *                or {@code null} to indicate the current default network.
     */
    public void reportBadNetwork(Network network) {
        try {
            mService.reportBadNetwork(network);
        } catch (RemoteException e) {
        }
    }

    /**
     * Set a network-independent global http proxy.  This is not normally what you want
     * for typical HTTP proxies - they are general network dependent.  However if you're
     * doing something unusual like general internal filtering this may be useful.  On
     * a private network where the proxy is not accessible, you may break HTTP using this.
     *
     * @param p The a {@link ProxyInfo} object defining the new global
     *        HTTP proxy.  A {@code null} value will clear the global HTTP proxy.
     *
     * <p>This method requires the call to hold the permission
     * android.Manifest.permission#CONNECTIVITY_INTERNAL.
     * @hide
     */
    public void setGlobalProxy(ProxyInfo p) {
        try {
            mService.setGlobalProxy(p);
        } catch (RemoteException e) {
        }
    }

    /**
     * Retrieve any network-independent global HTTP proxy.
     *
     * @return {@link ProxyInfo} for the current global HTTP proxy or {@code null}
     *        if no global HTTP proxy is set.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     * @hide
     */
    public ProxyInfo getGlobalProxy() {
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
     * @return the {@link ProxyInfo} for the current HTTP proxy, or {@code null} if no
     *        HTTP proxy is active.
     *
     * <p>This method requires the call to hold the permission
     * {@link android.Manifest.permission#ACCESS_NETWORK_STATE}.
     * {@hide}
     * @deprecated Deprecated in favor of {@link #getLinkProperties}
     */
    public ProxyInfo getProxy() {
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
     * @param networkType NetworkType to set
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

    /** {@hide} */
    public void registerNetworkFactory(Messenger messenger, String name) {
        try {
            mService.registerNetworkFactory(messenger, name);
        } catch (RemoteException e) { }
    }

    /** {@hide} */
    public void unregisterNetworkFactory(Messenger messenger) {
        try {
            mService.unregisterNetworkFactory(messenger);
        } catch (RemoteException e) { }
    }

    /** {@hide} */
    public void registerNetworkAgent(Messenger messenger, NetworkInfo ni, LinkProperties lp,
            NetworkCapabilities nc, int score) {
        try {
            mService.registerNetworkAgent(messenger, ni, lp, nc, score);
        } catch (RemoteException e) { }
    }

    /**
     * Base class for NetworkRequest callbacks.  Used for notifications about network
     * changes.  Should be extended by applications wanting notifications.
     */
    public static class NetworkCallbackListener {
        /** @hide */
        public static final int PRECHECK     = 1;
        /** @hide */
        public static final int AVAILABLE    = 2;
        /** @hide */
        public static final int LOSING       = 3;
        /** @hide */
        public static final int LOST         = 4;
        /** @hide */
        public static final int UNAVAIL      = 5;
        /** @hide */
        public static final int CAP_CHANGED  = 6;
        /** @hide */
        public static final int PROP_CHANGED = 7;
        /** @hide */
        public static final int CANCELED     = 8;

        /**
         * @hide
         * Called whenever the framework connects to a network that it may use to
         * satisfy this request
         */
        public void onPreCheck(NetworkRequest networkRequest, Network network) {}

        /**
         * Called when the framework connects and has declared new network ready for use.
         *
         * @param networkRequest The {@link NetworkRequest} used to initiate the request.
         * @param network The {@link Network} of the satisfying network.
         */
        public void onAvailable(NetworkRequest networkRequest, Network network) {}

        /**
         * Called when the network is about to be disconnected.  Often paired with an
         * {@link NetworkCallbackListener#onAvailable} call with the new replacement network
         * for graceful handover.  This may not be called if we have a hard loss
         * (loss without warning).  This may be followed by either a
         * {@link NetworkCallbackListener#onLost} call or a
         * {@link NetworkCallbackListener#onAvailable} call for this network depending
         * on whether we lose or regain it.
         *
         * @param networkRequest The {@link NetworkRequest} used to initiate the request.
         * @param network The {@link Network} of the failing network.
         * @param maxSecToLive The time in seconds the framework will attempt to keep the
         *                     network connected.  Note that the network may suffers a
         *                     hard loss at any time.
         */
        public void onLosing(NetworkRequest networkRequest, Network network, int maxSecToLive) {}

        /**
         * Called when the framework has a hard loss of the network or when the
         * graceful failure ends.
         *
         * @param networkRequest The {@link NetworkRequest} used to initiate the request.
         * @param network The {@link Network} lost.
         */
        public void onLost(NetworkRequest networkRequest, Network network) {}

        /**
         * Called if no network is found in the given timeout time.  If no timeout is given,
         * this will not be called.
         * @hide
         */
        public void onUnavailable(NetworkRequest networkRequest) {}

        /**
         * Called when the network the framework connected to for this request
         * changes capabilities but still satisfies the stated need.
         *
         * @param networkRequest The {@link NetworkRequest} used to initiate the request.
         * @param network The {@link Network} whose capabilities have changed.
         * @param networkCapabilities The new {@link NetworkCapabilities} for this network.
         */
        public void onNetworkCapabilitiesChanged(NetworkRequest networkRequest, Network network,
                NetworkCapabilities networkCapabilities) {}

        /**
         * Called when the network the framework connected to for this request
         * changes {@link LinkProperties}.
         *
         * @param networkRequest The {@link NetworkRequest} used to initiate the request.
         * @param network The {@link Network} whose link properties have changed.
         * @param linkProperties The new {@link LinkProperties} for this network.
         */
        public void onLinkPropertiesChanged(NetworkRequest networkRequest, Network network,
                LinkProperties linkProperties) {}

        /**
         * Called when a {@link #releaseNetworkRequest} call concludes and the registered
         * callbacks will no longer be used.
         *
         * @param networkRequest The {@link NetworkRequest} used to initiate the request.
         */
        public void onReleased(NetworkRequest networkRequest) {}
    }

    private static final int BASE = Protocol.BASE_CONNECTIVITY_MANAGER;
    /** @hide obj = pair(NetworkRequest, Network) */
    public static final int CALLBACK_PRECHECK           = BASE + 1;
    /** @hide obj = pair(NetworkRequest, Network) */
    public static final int CALLBACK_AVAILABLE          = BASE + 2;
    /** @hide obj = pair(NetworkRequest, Network), arg1 = ttl */
    public static final int CALLBACK_LOSING             = BASE + 3;
    /** @hide obj = pair(NetworkRequest, Network) */
    public static final int CALLBACK_LOST               = BASE + 4;
    /** @hide obj = NetworkRequest */
    public static final int CALLBACK_UNAVAIL            = BASE + 5;
    /** @hide obj = pair(NetworkRequest, Network) */
    public static final int CALLBACK_CAP_CHANGED        = BASE + 6;
    /** @hide obj = pair(NetworkRequest, Network) */
    public static final int CALLBACK_IP_CHANGED         = BASE + 7;
    /** @hide obj = NetworkRequest */
    public static final int CALLBACK_RELEASED           = BASE + 8;
    /** @hide */
    public static final int CALLBACK_EXIT               = BASE + 9;
    /** @hide obj = NetworkCapabilities, arg1 = seq number */
    private static final int EXPIRE_LEGACY_REQUEST      = BASE + 10;

    private class CallbackHandler extends Handler {
        private final HashMap<NetworkRequest, NetworkCallbackListener>mCallbackMap;
        private final AtomicInteger mRefCount;
        private static final String TAG = "ConnectivityManager.CallbackHandler";
        private final ConnectivityManager mCm;

        CallbackHandler(Looper looper, HashMap<NetworkRequest, NetworkCallbackListener>callbackMap,
                AtomicInteger refCount, ConnectivityManager cm) {
            super(looper);
            mCallbackMap = callbackMap;
            mRefCount = refCount;
            mCm = cm;
        }

        @Override
        public void handleMessage(Message message) {
            Log.d(TAG, "CM callback handler got msg " + message.what);
            switch (message.what) {
                case CALLBACK_PRECHECK: {
                    NetworkRequest request = getNetworkRequest(message);
                    NetworkCallbackListener callbacks = getCallbacks(request);
                    if (callbacks != null) {
                        callbacks.onPreCheck(request, getNetwork(message));
                    } else {
                        Log.e(TAG, "callback not found for PRECHECK message");
                    }
                    break;
                }
                case CALLBACK_AVAILABLE: {
                    NetworkRequest request = getNetworkRequest(message);
                    NetworkCallbackListener callbacks = getCallbacks(request);
                    if (callbacks != null) {
                        callbacks.onAvailable(request, getNetwork(message));
                    } else {
                        Log.e(TAG, "callback not found for AVAILABLE message");
                    }
                    break;
                }
                case CALLBACK_LOSING: {
                    NetworkRequest request = getNetworkRequest(message);
                    NetworkCallbackListener callbacks = getCallbacks(request);
                    if (callbacks != null) {
                        callbacks.onLosing(request, getNetwork(message), message.arg1);
                    } else {
                        Log.e(TAG, "callback not found for LOSING message");
                    }
                    break;
                }
                case CALLBACK_LOST: {
                    NetworkRequest request = getNetworkRequest(message);
                    NetworkCallbackListener callbacks = getCallbacks(request);
                    if (callbacks != null) {
                        callbacks.onLost(request, getNetwork(message));
                    } else {
                        Log.e(TAG, "callback not found for LOST message");
                    }
                    break;
                }
                case CALLBACK_UNAVAIL: {
                    NetworkRequest req = (NetworkRequest)message.obj;
                    NetworkCallbackListener callbacks = null;
                    synchronized(mCallbackMap) {
                        callbacks = mCallbackMap.get(req);
                    }
                    if (callbacks != null) {
                        callbacks.onUnavailable(req);
                    } else {
                        Log.e(TAG, "callback not found for UNAVAIL message");
                    }
                    break;
                }
                case CALLBACK_CAP_CHANGED: {
                    NetworkRequest request = getNetworkRequest(message);
                    NetworkCallbackListener callbacks = getCallbacks(request);
                    if (callbacks != null) {
                        Network network = getNetwork(message);
                        NetworkCapabilities cap = mCm.getNetworkCapabilities(network);

                        callbacks.onNetworkCapabilitiesChanged(request, network, cap);
                    } else {
                        Log.e(TAG, "callback not found for CHANGED message");
                    }
                    break;
                }
                case CALLBACK_IP_CHANGED: {
                    NetworkRequest request = getNetworkRequest(message);
                    NetworkCallbackListener callbacks = getCallbacks(request);
                    if (callbacks != null) {
                        Network network = getNetwork(message);
                        LinkProperties lp = mCm.getLinkProperties(network);

                        callbacks.onLinkPropertiesChanged(request, network, lp);
                    } else {
                        Log.e(TAG, "callback not found for CHANGED message");
                    }
                    break;
                }
                case CALLBACK_RELEASED: {
                    NetworkRequest req = (NetworkRequest)message.obj;
                    NetworkCallbackListener callbacks = null;
                    synchronized(mCallbackMap) {
                        callbacks = mCallbackMap.remove(req);
                    }
                    if (callbacks != null) {
                        callbacks.onReleased(req);
                    } else {
                        Log.e(TAG, "callback not found for CANCELED message");
                    }
                    synchronized(mRefCount) {
                        if (mRefCount.decrementAndGet() == 0) {
                            getLooper().quit();
                        }
                    }
                    break;
                }
                case CALLBACK_EXIT: {
                    Log.d(TAG, "Listener quiting");
                    getLooper().quit();
                    break;
                }
                case EXPIRE_LEGACY_REQUEST: {
                    expireRequest((NetworkCapabilities)message.obj, message.arg1);
                    break;
                }
            }
        }

        private NetworkRequest getNetworkRequest(Message msg) {
            return (NetworkRequest)(msg.obj);
        }
        private NetworkCallbackListener getCallbacks(NetworkRequest req) {
            synchronized(mCallbackMap) {
                return mCallbackMap.get(req);
            }
        }
        private Network getNetwork(Message msg) {
            return new Network(msg.arg2);
        }
        private NetworkCallbackListener removeCallbacks(Message msg) {
            NetworkRequest req = (NetworkRequest)msg.obj;
            synchronized(mCallbackMap) {
                return mCallbackMap.remove(req);
            }
        }
    }

    private void addCallbackListener() {
        synchronized(sCallbackRefCount) {
            if (sCallbackRefCount.incrementAndGet() == 1) {
                // TODO - switch this over to a ManagerThread or expire it when done
                HandlerThread callbackThread = new HandlerThread("ConnectivityManager");
                callbackThread.start();
                sCallbackHandler = new CallbackHandler(callbackThread.getLooper(),
                        sNetworkCallbackListener, sCallbackRefCount, this);
            }
        }
    }

    private void removeCallbackListener() {
        synchronized(sCallbackRefCount) {
            if (sCallbackRefCount.decrementAndGet() == 0) {
                sCallbackHandler.obtainMessage(CALLBACK_EXIT).sendToTarget();
                sCallbackHandler = null;
            }
        }
    }

    static final HashMap<NetworkRequest, NetworkCallbackListener> sNetworkCallbackListener =
            new HashMap<NetworkRequest, NetworkCallbackListener>();
    static final AtomicInteger sCallbackRefCount = new AtomicInteger(0);
    static CallbackHandler sCallbackHandler = null;

    private final static int LISTEN  = 1;
    private final static int REQUEST = 2;

    private NetworkRequest sendRequestForNetwork(NetworkCapabilities need,
            NetworkCallbackListener networkCallbackListener, int timeoutSec, int action,
            int legacyType) {
        NetworkRequest networkRequest = null;
        if (networkCallbackListener == null) {
            throw new IllegalArgumentException("null NetworkCallbackListener");
        }
        if (need == null) throw new IllegalArgumentException("null NetworkCapabilities");
        try {
            addCallbackListener();
            if (action == LISTEN) {
                networkRequest = mService.listenForNetwork(need, new Messenger(sCallbackHandler),
                        new Binder());
            } else {
                networkRequest = mService.requestNetwork(need, new Messenger(sCallbackHandler),
                        timeoutSec, new Binder(), legacyType);
            }
            if (networkRequest != null) {
                synchronized(sNetworkCallbackListener) {
                    sNetworkCallbackListener.put(networkRequest, networkCallbackListener);
                }
            }
        } catch (RemoteException e) {}
        if (networkRequest == null) removeCallbackListener();
        return networkRequest;
    }

    /**
     * Request a network to satisfy a set of {@link NetworkCapabilities}.
     *
     * This {@link NetworkRequest} will live until released via
     * {@link #releaseNetworkRequest} or the calling application exits.
     * Status of the request can be followed by listening to the various
     * callbacks described in {@link NetworkCallbackListener}.  The {@link Network}
     * can be used to direct traffic to the network.
     *
     * @param need {@link NetworkCapabilities} required by this request.
     * @param networkCallbackListener The {@link NetworkCallbackListener} to be utilized for this
     *                         request.  Note the callbacks can be shared by multiple
     *                         requests and the NetworkRequest token utilized to
     *                         determine to which request the callback relates.
     * @return A {@link NetworkRequest} object identifying the request.
     */
    public NetworkRequest requestNetwork(NetworkCapabilities need,
            NetworkCallbackListener networkCallbackListener) {
        return sendRequestForNetwork(need, networkCallbackListener, 0, REQUEST, TYPE_NONE);
    }

    /**
     * Request a network to satisfy a set of {@link NetworkCapabilities}, limited
     * by a timeout.
     *
     * This function behaves identically to the non-timedout version, but if a suitable
     * network is not found within the given time (in Seconds) the
     * {@link NetworkCallbackListener#unavailable} callback is called.  The request must
     * still be released normally by calling {@link releaseNetworkRequest}.
     * @param need {@link NetworkCapabilities} required by this request.
     * @param networkCallbackListener The callbacks to be utilized for this request.  Note
     *                         the callbacks can be shared by multiple requests and
     *                         the NetworkRequest token utilized to determine to which
     *                         request the callback relates.
     * @param timeoutSec The time in seconds to attempt looking for a suitable network
     *                   before {@link NetworkCallbackListener#unavailable} is called.
     * @return A {@link NetworkRequest} object identifying the request.
     * @hide
     */
    public NetworkRequest requestNetwork(NetworkCapabilities need,
            NetworkCallbackListener networkCallbackListener, int timeoutSec) {
        return sendRequestForNetwork(need, networkCallbackListener, timeoutSec, REQUEST,
                TYPE_NONE);
    }

    /**
     * The maximum number of seconds the framework will look for a suitable network
     * during a timeout-equiped call to {@link requestNetwork}.
     * {@hide}
     */
    public final static int MAX_NETWORK_REQUEST_TIMEOUT_SEC = 100 * 60;

    /**
     * The lookup key for a {@link Network} object included with the intent after
     * succesfully finding a network for the applications request.  Retrieve it with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_REQUEST_NETWORK = "networkRequestNetwork";

    /**
     * The lookup key for a {@link NetworkCapabilities} object included with the intent after
     * succesfully finding a network for the applications request.  Retrieve it with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_NETWORK_REQUEST_NETWORK_CAPABILITIES =
            "networkRequestNetworkCapabilities";


    /**
     * Request a network to satisfy a set of {@link NetworkCapabilities}.
     *
     * This function behavies identically to the callback-equiped version, but instead
     * of {@link NetworkCallbackListener} a {@link PendingIntent} is used.  This means
     * the request may outlive the calling application and get called back when a suitable
     * network is found.
     * <p>
     * The operation is an Intent broadcast that goes to a broadcast receiver that
     * you registered with {@link Context#registerReceiver} or through the
     * &lt;receiver&gt; tag in an AndroidManifest.xml file
     * <p>
     * The operation Intent is delivered with two extras, a {@link Network} typed
     * extra called {@link #EXTRA_NETWORK_REQUEST_NETWORK} and a {@link NetworkCapabilities}
     * typed extra called {@link #EXTRA_NETWORK_REQUEST_NETWORK_CAPABILITIES} containing
     * the original requests parameters.  It is important to create a new,
     * {@link NetworkCallbackListener} based request before completing the processing of the
     * Intent to reserve the network or it will be released shortly after the Intent
     * is processed.
     * <p>
     * If there is already an request for this Intent registered (with the equality of
     * two Intents defined by {@link Intent#filterEquals}), then it will be removed and
     * replaced by this one, effectively releasing the previous {@link NetworkRequest}.
     * <p>
     * The request may be released normally by calling {@link #releaseNetworkRequest}.
     *
     * @param need {@link NetworkCapabilities} required by this request.
     * @param operation Action to perform when the network is available (corresponds
     *                  to the {@link NetworkCallbackListener#onAvailable} call.  Typically
     *                  comes from {@link PendingIntent#getBroadcast}.
     * @return A {@link NetworkRequest} object identifying the request.
     */
    public NetworkRequest requestNetwork(NetworkCapabilities need, PendingIntent operation) {
        try {
            return mService.pendingRequestForNetwork(need, operation);
        } catch (RemoteException e) {}
        return null;
    }

    /**
     * Registers to receive notifications about all networks which satisfy the given
     * {@link NetworkCapabilities}.  The callbacks will continue to be called until
     * either the application exits or the request is released using
     * {@link #releaseNetworkRequest}.
     *
     * @param need {@link NetworkCapabilities} required by this request.
     * @param networkCallbackListener The {@link NetworkCallbackListener} to be called as suitable
     *                         networks change state.
     * @return A {@link NetworkRequest} object identifying the request.
     */
    public NetworkRequest listenForNetwork(NetworkCapabilities need,
            NetworkCallbackListener networkCallbackListener) {
        return sendRequestForNetwork(need, networkCallbackListener, 0, LISTEN, TYPE_NONE);
    }

    /**
     * Releases a {@link NetworkRequest} generated either through a {@link #requestNetwork}
     * or a {@link #listenForNetwork} call.  The {@link NetworkCallbackListener} given in the
     * earlier call may continue receiving calls until the
     * {@link NetworkCallbackListener#onReleased} function is called, signifying the end
     * of the request.
     *
     * @param networkRequest The {@link NetworkRequest} generated by an earlier call to
     *                       {@link #requestNetwork} or {@link #listenForNetwork}.
     */
    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        if (networkRequest == null) throw new IllegalArgumentException("null NetworkRequest");
        try {
            mService.releaseNetworkRequest(networkRequest);
        } catch (RemoteException e) {}
    }

    /**
     * Binds the current process to {@code network}.  All Sockets created in the future
     * (and not explicitly bound via a bound SocketFactory from
     * {@link Network#getSocketFactory() Network.getSocketFactory()}) will be bound to
     * {@code network}.  All host name resolutions will be limited to {@code network} as well.
     * Note that if {@code network} ever disconnects, all Sockets created in this way will cease to
     * work and all host name resolutions will fail.  This is by design so an application doesn't
     * accidentally use Sockets it thinks are still bound to a particular {@link Network}.
     * To clear binding pass {@code null} for {@code network}.  Using individually bound
     * Sockets created by Network.getSocketFactory().createSocket() and
     * performing network-specific host name resolutions via
     * {@link Network#getAllByName Network.getAllByName} is preferred to calling
     * {@code setProcessDefaultNetwork}.
     *
     * @param network The {@link Network} to bind the current process to, or {@code null} to clear
     *                the current binding.
     * @return {@code true} on success, {@code false} if the {@link Network} is no longer valid.
     */
    public static boolean setProcessDefaultNetwork(Network network) {
        if (network == null) {
            NetworkUtils.unbindProcessToNetwork();
        } else {
            NetworkUtils.bindProcessToNetwork(network.netId);
        }
        // TODO fix return value
        return true;
    }

    /**
     * Returns the {@link Network} currently bound to this process via
     * {@link #setProcessDefaultNetwork}, or {@code null} if no {@link Network} is explicitly bound.
     *
     * @return {@code Network} to which this process is bound, or {@code null}.
     */
    public static Network getProcessDefaultNetwork() {
        int netId = NetworkUtils.getNetworkBoundToProcess();
        if (netId == 0) return null;
        return new Network(netId);
    }

    /**
     * Binds host resolutions performed by this process to {@code network}.
     * {@link #setProcessDefaultNetwork} takes precedence over this setting.
     *
     * @param network The {@link Network} to bind host resolutions from the current process to, or
     *                {@code null} to clear the current binding.
     * @return {@code true} on success, {@code false} if the {@link Network} is no longer valid.
     * @hide
     * @deprecated This is strictly for legacy usage to support {@link #startUsingNetworkFeature}.
     */
    public static boolean setProcessDefaultNetworkForHostResolution(Network network) {
        if (network == null) {
            NetworkUtils.unbindProcessToNetworkForHostResolution();
        } else {
            NetworkUtils.bindProcessToNetworkForHostResolution(network.netId);
        }
        // TODO hook up the return value.
        return true;
    }
}
