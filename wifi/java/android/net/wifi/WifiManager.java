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

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.READ_WIFI_CREDENTIAL;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.server.net.NetworkPinner;

import dalvik.system.CloseGuard;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * This class provides the primary API for managing all aspects of Wi-Fi
 * connectivity.
 * <p>
 * On releases before {@link android.os.Build.VERSION_CODES#N}, this object
 * should only be obtained from an {@linkplain Context#getApplicationContext()
 * application context}, and not from any other derived context to avoid memory
 * leaks within the calling process.
 * <p>
 * It deals with several categories of items:
 * </p>
 * <ul>
 * <li>The list of configured networks. The list can be viewed and updated, and
 * attributes of individual entries can be modified.</li>
 * <li>The currently active Wi-Fi network, if any. Connectivity can be
 * established or torn down, and dynamic information about the state of the
 * network can be queried.</li>
 * <li>Results of access point scans, containing enough information to make
 * decisions about what access point to connect to.</li>
 * <li>It defines the names of various Intent actions that are broadcast upon
 * any sort of change in Wi-Fi state.
 * </ul>
 * <p>
 * This is the API to use when performing Wi-Fi specific operations. To perform
 * operations that pertain to network connectivity at an abstract level, use
 * {@link android.net.ConnectivityManager}.
 * </p>
 */
@SystemService(Context.WIFI_SERVICE)
public class WifiManager {

    private static final String TAG = "WifiManager";
    // Supplicant error codes:
    /**
     * The error code if there was a problem authenticating.
     * @deprecated This is no longer supported.
     */
    @Deprecated
    public static final int ERROR_AUTHENTICATING = 1;

    /**
     * The reason code if there is no error during authentication.
     * It could also imply that there no authentication in progress,
     * this reason code also serves as a reset value.
     * @deprecated This is no longer supported.
     * @hide
     */
    @Deprecated
    public static final int ERROR_AUTH_FAILURE_NONE = 0;

    /**
     * The reason code if there was a timeout authenticating.
     * @deprecated This is no longer supported.
     * @hide
     */
    @Deprecated
    public static final int ERROR_AUTH_FAILURE_TIMEOUT = 1;

    /**
     * The reason code if there was a wrong password while
     * authenticating.
     * @deprecated This is no longer supported.
     * @hide
     */
    @Deprecated
    public static final int ERROR_AUTH_FAILURE_WRONG_PSWD = 2;

    /**
     * The reason code if there was EAP failure while
     * authenticating.
     * @deprecated This is no longer supported.
     * @hide
     */
    @Deprecated
    public static final int ERROR_AUTH_FAILURE_EAP_FAILURE = 3;

    /**
     * Maximum number of active network suggestions allowed per app.
     * @hide
     */
    public static final int NETWORK_SUGGESTIONS_MAX_PER_APP =
            ActivityManager.isLowRamDeviceStatic() ? 256 : 1024;

    /**
     * Reason code if all of the network suggestions were successfully added or removed.
     */
    public static final int STATUS_NETWORK_SUGGESTIONS_SUCCESS = 0;

    /**
     * Reason code if there was an internal error in the platform while processing the addition or
     * removal of suggestions.
     */
    public static final int STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL = 1;

    /**
     * Reason code if the user has disallowed "android:change_wifi_state" app-ops from the app.
     * @see android.app.AppOpsManager#unsafeCheckOp(String, int, String).
     */
    public static final int STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED = 2;

    /**
     * Reason code if one or more of the network suggestions added already exists in platform's
     * database.
     * @see WifiNetworkSuggestion#equals(Object)
     */
    public static final int STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE = 3;

    /**
     * Reason code if the number of network suggestions provided by the app crosses the max
     * threshold set per app.
     * @see #getMaxNumberOfNetworkSuggestionsPerApp()
     */
    public static final int STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP = 4;

    /**
     * Reason code if one or more of the network suggestions removed does not exist in platform's
     * database.
     */
    public static final int STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID = 5;

    /** @hide */
    @IntDef(prefix = { "STATUS_NETWORK_SUGGESTIONS_" }, value = {
            STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL,
            STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED,
            STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
            STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP,
            STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkSuggestionsStatusCode {}

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
     * Broadcast intent action indicating that the credential of a Wi-Fi network
     * has been changed. One extra provides the ssid of the network. Another
     * extra provides the event type, whether the credential is saved or forgot.
     * @hide
     */
    @SystemApi
    public static final String WIFI_CREDENTIAL_CHANGED_ACTION =
            "android.net.wifi.WIFI_CREDENTIAL_CHANGED";
    /** @hide */
    @SystemApi
    public static final String EXTRA_WIFI_CREDENTIAL_EVENT_TYPE = "et";
    /** @hide */
    @SystemApi
    public static final String EXTRA_WIFI_CREDENTIAL_SSID = "ssid";
    /** @hide */
    @SystemApi
    public static final int WIFI_CREDENTIAL_SAVED = 0;
    /** @hide */
    @SystemApi
    public static final int WIFI_CREDENTIAL_FORGOT = 1;

    /** @hide */
    @SystemApi
    public static final int PASSPOINT_HOME_NETWORK = 0;

    /** @hide */
    @SystemApi
    public static final int PASSPOINT_ROAMING_NETWORK = 1;

    /**
     * Broadcast intent action indicating that a Passpoint provider icon has been received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_FILENAME}
     * {@link #EXTRA_ICON}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_ICON = "android.net.wifi.action.PASSPOINT_ICON";
    /**
     * BSSID of an AP in long representation.  The {@link #EXTRA_BSSID} contains BSSID in
     * String representation.
     *
     * Retrieve with {@link android.content.Intent#getLongExtra(String, long)}.
     *
     * @hide
     */
    public static final String EXTRA_BSSID_LONG = "android.net.wifi.extra.BSSID_LONG";
    /**
     * Icon data.
     *
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)} and cast into
     * {@link android.graphics.drawable.Icon}.
     *
     * @hide
     */
    public static final String EXTRA_ICON = "android.net.wifi.extra.ICON";
    /**
     * Name of a file.
     *
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_FILENAME = "android.net.wifi.extra.FILENAME";

    /**
     * Broadcast intent action indicating a Passpoint OSU Providers List element has been received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_ANQP_ELEMENT_DATA}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_OSU_PROVIDERS_LIST =
            "android.net.wifi.action.PASSPOINT_OSU_PROVIDERS_LIST";
    /**
     * Raw binary data of an ANQP (Access Network Query Protocol) element.
     *
     * Retrieve with {@link android.content.Intent#getByteArrayExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_ANQP_ELEMENT_DATA =
            "android.net.wifi.extra.ANQP_ELEMENT_DATA";

    /**
     * Broadcast intent action indicating that a Passpoint Deauth Imminent frame has been received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_ESS}
     * {@link #EXTRA_DELAY}
     * {@link #EXTRA_URL}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_DEAUTH_IMMINENT =
            "android.net.wifi.action.PASSPOINT_DEAUTH_IMMINENT";
    /**
     * Flag indicating BSS (Basic Service Set) or ESS (Extended Service Set). This will be set to
     * {@code true} for ESS.
     *
     * Retrieve with {@link android.content.Intent#getBooleanExtra(String, boolean)}.
     *
     * @hide
     */
    public static final String EXTRA_ESS = "android.net.wifi.extra.ESS";
    /**
     * Delay in seconds.
     *
     * Retrieve with {@link android.content.Intent#getIntExtra(String, int)}.
     *
     * @hide
     */
    public static final String EXTRA_DELAY = "android.net.wifi.extra.DELAY";
    /**
     * String representation of an URL.
     *
     * Retrieve with {@link android.content.Intent#getStringExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_URL = "android.net.wifi.extra.URL";

    /**
     * Broadcast intent action indicating a Passpoint subscription remediation frame has been
     * received.
     *
     * Included extras:
     * {@link #EXTRA_BSSID_LONG}
     * {@link #EXTRA_SUBSCRIPTION_REMEDIATION_METHOD}
     * {@link #EXTRA_URL}
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    public static final String ACTION_PASSPOINT_SUBSCRIPTION_REMEDIATION =
            "android.net.wifi.action.PASSPOINT_SUBSCRIPTION_REMEDIATION";
    /**
     * The protocol supported by the subscription remediation server. The possible values are:
     * 0 - OMA DM
     * 1 - SOAP XML SPP
     *
     * Retrieve with {@link android.content.Intent#getIntExtra(String, int)}.
     *
     * @hide
     */
    public static final String EXTRA_SUBSCRIPTION_REMEDIATION_METHOD =
            "android.net.wifi.extra.SUBSCRIPTION_REMEDIATION_METHOD";

    /**
     * Activity Action: lunch OSU (Online Sign Up) view.
     * Included extras:
     *
     * {@link #EXTRA_OSU_NETWORK}: {@link Network} instance associated with OSU AP.
     * {@link #EXTRA_URL}: String representation of a server URL used for OSU process.
     *
     * <p>Note: The broadcast is only delivered to registered receivers - no manifest registered
     * components will be launched.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PASSPOINT_LAUNCH_OSU_VIEW =
            "android.net.wifi.action.PASSPOINT_LAUNCH_OSU_VIEW";

    /**
     * The lookup key for a {@link android.net.Network} associated with OSU server.
     *
     * Retrieve with {@link android.content.Intent#getParcelableExtra(String)}.
     *
     * @hide
     */
    public static final String EXTRA_OSU_NETWORK = "android.net.wifi.extra.OSU_NETWORK";

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
    @SystemApi
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
    @SystemApi
    public static final String EXTRA_WIFI_AP_STATE = "wifi_state";

    /**
     * The look up key for an int that indicates why softAP started failed
     * currently support general and no_channel
     * @see #SAP_START_FAILURE_GENERAL
     * @see #SAP_START_FAILURE_NO_CHANNEL
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_FAILURE_REASON = "wifi_ap_error_code";
    /**
     * The previous Wi-Fi state.
     *
     * @see #EXTRA_WIFI_AP_STATE
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PREVIOUS_WIFI_AP_STATE = "previous_wifi_state";
    /**
     * The interface used for the softap.
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_INTERFACE_NAME = "wifi_ap_interface_name";
    /**
     * The intended ip mode for this softap.
     * @see #IFACE_IP_MODE_TETHERED
     * @see #IFACE_IP_MODE_LOCAL_ONLY
     *
     * @hide
     */
    public static final String EXTRA_WIFI_AP_MODE = "wifi_ap_mode";

    /** @hide */
    @IntDef(flag = false, prefix = { "WIFI_AP_STATE_" }, value = {
        WIFI_AP_STATE_DISABLING,
        WIFI_AP_STATE_DISABLED,
        WIFI_AP_STATE_ENABLING,
        WIFI_AP_STATE_ENABLED,
        WIFI_AP_STATE_FAILED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WifiApState {}

    /**
     * Wi-Fi AP is currently being disabled. The state will change to
     * {@link #WIFI_AP_STATE_DISABLED} if it finishes successfully.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    public static final int WIFI_AP_STATE_DISABLING = 10;
    /**
     * Wi-Fi AP is disabled.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiState()
     *
     * @hide
     */
    @SystemApi
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
    @SystemApi
    public static final int WIFI_AP_STATE_ENABLING = 12;
    /**
     * Wi-Fi AP is enabled.
     *
     * @see #WIFI_AP_STATE_CHANGED_ACTION
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
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
    @SystemApi
    public static final int WIFI_AP_STATE_FAILED = 14;

    /** @hide */
    @IntDef(flag = false, prefix = { "SAP_START_FAILURE_" }, value = {
        SAP_START_FAILURE_GENERAL,
        SAP_START_FAILURE_NO_CHANNEL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SapStartFailure {}

    /**
     *  All other reasons for AP start failure besides {@link #SAP_START_FAILURE_NO_CHANNEL}.
     *
     *  @hide
     */
    public static final int SAP_START_FAILURE_GENERAL= 0;

    /**
     *  If Wi-Fi AP start failed, this reason code means that no legal channel exists on user
     *  selected band due to regulatory constraints.
     *
     *  @hide
     */
    public static final int SAP_START_FAILURE_NO_CHANNEL = 1;

    /**
     * Interface IP mode unspecified.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_UNSPECIFIED = -1;

    /**
     * Interface IP mode for configuration error.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_CONFIGURATION_ERROR = 0;

    /**
     * Interface IP mode for tethering.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_TETHERED = 1;

    /**
     * Interface IP mode for Local Only Hotspot.
     *
     * @see updateInterfaceIpState(String, int)
     *
     * @hide
     */
    public static final int IFACE_IP_MODE_LOCAL_ONLY = 2;

    /**
     * Broadcast intent action indicating that the wifi network settings
     * had been reset.
     * @hide
     */
    public static final String WIFI_NETWORK_SETTINGS_RESET_ACTION =
            "android.net.wifi.action.NETWORK_SETTINGS_RESET";

    /**
     * Broadcast intent action indicating that a connection to the supplicant has
     * been established (and it is now possible
     * to perform Wi-Fi operations) or the connection to the supplicant has been
     * lost. One extra provides the connection state as a boolean, where {@code true}
     * means CONNECTED.
     * @deprecated This is no longer supported.
     * @see #EXTRA_SUPPLICANT_CONNECTED
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUPPLICANT_CONNECTION_CHANGE_ACTION =
        "android.net.wifi.supplicant.CONNECTION_CHANGE";
    /**
     * The lookup key for a boolean that indicates whether a connection to
     * the supplicant daemon has been gained or lost. {@code true} means
     * a connection now exists.
     * Retrieve it with {@link android.content.Intent#getBooleanExtra(String,boolean)}.
     * @deprecated This is no longer supported.
     */
    @Deprecated
    public static final String EXTRA_SUPPLICANT_CONNECTED = "connected";
    /**
     * Broadcast intent action indicating that the state of Wi-Fi connectivity
     * has changed. An extra provides the new state
     * in the form of a {@link android.net.NetworkInfo} object.
     * @see #EXTRA_NETWORK_INFO
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
     * we are connected. No longer used.
     */
    @Deprecated
    public static final String EXTRA_BSSID = "bssid";
    /**
     * The lookup key for a {@link android.net.wifi.WifiInfo} object giving the
     * information about the access point to which we are connected.
     * No longer used.
     */
    @Deprecated
    public static final String EXTRA_WIFI_INFO = "wifiInfo";
    /**
     * Broadcast intent action indicating that the state of establishing a connection to
     * an access point has changed.One extra provides the new
     * {@link SupplicantState}. Note that the supplicant state is Wi-Fi specific, and
     * is not generally the most useful thing to look at if you are just interested in
     * the overall state of connectivity.
     * @see #EXTRA_NEW_STATE
     * @see #EXTRA_SUPPLICANT_ERROR
     * @deprecated This is no longer supported.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUPPLICANT_STATE_CHANGED_ACTION =
        "android.net.wifi.supplicant.STATE_CHANGE";
    /**
     * The lookup key for a {@link SupplicantState} describing the new state
     * Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @deprecated This is no longer supported.
     */
    @Deprecated
    public static final String EXTRA_NEW_STATE = "newState";

    /**
     * The lookup key for a {@link SupplicantState} describing the supplicant
     * error code if any
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String, int)}.
     * @see #ERROR_AUTHENTICATING
     * @deprecated This is no longer supported.
     */
    @Deprecated
    public static final String EXTRA_SUPPLICANT_ERROR = "supplicantError";

    /**
     * The lookup key for a {@link SupplicantState} describing the supplicant
     * error reason if any
     * Retrieve with
     * {@link android.content.Intent#getIntExtra(String, int)}.
     * @see #ERROR_AUTH_FAILURE_#REASON_CODE
     * @deprecated This is no longer supported.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_SUPPLICANT_ERROR_REASON = "supplicantErrorReason";

    /**
     * Broadcast intent action indicating that the configured networks changed.
     * This can be as a result of adding/updating/deleting a network. If
     * {@link #EXTRA_MULTIPLE_NETWORKS_CHANGED} is set to true the new configuration
     * can be retreived with the {@link #EXTRA_WIFI_CONFIGURATION} extra. If multiple
     * Wi-Fi configurations changed, {@link #EXTRA_WIFI_CONFIGURATION} will not be present.
     * @hide
     */
    @SystemApi
    public static final String CONFIGURED_NETWORKS_CHANGED_ACTION =
        "android.net.wifi.CONFIGURED_NETWORKS_CHANGE";
    /**
     * The lookup key for a (@link android.net.wifi.WifiConfiguration} object representing
     * the changed Wi-Fi configuration when the {@link #CONFIGURED_NETWORKS_CHANGED_ACTION}
     * broadcast is sent.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_WIFI_CONFIGURATION = "wifiConfiguration";
    /**
     * Multiple network configurations have changed.
     * @see #CONFIGURED_NETWORKS_CHANGED_ACTION
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_MULTIPLE_NETWORKS_CHANGED = "multipleChanges";
    /**
     * The lookup key for an integer indicating the reason a Wi-Fi network configuration
     * has changed. Only present if {@link #EXTRA_MULTIPLE_NETWORKS_CHANGED} is {@code false}
     * @see #CONFIGURED_NETWORKS_CHANGED_ACTION
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CHANGE_REASON = "changeReason";
    /**
     * The configuration is new and was added.
     * @hide
     */
    @SystemApi
    public static final int CHANGE_REASON_ADDED = 0;
    /**
     * The configuration was removed and is no longer present in the system's list of
     * configured networks.
     * @hide
     */
    @SystemApi
    public static final int CHANGE_REASON_REMOVED = 1;
    /**
     * The configuration has changed as a result of explicit action or because the system
     * took an automated action such as disabling a malfunctioning configuration.
     * @hide
     */
    @SystemApi
    public static final int CHANGE_REASON_CONFIG_CHANGE = 2;
    /**
     * An access point scan has completed, and results are available.
     * Call {@link #getScanResults()} to obtain the results.
     * The broadcast intent may contain an extra field with the key {@link #EXTRA_RESULTS_UPDATED}
     * and a {@code boolean} value indicating if the scan was successful.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SCAN_RESULTS_AVAILABLE_ACTION = "android.net.wifi.SCAN_RESULTS";

    /**
     * Lookup key for a {@code boolean} extra in intent {@link #SCAN_RESULTS_AVAILABLE_ACTION}
     * representing if the scan was successful or not.
     * Scans may fail for multiple reasons, these may include:
     * <ol>
     * <li>An app requested too many scans in a certain period of time.
     * This may lead to additional scan request rejections via "scan throttling" for both
     * foreground and background apps.
     * Note: Apps holding android.Manifest.permission.NETWORK_SETTINGS permission are
     * exempted from scan throttling.
     * </li>
     * <li>The device is idle and scanning is disabled.</li>
     * <li>Wifi hardware reported a scan failure.</li>
     * </ol>
     * @return true scan was successful, results are updated
     * @return false scan was not successful, results haven't been updated since previous scan
     */
    public static final String EXTRA_RESULTS_UPDATED = "resultsUpdated";

    /**
     * A batch of access point scans has been completed and the results areavailable.
     * Call {@link #getBatchedScanResults()} to obtain the results.
     * @deprecated This API is nolonger supported.
     * Use {@link android.net.wifi.WifiScanner} API
     * @hide
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String BATCHED_SCAN_RESULTS_AVAILABLE_ACTION =
            "android.net.wifi.BATCHED_RESULTS";

    /**
     * The RSSI (signal strength) has changed.
     *
     * Receiver Required Permission: android.Manifest.permission.ACCESS_WIFI_STATE
     * @see {@link #EXTRA_NEW_RSSI}
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
    @UnsupportedAppUsage
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
     * The lookup key for a {@link android.net.NetworkCapabilities} object associated with the
     * Wi-Fi network. Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     * @hide
     */
    public static final String EXTRA_NETWORK_CAPABILITIES = "networkCapabilities";

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
     * Activity Action: Show UI to get user approval to enable WiFi.
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} string extra with
     *           the name of the app requesting the action.
     * <p>Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_ENABLE = "android.net.wifi.action.REQUEST_ENABLE";

    /**
     * Activity Action: Show UI to get user approval to disable WiFi.
     * <p>Input: {@link android.content.Intent#EXTRA_PACKAGE_NAME} string extra with
     *           the name of the app requesting the action.
     * <p>Output: Nothing.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_DISABLE = "android.net.wifi.action.REQUEST_DISABLE";

    /**
     * Directed broadcast intent action indicating that the device has connected to one of the
     * network suggestions provided by the app. This will be sent post connection to a network
     * which was created with {@link WifiNetworkSuggestion.Builder#setIsAppInteractionRequired(
     * boolean)}
     * flag set.
     * <p>
     * Note: The broadcast is sent to the app only if it holds
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission.
     *
     * @see #EXTRA_NETWORK_SUGGESTION
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION =
            "android.net.wifi.action.WIFI_NETWORK_SUGGESTION_POST_CONNECTION";
    /**
     * Sent as as a part of {@link #ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} that holds
     * an instance of {@link WifiNetworkSuggestion} corresponding to the connected network.
     */
    public static final String EXTRA_NETWORK_SUGGESTION =
            "android.net.wifi.extra.NETWORK_SUGGESTION";

    /**
     * Internally used Wi-Fi lock mode representing the case were no locks are held.
     * @hide
     */
    public static final int WIFI_MODE_NO_LOCKS_HELD = 0;

    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active,
     * and will behave normally, i.e., it will attempt to automatically
     * establish a connection to a remembered access point that is
     * within range, and will do periodic scans if there are remembered
     * access points but none are in range.
     *
     * @deprecated This API is non-functional and will have no impact.
     */
    @Deprecated
    public static final int WIFI_MODE_FULL = WifiProtoEnums.WIFI_MODE_FULL; // 1

    /**
     * In this Wi-Fi lock mode, Wi-Fi will be kept active,
     * but the only operation that will be supported is initiation of
     * scans, and the subsequent reporting of scan results. No attempts
     * will be made to automatically connect to remembered access points,
     * nor will periodic scans be automatically performed looking for
     * remembered access points. Scans must be explicitly requested by
     * an application in this mode.
     *
     * @deprecated This API is non-functional and will have no impact.
     */
    @Deprecated
    public static final int WIFI_MODE_SCAN_ONLY = WifiProtoEnums.WIFI_MODE_SCAN_ONLY; // 2

    /**
     * In this Wi-Fi lock mode, Wi-Fi will not go to power save.
     * This results in operating with low packet latency.
     * The lock is only active when the device is connected to an access point.
     * The lock is active even when the device screen is off or the acquiring application is
     * running in the background.
     * This mode will consume more power and hence should be used only
     * when there is a need for this tradeoff.
     * <p>
     * An example use case is when a voice connection needs to be
     * kept active even after the device screen goes off.
     * Holding a {@link #WIFI_MODE_FULL_HIGH_PERF} lock for the
     * duration of the voice call may improve the call quality.
     * <p>
     * When there is no support from the hardware, the {@link #WIFI_MODE_FULL_HIGH_PERF}
     * lock will have no impact.
     */
    public static final int WIFI_MODE_FULL_HIGH_PERF = WifiProtoEnums.WIFI_MODE_FULL_HIGH_PERF; // 3

    /**
     * In this Wi-Fi lock mode, Wi-Fi will operate with a priority to achieve low latency.
     * {@link #WIFI_MODE_FULL_LOW_LATENCY} lock has the following limitations:
     * <ol>
     * <li>The lock is only active when the device is connected to an access point.</li>
     * <li>The lock is only active when the screen is on.</li>
     * <li>The lock is only active when the acquiring app is running in the foreground.</li>
     * </ol>
     * Low latency mode optimizes for reduced packet latency,
     * and as a result other performance measures may suffer when there are trade-offs to make:
     * <ol>
     * <li>Battery life may be reduced.</li>
     * <li>Throughput may be reduced.</li>
     * <li>Frequency of Wi-Fi scanning may be reduced. This may result in: </li>
     * <ul>
     * <li>The device may not roam or switch to the AP with highest signal quality.</li>
     * <li>Location accuracy may be reduced.</li>
     * </ul>
     * </ol>
     * <p>
     * Example use cases are real time gaming or virtual reality applications where
     * low latency is a key factor for user experience.
     * <p>
     * Note: For an app which acquires both {@link #WIFI_MODE_FULL_LOW_LATENCY} and
     * {@link #WIFI_MODE_FULL_HIGH_PERF} locks, {@link #WIFI_MODE_FULL_LOW_LATENCY}
     * lock will be effective when app is running in foreground and screen is on,
     * while the {@link #WIFI_MODE_FULL_HIGH_PERF} lock will take effect otherwise.
     */
    public static final int WIFI_MODE_FULL_LOW_LATENCY =
            WifiProtoEnums.WIFI_MODE_FULL_LOW_LATENCY; // 4

    /** Anything worse than or equal to this will show 0 bars. */
    @UnsupportedAppUsage
    private static final int MIN_RSSI = -100;

    /** Anything better than or equal to this will show the max bars. */
    @UnsupportedAppUsage
    private static final int MAX_RSSI = -55;

    /**
     * Number of RSSI levels used in the framework to initiate
     * {@link #RSSI_CHANGED_ACTION} broadcast
     * @hide
     */
    @UnsupportedAppUsage
    public static final int RSSI_LEVELS = 5;

    /**
     * Auto settings in the driver. The driver could choose to operate on both
     * 2.4 GHz and 5 GHz or make a dynamic decision on selecting the band.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int WIFI_FREQUENCY_BAND_AUTO = 0;

    /**
     * Operation on 5 GHz alone
     * @hide
     */
    @UnsupportedAppUsage
    public static final int WIFI_FREQUENCY_BAND_5GHZ = 1;

    /**
     * Operation on 2.4 GHz alone
     * @hide
     */
    @UnsupportedAppUsage
    public static final int WIFI_FREQUENCY_BAND_2GHZ = 2;

    /** @hide */
    public static final boolean DEFAULT_POOR_NETWORK_AVOIDANCE_ENABLED = false;

    /* Maximum number of active locks we allow.
     * This limit was added to prevent apps from creating a ridiculous number
     * of locks and crashing the system by overflowing the global ref table.
     */
    private static final int MAX_ACTIVE_LOCKS = 50;

    /* Number of currently active WifiLocks and MulticastLocks */
    @UnsupportedAppUsage
    private int mActiveLockCount;

    private Context mContext;
    @UnsupportedAppUsage
    IWifiManager mService;
    private final int mTargetSdkVersion;

    private static final int INVALID_KEY = 0;
    private int mListenerKey = 1;
    private final SparseArray mListenerMap = new SparseArray();
    private final Object mListenerMapLock = new Object();

    private AsyncChannel mAsyncChannel;
    private CountDownLatch mConnected;
    private Looper mLooper;
    private boolean mVerboseLoggingEnabled = false;

    /* LocalOnlyHotspot callback message types */
    /** @hide */
    public static final int HOTSPOT_STARTED = 0;
    /** @hide */
    public static final int HOTSPOT_STOPPED = 1;
    /** @hide */
    public static final int HOTSPOT_FAILED = 2;
    /** @hide */
    public static final int HOTSPOT_OBSERVER_REGISTERED = 3;

    private final Object mLock = new Object(); // lock guarding access to the following vars
    @GuardedBy("mLock")
    private LocalOnlyHotspotCallbackProxy mLOHSCallbackProxy;
    @GuardedBy("mLock")
    private LocalOnlyHotspotObserverProxy mLOHSObserverProxy;

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
    public WifiManager(Context context, IWifiManager service, Looper looper) {
        mContext = context;
        mService = service;
        mLooper = looper;
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        updateVerboseLoggingEnabledFromService();
    }

    /**
     * Return a list of all the networks configured for the current foreground
     * user.
     *
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
     * of {@link WifiConfiguration} objects.
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will return an empty list,
     * except for:
     * <ul>
     * <li>Device Owner (DO) & Profile Owner (PO) apps will have access to the full list.
     * <li>Callers with Carrier privilege will receive a restricted list only containing
     * configurations which they created.
     * </ul>
     */
    @Deprecated
    @RequiresPermission(allOf = {ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE})
    public List<WifiConfiguration> getConfiguredNetworks() {
        try {
            ParceledListSlice<WifiConfiguration> parceledList =
                    mService.getConfiguredNetworks(mContext.getOpPackageName());
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(allOf = {ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE, READ_WIFI_CREDENTIAL})
    public List<WifiConfiguration> getPrivilegedConfiguredNetworks() {
        try {
            ParceledListSlice<WifiConfiguration> parceledList =
                    mService.getPrivilegedConfiguredNetworks(mContext.getOpPackageName());
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of all matching WifiConfigurations for a given list of ScanResult.
     *
     * An empty list will be returned when no configurations are installed or if no configurations
     * match the ScanResult.
     *
     * @param scanResults a list of scanResult that represents the BSSID
     * @return List that consists of {@link WifiConfiguration} and corresponding scanResults per
     * network type({@link #PASSPOINT_HOME_NETWORK} and {@link #PASSPOINT_ROAMING_NETWORK}).
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD
    })
    @NonNull
    public List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> getAllMatchingWifiConfigs(
            @NonNull List<ScanResult> scanResults) {
        List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> configs = new ArrayList<>();
        try {
            Map<String, Map<Integer, List<ScanResult>>> results =
                    mService.getAllMatchingFqdnsForScanResults(
                            scanResults);
            if (results.isEmpty()) {
                return configs;
            }
            List<WifiConfiguration> wifiConfigurations =
                    mService.getWifiConfigsForPasspointProfiles(
                            new ArrayList<>(results.keySet()));
            for (WifiConfiguration configuration : wifiConfigurations) {
                Map<Integer, List<ScanResult>> scanResultsPerNetworkType = results.get(
                        configuration.FQDN);
                if (scanResultsPerNetworkType != null) {
                    configs.add(Pair.create(configuration, scanResultsPerNetworkType));
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return configs;
    }

    /**
     * Returns a list of unique Hotspot 2.0 OSU (Online Sign-Up) providers associated with a given
     * list of ScanResult.
     *
     * An empty list will be returned if no match is found.
     *
     * @param scanResults a list of ScanResult
     * @return Map that consists {@link OsuProvider} and a list of matching {@link ScanResult}
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD
    })
    @NonNull
    public Map<OsuProvider, List<ScanResult>> getMatchingOsuProviders(
            @Nullable List<ScanResult> scanResults) {
        if (scanResults == null) {
            return new HashMap<>();
        }
        try {
            return mService.getMatchingOsuProviders(scanResults);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the matching Passpoint R2 configurations for given OSU (Online Sign-Up) providers.
     *
     * Given a list of OSU providers, this only returns OSU providers that already have Passpoint R2
     * configurations in the device.
     * An empty map will be returned when there is no matching Passpoint R2 configuration for the
     * given OsuProviders.
     *
     * @param osuProviders a set of {@link OsuProvider}
     * @return Map that consists of {@link OsuProvider} and matching {@link PasspointConfiguration}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD
    })
    @NonNull
    public Map<OsuProvider, PasspointConfiguration> getMatchingPasspointConfigsForOsuProviders(
            @NonNull Set<OsuProvider> osuProviders) {
        try {
            return mService.getMatchingPasspointConfigsForOsuProviders(
                    new ArrayList<>(osuProviders));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     *            If the {@link WifiConfiguration} has an Http Proxy set
     *            the calling app must be System, or be provisioned as the Profile or Device Owner.
     * @return the ID of the newly created network description. This is used in
     *         other operations to specified the network to be acted upon.
     *         Returns {@code -1} on failure.
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return {@code -1}.
     */
    @Deprecated
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
     *            If the {@link WifiConfiguration} has an Http Proxy set
     *            the calling app must be System, or be provisioned as the Profile or Device Owner.
     * @return Returns the {@code networkId} of the supplied
     *         {@code WifiConfiguration} on success.
     *         <br/>
     *         Returns {@code -1} on failure, including when the {@code networkId}
     *         field of the {@code WifiConfiguration} does not refer to an
     *         existing network.
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return {@code -1}.
     */
    @Deprecated
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
            return mService.addOrUpdateNetwork(config, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Interface for indicating user selection from the list of networks presented in the
     * {@link NetworkRequestMatchCallback#onMatch(List)}.
     *
     * The platform will implement this callback and pass it along with the
     * {@link NetworkRequestMatchCallback#onUserSelectionCallbackRegistration(
     * NetworkRequestUserSelectionCallback)}. The UI component handling
     * {@link NetworkRequestMatchCallback} will invoke {@link #select(WifiConfiguration)} or
     * {@link #reject()} to return the user's selection back to the platform via this callback.
     * @hide
     */
    public interface NetworkRequestUserSelectionCallback {
        /**
         * User selected this network to connect to.
         * @param wifiConfiguration WifiConfiguration object corresponding to the network
         *                          user selected.
         */
        void select(@NonNull WifiConfiguration wifiConfiguration);

        /**
         * User rejected the app's request.
         */
        void reject();
    }

    /**
     * Interface for network request callback. Should be implemented by applications and passed when
     * calling {@link #registerNetworkRequestMatchCallback(NetworkRequestMatchCallback, Handler)}.
     *
     * This is meant to be implemented by a UI component to present the user with a list of networks
     * matching the app's request. The user is allowed to pick one of these networks to connect to
     * or reject the request by the app.
     * @hide
     */
    public interface NetworkRequestMatchCallback {
        /**
         * Invoked to register a callback to be invoked to convey user selection. The callback
         * object paased in this method is to be invoked by the UI component after the service sends
         * a list of matching scan networks using {@link #onMatch(List)} and user picks a network
         * from that list.
         *
         * @param userSelectionCallback Callback object to send back the user selection.
         */
        void onUserSelectionCallbackRegistration(
                @NonNull NetworkRequestUserSelectionCallback userSelectionCallback);

        /**
         * Invoked when the active network request is aborted, either because
         * <li> The app released the request, OR</li>
         * <li> Request was overridden by a new request</li>
         * This signals the end of processing for the current request and should stop the UI
         * component. No subsequent calls from the UI component will be handled by the platform.
         */
        void onAbort();

        /**
         * Invoked when a network request initiated by an app matches some networks in scan results.
         * This may be invoked multiple times for a single network request as the platform finds new
         * matching networks in scan results.
         *
         * @param scanResults List of {@link ScanResult} objects corresponding to the networks
         *                    matching the request.
         */
        void onMatch(@NonNull List<ScanResult> scanResults);

        /**
         * Invoked on a successful connection with the network that the user selected
         * via {@link NetworkRequestUserSelectionCallback}.
         *
         * @param wifiConfiguration WifiConfiguration object corresponding to the network that the
         *                          user selected.
         */
        void onUserSelectionConnectSuccess(@NonNull WifiConfiguration wifiConfiguration);

        /**
         * Invoked on failure to establish connection with the network that the user selected
         * via {@link NetworkRequestUserSelectionCallback}.
         *
         * @param wifiConfiguration WifiConfiguration object corresponding to the network
         *                          user selected.
         */
        void onUserSelectionConnectFailure(@NonNull WifiConfiguration wifiConfiguration);
    }

    /**
     * Callback proxy for NetworkRequestUserSelectionCallback objects.
     * @hide
     */
    private class NetworkRequestUserSelectionCallbackProxy implements
            NetworkRequestUserSelectionCallback {
        private final INetworkRequestUserSelectionCallback mCallback;

        NetworkRequestUserSelectionCallbackProxy(
                INetworkRequestUserSelectionCallback callback) {
            mCallback = callback;
        }

        @Override
        public void select(@NonNull WifiConfiguration wifiConfiguration) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestUserSelectionCallbackProxy: select "
                        + "wificonfiguration: " + wifiConfiguration);
            }
            try {
                mCallback.select(wifiConfiguration);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to invoke onSelected", e);
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        public void reject() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestUserSelectionCallbackProxy: reject");
            }
            try {
                mCallback.reject();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to invoke onRejected", e);
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callback proxy for NetworkRequestMatchCallback objects.
     * @hide
     */
    private class NetworkRequestMatchCallbackProxy extends INetworkRequestMatchCallback.Stub {
        private final Handler mHandler;
        private final NetworkRequestMatchCallback mCallback;

        NetworkRequestMatchCallbackProxy(Looper looper, NetworkRequestMatchCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onUserSelectionCallbackRegistration(
                INetworkRequestUserSelectionCallback userSelectionCallback) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestMatchCallbackProxy: "
                        + "onUserSelectionCallbackRegistration callback: " + userSelectionCallback);
            }
            mHandler.post(() -> {
                mCallback.onUserSelectionCallbackRegistration(
                        new NetworkRequestUserSelectionCallbackProxy(userSelectionCallback));
            });
        }

        @Override
        public void onAbort() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestMatchCallbackProxy: onAbort");
            }
            mHandler.post(() -> {
                mCallback.onAbort();
            });
        }

        @Override
        public void onMatch(List<ScanResult> scanResults) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestMatchCallbackProxy: onMatch scanResults: "
                        + scanResults);
            }
            mHandler.post(() -> {
                mCallback.onMatch(scanResults);
            });
        }

        @Override
        public void onUserSelectionConnectSuccess(WifiConfiguration wifiConfiguration) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestMatchCallbackProxy: onUserSelectionConnectSuccess "
                        + " wificonfiguration: " + wifiConfiguration);
            }
            mHandler.post(() -> {
                mCallback.onUserSelectionConnectSuccess(wifiConfiguration);
            });
        }

        @Override
        public void onUserSelectionConnectFailure(WifiConfiguration wifiConfiguration) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "NetworkRequestMatchCallbackProxy: onUserSelectionConnectFailure"
                        + " wificonfiguration: " + wifiConfiguration);
            }
            mHandler.post(() -> {
                mCallback.onUserSelectionConnectFailure(wifiConfiguration);
            });
        }
    }

    /**
     * Registers a callback for NetworkRequest matches. See {@link NetworkRequestMatchCallback}.
     * Caller can unregister a previously registered callback using
     * {@link #unregisterNetworkRequestMatchCallback(NetworkRequestMatchCallback)}
     * <p>
     * Applications should have the
     * {@link android.Manifest.permission#NETWORK_SETTINGS} permission. Callers
     * without the permission will trigger a {@link java.lang.SecurityException}.
     * <p>
     *
     * @param callback Callback for network match events
     * @param handler  The Handler on whose thread to execute the callbacks of the {@code callback}
     *                 object. If null, then the application's main thread will be used.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void registerNetworkRequestMatchCallback(@NonNull NetworkRequestMatchCallback callback,
                                                    @Nullable Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "registerNetworkRequestMatchCallback: callback=" + callback
                + ", handler=" + handler);

        Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
        Binder binder = new Binder();
        try {
            mService.registerNetworkRequestMatchCallback(
                    binder, new NetworkRequestMatchCallbackProxy(looper, callback),
                    callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a callback for NetworkRequest matches. See {@link NetworkRequestMatchCallback}.
     * <p>
     * Applications should have the
     * {@link android.Manifest.permission#NETWORK_SETTINGS} permission. Callers
     * without the permission will trigger a {@link java.lang.SecurityException}.
     * <p>
     *
     * @param callback Callback for network match events
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void unregisterNetworkRequestMatchCallback(
            @NonNull NetworkRequestMatchCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "unregisterNetworkRequestMatchCallback: callback=" + callback);

        try {
            mService.unregisterNetworkRequestMatchCallback(callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provide a list of network suggestions to the device. See {@link WifiNetworkSuggestion}
     * for a detailed explanation of the parameters.
     * When the device decides to connect to one of the provided network suggestions, platform sends
     * a directed broadcast {@link #ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} to the app if
     * the network was created with {@link WifiNetworkSuggestion.Builder
     * #setIsAppInteractionRequired()} flag set and the app holds
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission.
     *<p>
     * NOTE:
     * <li> These networks are just a suggestion to the platform. The platform will ultimately
     * decide on which network the device connects to. </li>
     * <li> When an app is uninstalled, all its suggested networks are discarded. If the device is
     * currently connected to a suggested network which is being removed then the device will
     * disconnect from that network.</li>
     * <li> No in-place modification of existing suggestions are allowed. Apps are expected to
     * remove suggestions using {@link #removeNetworkSuggestions(List)} and then add the modified
     * suggestion back using this API.</li>
     *
     * @param networkSuggestions List of network suggestions provided by the app.
     * @return Status code for the operation. One of the STATUS_NETWORK_SUGGESTIONS_ values.
     * {@link WifiNetworkSuggestion#equals(Object)} any previously provided suggestions by the app.
     * @throws {@link SecurityException} if the caller is missing required permissions.
     */
    @RequiresPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
    public @NetworkSuggestionsStatusCode int addNetworkSuggestions(
            @NonNull List<WifiNetworkSuggestion> networkSuggestions) {
        try {
            return mService.addNetworkSuggestions(networkSuggestions, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove some or all of the network suggestions that were previously provided by the app.
     * See {@link WifiNetworkSuggestion} for a detailed explanation of the parameters.
     * See {@link WifiNetworkSuggestion#equals(Object)} for the equivalence evaluation used.
     *
     * @param networkSuggestions List of network suggestions to be removed. Pass an empty list
     *                           to remove all the previous suggestions provided by the app.
     * @return Status code for the operation. One of the STATUS_NETWORK_SUGGESTIONS_ values.
     * Any matching suggestions are removed from the device and will not be considered for any
     * further connection attempts.
     */
    @RequiresPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
    public @NetworkSuggestionsStatusCode int removeNetworkSuggestions(
            @NonNull List<WifiNetworkSuggestion> networkSuggestions) {
        try {
            return mService.removeNetworkSuggestions(
                    networkSuggestions, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the max number of network suggestions that are allowed per app on the device.
     * @see #addNetworkSuggestions(List)
     * @see #removeNetworkSuggestions(List)
     */
    public int getMaxNumberOfNetworkSuggestionsPerApp() {
        return NETWORK_SUGGESTIONS_MAX_PER_APP;
    }

    /**
     * Add or update a Passpoint configuration.  The configuration provides a credential
     * for connecting to Passpoint networks that are operated by the Passpoint
     * service provider specified in the configuration.
     *
     * Each configuration is uniquely identified by its FQDN (Fully Qualified Domain
     * Name).  In the case when there is an existing configuration with the same
     * FQDN, the new configuration will replace the existing configuration.
     *
     * @param config The Passpoint configuration to be added
     * @throws IllegalArgumentException if configuration is invalid or Passpoint is not enabled on
     *                                  the device.
     */
    public void addOrUpdatePasspointConfiguration(PasspointConfiguration config) {
        try {
            if (!mService.addOrUpdatePasspointConfiguration(config, mContext.getOpPackageName())) {
                throw new IllegalArgumentException();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the Passpoint configuration identified by its FQDN (Fully Qualified Domain Name).
     *
     * @param fqdn The FQDN of the Passpoint configuration to be removed
     * @throws IllegalArgumentException if no configuration is associated with the given FQDN or
     *                                  Passpoint is not enabled on the device.
     * @deprecated This is no longer supported.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void removePasspointConfiguration(String fqdn) {
        try {
            if (!mService.removePasspointConfiguration(fqdn, mContext.getOpPackageName())) {
                throw new IllegalArgumentException();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the list of installed Passpoint configurations.
     *
     * An empty list will be returned when no configurations are installed.
     *
     * @return A list of {@link PasspointConfiguration}
     * @deprecated This is no longer supported.
     */
    @Deprecated
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD
    })
    public List<PasspointConfiguration> getPasspointConfigurations() {
        try {
            return mService.getPasspointConfigurations(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query for a Hotspot 2.0 release 2 OSU icon file. An {@link #ACTION_PASSPOINT_ICON} intent
     * will be broadcasted once the request is completed.  The presence of the intent extra
     * {@link #EXTRA_ICON} will indicate the result of the request.
     * A missing intent extra {@link #EXTRA_ICON} will indicate a failure.
     *
     * @param bssid The BSSID of the AP
     * @param fileName Name of the icon file (remote file) to query from the AP
     *
     * @throws UnsupportedOperationException if Passpoint is not enabled on the device.
     * @hide
     */
    public void queryPasspointIcon(long bssid, String fileName) {
        try {
            mService.queryPasspointIcon(bssid, fileName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Match the currently associated network against the SP matching the given FQDN
     * @param fqdn FQDN of the SP
     * @return ordinal [HomeProvider, RoamingProvider, Incomplete, None, Declined]
     * @hide
     */
    public int matchProviderWithCurrentNetwork(String fqdn) {
        try {
            return mService.matchProviderWithCurrentNetwork(fqdn);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deauthenticate and set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     * @hide
     */
    public void deauthenticateNetwork(long holdoff, boolean ess) {
        try {
            mService.deauthenticateNetwork(holdoff, ess);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the specified network from the list of configured networks.
     * This may result in the asynchronous delivery of state change
     * events.
     *
     * Applications are not allowed to remove networks created by other
     * applications.
     *
     * @param netId the ID of the network as returned by {@link #addNetwork} or {@link
     *        #getConfiguredNetworks}.
     * @return {@code true} if the operation succeeded
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return false.
     */
    @Deprecated
    public boolean removeNetwork(int netId) {
        try {
            return mService.removeNetwork(netId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow a previously configured network to be associated with. If
     * <code>attemptConnect</code> is true, an attempt to connect to the selected
     * network is initiated. This may result in the asynchronous delivery
     * of state change events.
     * <p>
     * <b>Note:</b> Network communication may not use Wi-Fi even if Wi-Fi is connected;
     * traffic may instead be sent through another network, such as cellular data,
     * Bluetooth tethering, or Ethernet. For example, traffic will never use a
     * Wi-Fi network that does not provide Internet access (e.g. a wireless
     * printer), if another network that does offer Internet access (e.g.
     * cellular data) is available. Applications that need to ensure that their
     * network traffic uses Wi-Fi should use APIs such as
     * {@link Network#bindSocket(java.net.Socket)},
     * {@link Network#openConnection(java.net.URL)}, or
     * {@link ConnectivityManager#bindProcessToNetwork} to do so.
     *
     * Applications are not allowed to enable networks created by other
     * applications.
     *
     * @param netId the ID of the network as returned by {@link #addNetwork} or {@link
     *        #getConfiguredNetworks}.
     * @param attemptConnect The way to select a particular network to connect to is specify
     *        {@code true} for this parameter.
     * @return {@code true} if the operation succeeded
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return false.
     */
    @Deprecated
    public boolean enableNetwork(int netId, boolean attemptConnect) {
        final boolean pin = attemptConnect && mTargetSdkVersion < Build.VERSION_CODES.LOLLIPOP;
        if (pin) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .clearCapabilities()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            NetworkPinner.pin(mContext, request);
        }

        boolean success;
        try {
            success = mService.enableNetwork(netId, attemptConnect, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (pin && !success) {
            NetworkPinner.unpin();
        }

        return success;
    }

    /**
     * Disable a configured network. The specified network will not be
     * a candidate for associating. This may result in the asynchronous
     * delivery of state change events.
     *
     * Applications are not allowed to disable networks created by other
     * applications.
     *
     * @param netId the ID of the network as returned by {@link #addNetwork} or {@link
     *        #getConfiguredNetworks}.
     * @return {@code true} if the operation succeeded
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return false.
     */
    @Deprecated
    public boolean disableNetwork(int netId) {
        try {
            return mService.disableNetwork(netId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disassociate from the currently active access point. This may result
     * in the asynchronous delivery of state change events.
     * @return {@code true} if the operation succeeded
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return false.
     */
    @Deprecated
    public boolean disconnect() {
        try {
            return mService.disconnect(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reconnect to the currently active access point, if we are currently
     * disconnected. This may result in the asynchronous delivery of state
     * change events.
     * @return {@code true} if the operation succeeded
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return false.
     */
    @Deprecated
    public boolean reconnect() {
        try {
            return mService.reconnect(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reconnect to the currently active access point, even if we are already
     * connected. This may result in the asynchronous delivery of state
     * change events.
     * @return {@code true} if the operation succeeded
     *
     * @deprecated
     * a) See {@link WifiNetworkSpecifier.Builder#build()} for new
     * mechanism to trigger connection to a Wi-Fi network.
     * b) See {@link #addNetworkSuggestions(List)},
     * {@link #removeNetworkSuggestions(List)} for new API to add Wi-Fi networks for consideration
     * when auto-connecting to wifi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return false.
     */
    @Deprecated
    public boolean reassociate() {
        try {
            return mService.reassociate(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check that the supplicant daemon is responding to requests.
     * @return {@code true} if we were able to communicate with the supplicant and
     * it returned the expected response to the PING message.
     * @deprecated Will return the output of {@link #isWifiEnabled()} instead.
     */
    @Deprecated
    public boolean pingSupplicant() {
        return isWifiEnabled();
    }

    /** @hide */
    public static final int WIFI_FEATURE_INFRA            = 0x0001;  // Basic infrastructure mode
    /** @hide */
    public static final int WIFI_FEATURE_INFRA_5G         = 0x0002;  // Support for 5 GHz Band
    /** @hide */
    public static final int WIFI_FEATURE_PASSPOINT        = 0x0004;  // Support for GAS/ANQP
    /** @hide */
    public static final int WIFI_FEATURE_P2P              = 0x0008;  // Wifi-Direct
    /** @hide */
    public static final int WIFI_FEATURE_MOBILE_HOTSPOT   = 0x0010;  // Soft AP
    /** @hide */
    public static final int WIFI_FEATURE_SCANNER          = 0x0020;  // WifiScanner APIs
    /** @hide */
    public static final int WIFI_FEATURE_AWARE            = 0x0040;  // Wi-Fi AWare networking
    /** @hide */
    public static final int WIFI_FEATURE_D2D_RTT          = 0x0080;  // Device-to-device RTT
    /** @hide */
    public static final int WIFI_FEATURE_D2AP_RTT         = 0x0100;  // Device-to-AP RTT
    /** @hide */
    public static final int WIFI_FEATURE_BATCH_SCAN       = 0x0200;  // Batched Scan (deprecated)
    /** @hide */
    public static final int WIFI_FEATURE_PNO              = 0x0400;  // Preferred network offload
    /** @hide */
    public static final int WIFI_FEATURE_ADDITIONAL_STA   = 0x0800;  // Support for two STAs
    /** @hide */
    public static final int WIFI_FEATURE_TDLS             = 0x1000;  // Tunnel directed link setup
    /** @hide */
    public static final int WIFI_FEATURE_TDLS_OFFCHANNEL  = 0x2000;  // Support for TDLS off channel
    /** @hide */
    public static final int WIFI_FEATURE_EPR              = 0x4000;  // Enhanced power reporting
    /** @hide */
    public static final int WIFI_FEATURE_AP_STA           = 0x8000;  // AP STA Concurrency
    /** @hide */
    public static final int WIFI_FEATURE_LINK_LAYER_STATS = 0x10000; // Link layer stats collection
    /** @hide */
    public static final int WIFI_FEATURE_LOGGER           = 0x20000; // WiFi Logger
    /** @hide */
    public static final int WIFI_FEATURE_HAL_EPNO         = 0x40000; // Enhanced PNO
    /** @hide */
    public static final int WIFI_FEATURE_RSSI_MONITOR     = 0x80000; // RSSI Monitor
    /** @hide */
    public static final int WIFI_FEATURE_MKEEP_ALIVE      = 0x100000; // mkeep_alive
    /** @hide */
    public static final int WIFI_FEATURE_CONFIG_NDO       = 0x200000; // ND offload
    /** @hide */
    public static final int WIFI_FEATURE_TRANSMIT_POWER   = 0x400000; // Capture transmit power
    /** @hide */
    public static final int WIFI_FEATURE_CONTROL_ROAMING  = 0x800000; // Control firmware roaming
    /** @hide */
    public static final int WIFI_FEATURE_IE_WHITELIST     = 0x1000000; // Probe IE white listing
    /** @hide */
    public static final int WIFI_FEATURE_SCAN_RAND        = 0x2000000; // Random MAC & Probe seq
    /** @hide */
    public static final int WIFI_FEATURE_TX_POWER_LIMIT   = 0x4000000; // Set Tx power limit
    /** @hide */
    public static final int WIFI_FEATURE_WPA3_SAE         = 0x8000000; // WPA3-Personal SAE
    /** @hide */
    public static final int WIFI_FEATURE_WPA3_SUITE_B     = 0x10000000; // WPA3-Enterprise Suite-B
    /** @hide */
    public static final int WIFI_FEATURE_OWE              = 0x20000000; // Enhanced Open
    /** @hide */
    public static final int WIFI_FEATURE_LOW_LATENCY      = 0x40000000; // Low Latency modes
    /** @hide */
    public static final int WIFI_FEATURE_DPP              = 0x80000000; // DPP (Easy-Connect)
    /** @hide */
    public static final long WIFI_FEATURE_P2P_RAND_MAC    = 0x100000000L; // Random P2P MAC

    private long getSupportedFeatures() {
        try {
            return mService.getSupportedFeatures();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean isFeatureSupported(long feature) {
        return (getSupportedFeatures() & feature) == feature;
    }
    /**
     * @return true if this adapter supports 5 GHz band
     */
    public boolean is5GHzBandSupported() {
        return isFeatureSupported(WIFI_FEATURE_INFRA_5G);
    }

    /**
     * @return true if this adapter supports Passpoint
     * @hide
     */
    public boolean isPasspointSupported() {
        return isFeatureSupported(WIFI_FEATURE_PASSPOINT);
    }

    /**
     * @return true if this adapter supports WifiP2pManager (Wi-Fi Direct)
     */
    public boolean isP2pSupported() {
        return isFeatureSupported(WIFI_FEATURE_P2P);
    }

    /**
     * @return true if this adapter supports portable Wi-Fi hotspot
     * @hide
     */
    @SystemApi
    public boolean isPortableHotspotSupported() {
        return isFeatureSupported(WIFI_FEATURE_MOBILE_HOTSPOT);
    }

    /**
     * @return true if this adapter supports WifiScanner APIs
     * @hide
     */
    @SystemApi
    public boolean isWifiScannerSupported() {
        return isFeatureSupported(WIFI_FEATURE_SCANNER);
    }

    /**
     * @return true if this adapter supports Neighbour Awareness Network APIs
     * @hide
     */
    public boolean isWifiAwareSupported() {
        return isFeatureSupported(WIFI_FEATURE_AWARE);
    }

    /**
     * @deprecated Please use {@link android.content.pm.PackageManager#hasSystemFeature(String)}
     * with {@link android.content.pm.PackageManager#FEATURE_WIFI_RTT} and
     * {@link android.content.pm.PackageManager#FEATURE_WIFI_AWARE}.
     *
     * @return true if this adapter supports Device-to-device RTT
     * @hide
     */
    @Deprecated
    @SystemApi
    public boolean isDeviceToDeviceRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2D_RTT);
    }

    /**
     * @deprecated Please use {@link android.content.pm.PackageManager#hasSystemFeature(String)}
     * with {@link android.content.pm.PackageManager#FEATURE_WIFI_RTT}.
     *
     * @return true if this adapter supports Device-to-AP RTT
     */
    @Deprecated
    public boolean isDeviceToApRttSupported() {
        return isFeatureSupported(WIFI_FEATURE_D2AP_RTT);
    }

    /**
     * @return true if this adapter supports offloaded connectivity scan
     */
    public boolean isPreferredNetworkOffloadSupported() {
        return isFeatureSupported(WIFI_FEATURE_PNO);
    }

    /**
     * @return true if this adapter supports multiple simultaneous connections
     * @hide
     */
    public boolean isAdditionalStaSupported() {
        return isFeatureSupported(WIFI_FEATURE_ADDITIONAL_STA);
    }

    /**
     * @return true if this adapter supports Tunnel Directed Link Setup
     */
    public boolean isTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS);
    }

    /**
     * @return true if this adapter supports Off Channel Tunnel Directed Link Setup
     * @hide
     */
    public boolean isOffChannelTdlsSupported() {
        return isFeatureSupported(WIFI_FEATURE_TDLS_OFFCHANNEL);
    }

    /**
     * @return true if this adapter supports advanced power/performance counters
     */
    public boolean isEnhancedPowerReportingSupported() {
        return isFeatureSupported(WIFI_FEATURE_LINK_LAYER_STATS);
    }

    /**
     * Return the record of {@link WifiActivityEnergyInfo} object that
     * has the activity and energy info. This can be used to ascertain what
     * the controller has been up to, since the last sample.
     *
     * @return a record with {@link WifiActivityEnergyInfo} or null if
     * report is unavailable or unsupported
     * @hide
     */
    public WifiActivityEnergyInfo getControllerActivityEnergyInfo() {
        if (mService == null) return null;
        try {
            synchronized(this) {
                return mService.reportActivityInfo();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request a scan for access points. Returns immediately. The availability
     * of the results is made known later by means of an asynchronous event sent
     * on completion of the scan.
     * <p>
     * To initiate a Wi-Fi scan, declare the
     * {@link android.Manifest.permission#CHANGE_WIFI_STATE}
     * permission in the manifest, and perform these steps:
     * </p>
     * <ol style="1">
     * <li>Invoke the following method:
     * {@code ((WifiManager) getSystemService(WIFI_SERVICE)).startScan()}</li>
     * <li>
     * Register a BroadcastReceiver to listen to
     * {@code SCAN_RESULTS_AVAILABLE_ACTION}.</li>
     * <li>When a broadcast is received, call:
     * {@code ((WifiManager) getSystemService(WIFI_SERVICE)).getScanResults()}</li>
     * </ol>
     * @return {@code true} if the operation succeeded, i.e., the scan was initiated.
     * @deprecated The ability for apps to trigger scan requests will be removed in a future
     * release.
     */
    @Deprecated
    public boolean startScan() {
        return startScan(null);
    }

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public boolean startScan(WorkSource workSource) {
        try {
            String packageName = mContext.getOpPackageName();
            return mService.startScan(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * WPS has been deprecated from Client mode operation.
     *
     * @return null
     * @hide
     * @deprecated This API is deprecated
     */
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        return null;
    }

    /**
     * Return dynamic information about the current Wi-Fi connection, if any is active.
     * <p>
     * In the connected state, access to the SSID and BSSID requires
     * the same permissions as {@link #getScanResults}. If such access is not allowed,
     * {@link WifiInfo#getSSID} will return {@code "<unknown ssid>"} and
     * {@link WifiInfo#getBSSID} will return {@code "02:00:00:00:00:00"}.
     *
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    public WifiInfo getConnectionInfo() {
        try {
            return mService.getConnectionInfo(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the results of the latest access point scan.
     * @return the list of access points found in the most recent scan. An app must hold
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION} permission
     * in order to get valid results.
     */
    public List<ScanResult> getScanResults() {
        try {
            return mService.getScanResults(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if scanning is always available.
     *
     * If this return {@code true}, apps can issue {@link #startScan} and fetch scan results
     * even when Wi-Fi is turned off.
     *
     * To change this setting, see {@link #ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE}.
     * @deprecated The ability for apps to trigger scan requests will be removed in a future
     * release.
     */
    @Deprecated
    public boolean isScanAlwaysAvailable() {
        try {
            return mService.isScanAlwaysAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Tell the device to persist the current list of configured networks.
     * <p>
     * Note: It is possible for this method to change the network IDs of
     * existing networks. You should assume the network IDs can be different
     * after calling this method.
     *
     * @return {@code false}.
     * @deprecated There is no need to call this method -
     * {@link #addNetwork(WifiConfiguration)}, {@link #updateNetwork(WifiConfiguration)}
     * and {@link #removeNetwork(int)} already persist the configurations automatically.
     */
    @Deprecated
    public boolean saveConfiguration() {
        return false;
    }

    /**
     * Set the country code.
     * @param countryCode country code in ISO 3166 format.
     *
     * @hide
     */
    public void setCountryCode(@NonNull String country) {
        try {
            mService.setCountryCode(country);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
    * get the country code.
    * @return the country code in ISO 3166 format.
    *
    * @hide
    */
    @UnsupportedAppUsage
    public String getCountryCode() {
       try {
           String country = mService.getCountryCode();
           return country;
       } catch (RemoteException e) {
           throw e.rethrowFromSystemServer();
       }
    }

    /**
     * Check if the chipset supports dual frequency band (2.4 GHz and 5 GHz)
     * @return {@code true} if supported, {@code false} otherwise.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isDualBandSupported() {
        try {
            return mService.isDualBandSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if the chipset requires conversion of 5GHz Only apBand to ANY.
     * @return {@code true} if required, {@code false} otherwise.
     * @hide
     */
    public boolean isDualModeSupported() {
        try {
            return mService.needs5GHzToAnyApBandConversion();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enable or disable Wi-Fi.
     * <p>
     * Applications must have the {@link android.Manifest.permission#CHANGE_WIFI_STATE}
     * permission to toggle wifi.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     * @return {@code false} if the request cannot be satisfied; {@code true} indicates that wifi is
     *         either already in the requested state, or in progress toward the requested state.
     * @throws  {@link java.lang.SecurityException} if the caller is missing required permissions.
     *
     * @deprecated Starting with Build.VERSION_CODES#Q, applications are not allowed to
     * enable/disable Wi-Fi.
     * <b>Compatibility Note:</b> For applications targeting
     * {@link android.os.Build.VERSION_CODES#Q} or above, this API will always return {@code false}
     * and will have no effect. If apps are targeting an older SDK (
     * {@link android.os.Build.VERSION_CODES#P} or below), they can continue to use this API.
     */
    @Deprecated
    public boolean setWifiEnabled(boolean enabled) {
        try {
            return mService.setWifiEnabled(mContext.getOpPackageName(), enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
        getChannel().sendMessage(RSSI_PKTCNT_FETCH, 0, putListener(listener));
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
     * Call allowing ConnectivityService to update WifiService with interface mode changes.
     *
     * The possible modes include: {@link IFACE_IP_MODE_TETHERED},
     *                             {@link IFACE_IP_MODE_LOCAL_ONLY},
     *                             {@link IFACE_IP_MODE_CONFIGURATION_ERROR}
     *
     * @param ifaceName String name of the updated interface
     * @param mode int representing the new mode
     *
     * @hide
     */
    public void updateInterfaceIpState(String ifaceName, int mode) {
        try {
            mService.updateInterfaceIpState(ifaceName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start SoftAp mode with the specified configuration.
     * Note that starting in access point mode disables station
     * mode operation
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * @hide
     */
    public boolean startSoftAp(@Nullable WifiConfiguration wifiConfig) {
        try {
            return mService.startSoftAp(wifiConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop SoftAp mode.
     * Note that stopping softap mode will restore the previous wifi mode.
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * @hide
     */
    public boolean stopSoftAp() {
        try {
            return mService.stopSoftAp();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request a local only hotspot that an application can use to communicate between co-located
     * devices connected to the created WiFi hotspot.  The network created by this method will not
     * have Internet access.  Each application can make a single request for the hotspot, but
     * multiple applications could be requesting the hotspot at the same time.  When multiple
     * applications have successfully registered concurrently, they will be sharing the underlying
     * hotspot. {@link LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} is called
     * when the hotspot is ready for use by the application.
     * <p>
     * Each application can make a single active call to this method. The {@link
     * LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} callback supplies the
     * requestor with a {@link LocalOnlyHotspotReservation} that contains a
     * {@link WifiConfiguration} with the SSID, security type and credentials needed to connect
     * to the hotspot.  Communicating this information is up to the application.
     * <p>
     * If the LocalOnlyHotspot cannot be created, the {@link LocalOnlyHotspotCallback#onFailed(int)}
     * method will be called. Example failures include errors bringing up the network or if
     * there is an incompatible operating mode.  For example, if the user is currently using Wifi
     * Tethering to provide an upstream to another device, LocalOnlyHotspot will not start due to
     * an incompatible mode. The possible error codes include:
     * {@link LocalOnlyHotspotCallback#ERROR_NO_CHANNEL},
     * {@link LocalOnlyHotspotCallback#ERROR_GENERIC},
     * {@link LocalOnlyHotspotCallback#ERROR_INCOMPATIBLE_MODE} and
     * {@link LocalOnlyHotspotCallback#ERROR_TETHERING_DISALLOWED}.
     * <p>
     * Internally, requests will be tracked to prevent the hotspot from being torn down while apps
     * are still using it.  The {@link LocalOnlyHotspotReservation} object passed in the  {@link
     * LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} call should be closed when
     * the LocalOnlyHotspot is no longer needed using {@link LocalOnlyHotspotReservation#close()}.
     * Since the hotspot may be shared among multiple applications, removing the final registered
     * application request will trigger the hotspot teardown.  This means that applications should
     * not listen to broadcasts containing wifi state to determine if the hotspot was stopped after
     * they are done using it. Additionally, once {@link LocalOnlyHotspotReservation#close()} is
     * called, applications will not receive callbacks of any kind.
     * <p>
     * Applications should be aware that the user may also stop the LocalOnlyHotspot through the
     * Settings UI; it is not guaranteed to stay up as long as there is a requesting application.
     * The requestors will be notified of this case via
     * {@link LocalOnlyHotspotCallback#onStopped()}.  Other cases may arise where the hotspot is
     * torn down (Emergency mode, etc).  Application developers should be aware that it can stop
     * unexpectedly, but they will receive a notification if they have properly registered.
     * <p>
     * Applications should also be aware that this network will be shared with other applications.
     * Applications are responsible for protecting their data on this network (e.g., TLS).
     * <p>
     * Applications need to have the following permissions to start LocalOnlyHotspot: {@link
     * android.Manifest.permission#CHANGE_WIFI_STATE} and {@link
     * android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION}.  Callers without
     * the permissions will trigger a {@link java.lang.SecurityException}.
     * <p>
     * @param callback LocalOnlyHotspotCallback for the application to receive updates about
     * operating status.
     * @param handler Handler to be used for callbacks.  If the caller passes a null Handler, the
     * main thread will be used.
     */
    public void startLocalOnlyHotspot(LocalOnlyHotspotCallback callback,
            @Nullable Handler handler) {
        synchronized (mLock) {
            Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
            LocalOnlyHotspotCallbackProxy proxy =
                    new LocalOnlyHotspotCallbackProxy(this, looper, callback);
            try {
                String packageName = mContext.getOpPackageName();
                int returnCode = mService.startLocalOnlyHotspot(
                        proxy.getMessenger(), new Binder(), packageName);
                if (returnCode != LocalOnlyHotspotCallback.REQUEST_REGISTERED) {
                    // Send message to the proxy to make sure we call back on the correct thread
                    proxy.notifyFailed(returnCode);
                    return;
                }
                mLOHSCallbackProxy = proxy;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Cancels a pending local only hotspot request.  This can be used by the calling application to
     * cancel the existing request if the provided callback has not been triggered.  Calling this
     * method will be equivalent to closing the returned LocalOnlyHotspotReservation, but it is not
     * explicitly required.
     * <p>
     * When cancelling this request, application developers should be aware that there may still be
     * outstanding local only hotspot requests and the hotspot may still start, or continue running.
     * Additionally, if a callback was registered, it will no longer be triggered after calling
     * cancel.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void cancelLocalOnlyHotspotRequest() {
        synchronized (mLock) {
            stopLocalOnlyHotspot();
        }
    }

    /**
     *  Method used to inform WifiService that the LocalOnlyHotspot is no longer needed.  This
     *  method is used by WifiManager to release LocalOnlyHotspotReservations held by calling
     *  applications and removes the internal tracking for the hotspot request.  When all requesting
     *  applications are finished using the hotspot, it will be stopped and WiFi will return to the
     *  previous operational mode.
     *
     *  This method should not be called by applications.  Instead, they should call the close()
     *  method on their LocalOnlyHotspotReservation.
     */
    private void stopLocalOnlyHotspot() {
        synchronized (mLock) {
            if (mLOHSCallbackProxy == null) {
                // nothing to do, the callback was already cleaned up.
                return;
            }
            mLOHSCallbackProxy = null;
            try {
                mService.stopLocalOnlyHotspot();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Allow callers (Settings UI) to watch LocalOnlyHotspot state changes.  Callers will
     * receive a {@link LocalOnlyHotspotSubscription} object as a parameter of the
     * {@link LocalOnlyHotspotObserver#onRegistered(LocalOnlyHotspotSubscription)}. The registered
     * callers will receive the {@link LocalOnlyHotspotObserver#onStarted(WifiConfiguration)} and
     * {@link LocalOnlyHotspotObserver#onStopped()} callbacks.
     * <p>
     * Applications should have the
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION ACCESS_FINE_LOCATION}
     * permission.  Callers without the permission will trigger a
     * {@link java.lang.SecurityException}.
     * <p>
     * @param observer LocalOnlyHotspotObserver callback.
     * @param handler Handler to use for callbacks
     *
     * @hide
     */
    public void watchLocalOnlyHotspot(LocalOnlyHotspotObserver observer,
            @Nullable Handler handler) {
        synchronized (mLock) {
            Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
            mLOHSObserverProxy = new LocalOnlyHotspotObserverProxy(this, looper, observer);
            try {
                mService.startWatchLocalOnlyHotspot(
                        mLOHSObserverProxy.getMessenger(), new Binder());
                mLOHSObserverProxy.registered();
            } catch (RemoteException e) {
                mLOHSObserverProxy = null;
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Allow callers to stop watching LocalOnlyHotspot state changes.  After calling this method,
     * applications will no longer receive callbacks.
     *
     * @hide
     */
    public void unregisterLocalOnlyHotspotObserver() {
        synchronized (mLock) {
            if (mLOHSObserverProxy == null) {
                // nothing to do, the callback was already cleaned up
                return;
            }
            mLOHSObserverProxy = null;
            try {
                mService.stopWatchLocalOnlyHotspot();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets the Wi-Fi enabled state.
     * @return One of {@link #WIFI_AP_STATE_DISABLED},
     *         {@link #WIFI_AP_STATE_DISABLING}, {@link #WIFI_AP_STATE_ENABLED},
     *         {@link #WIFI_AP_STATE_ENABLING}, {@link #WIFI_AP_STATE_FAILED}
     * @see #isWifiApEnabled()
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
    public int getWifiApState() {
        try {
            return mService.getWifiApEnabledState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether Wi-Fi AP is enabled or disabled.
     * @return {@code true} if Wi-Fi AP is enabled
     * @see #getWifiApState()
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
    public boolean isWifiApEnabled() {
        return getWifiApState() == WIFI_AP_STATE_ENABLED;
    }

    /**
     * Gets the Wi-Fi AP Configuration.
     * @return AP details in WifiConfiguration
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_WIFI_STATE)
    public WifiConfiguration getWifiApConfiguration() {
        try {
            return mService.getWifiApConfiguration();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the Wi-Fi AP Configuration.  The AP configuration must either be open or
     * WPA2 PSK networks.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_WIFI_STATE)
    public boolean setWifiApConfiguration(WifiConfiguration wifiConfig) {
        try {
            return mService.setWifiApConfiguration(wifiConfig, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Method that triggers a notification to the user about a conversion to their saved AP config.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void notifyUserOfApBandConversion() {
        Log.d(TAG, "apBand was converted, notify the user");
        try {
            mService.notifyUserOfApBandConversion(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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

    /** @hide
     * @deprecated This is deprecated
     */
    public static final int START_WPS                       = BASE + 10;
    /** @hide
     * @deprecated This is deprecated
     */
    public static final int START_WPS_SUCCEEDED             = BASE + 11;
    /** @hide
     * @deprecated This is deprecated
     */
    public static final int WPS_FAILED                      = BASE + 12;
    /** @hide
     * @deprecated This is deprecated
     */
    public static final int WPS_COMPLETED                   = BASE + 13;

    /** @hide
     * @deprecated This is deprecated
     */
    public static final int CANCEL_WPS                      = BASE + 14;
    /** @hide
     * @deprecated This is deprecated
     */
    public static final int CANCEL_WPS_FAILED               = BASE + 15;
    /** @hide
     * @deprecated This is deprecated
     */
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
    /** WPS overlap detected
     * @deprecated This is deprecated
     */
    public static final int WPS_OVERLAP_ERROR           = 3;
    /** WEP on WPS is prohibited
     * @deprecated This is deprecated
     */
    public static final int WPS_WEP_PROHIBITED          = 4;
    /** TKIP only prohibited
     * @deprecated This is deprecated
     */
    public static final int WPS_TKIP_ONLY_PROHIBITED    = 5;
    /** Authentication failure on WPS
     * @deprecated This is deprecated
     */
    public static final int WPS_AUTH_FAILURE            = 6;
    /** WPS timed out
     * @deprecated This is deprecated
     */
    public static final int WPS_TIMED_OUT               = 7;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to invalid inputs
     * @hide
     */
    public static final int INVALID_ARGS                = 8;

    /**
     * Passed with {@link ActionListener#onFailure}.
     * Indicates that the operation failed due to user permissions.
     * @hide
     */
    public static final int NOT_AUTHORIZED              = 9;

    /**
     * Interface for callback invocation on an application action
     * @hide
     */
    @SystemApi
    public interface ActionListener {
        /**
         * The operation succeeded.
         * This is called when the scan request has been validated and ready
         * to sent to driver.
         */
        public void onSuccess();
        /**
         * The operation failed.
         * This is called when the scan request failed.
         * @param reason The reason for failure could be one of the following:
         * {@link #REASON_INVALID_REQUEST}} is specified when scan request parameters are invalid.
         * {@link #REASON_NOT_AUTHORIZED} is specified when requesting app doesn't have the required
         * permission to request a scan.
         * {@link #REASON_UNSPECIFIED} is specified when driver reports a scan failure.
         */
        public void onFailure(int reason);
    }

    /** Interface for callback invocation on a start WPS action
     * @deprecated This is deprecated
     */
    public static abstract class WpsCallback {

        /** WPS start succeeded
         * @deprecated This API is deprecated
         */
        public abstract void onStarted(String pin);

        /** WPS operation completed successfully
         * @deprecated This API is deprecated
         */
        public abstract void onSucceeded();

        /**
         * WPS operation failed
         * @param reason The reason for failure could be one of
         * {@link #WPS_TKIP_ONLY_PROHIBITED}, {@link #WPS_OVERLAP_ERROR},
         * {@link #WPS_WEP_PROHIBITED}, {@link #WPS_TIMED_OUT} or {@link #WPS_AUTH_FAILURE}
         * and some generic errors.
         * @deprecated This API is deprecated
         */
        public abstract void onFailed(int reason);
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

    /**
     * Base class for soft AP callback. Should be extended by applications and set when calling
     * {@link WifiManager#registerSoftApCallback(SoftApCallback, Handler)}.
     *
     * @hide
     */
    public interface SoftApCallback {
        /**
         * Called when soft AP state changes.
         *
         * @param state new new AP state. One of {@link #WIFI_AP_STATE_DISABLED},
         *        {@link #WIFI_AP_STATE_DISABLING}, {@link #WIFI_AP_STATE_ENABLED},
         *        {@link #WIFI_AP_STATE_ENABLING}, {@link #WIFI_AP_STATE_FAILED}
         * @param failureReason reason when in failed state. One of
         *        {@link #SAP_START_FAILURE_GENERAL}, {@link #SAP_START_FAILURE_NO_CHANNEL}
         */
        public abstract void onStateChanged(@WifiApState int state,
                @SapStartFailure int failureReason);

        /**
         * Called when number of connected clients to soft AP changes.
         *
         * @param numClients number of connected clients
         */
        public abstract void onNumClientsChanged(int numClients);
    }

    /**
     * Callback proxy for SoftApCallback objects.
     *
     * @hide
     */
    private class SoftApCallbackProxy extends ISoftApCallback.Stub {
        private final Handler mHandler;
        private final SoftApCallback mCallback;

        SoftApCallbackProxy(Looper looper, SoftApCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onStateChanged(int state, int failureReason) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "SoftApCallbackProxy: onStateChanged: state=" + state
                        + ", failureReason=" + failureReason);
            }
            mHandler.post(() -> {
                mCallback.onStateChanged(state, failureReason);
            });
        }

        @Override
        public void onNumClientsChanged(int numClients) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "SoftApCallbackProxy: onNumClientsChanged: numClients=" + numClients);
            }
            mHandler.post(() -> {
                mCallback.onNumClientsChanged(numClients);
            });
        }
    }

    /**
     * Registers a callback for Soft AP. See {@link SoftApCallback}. Caller will receive the current
     * soft AP state and number of connected devices immediately after a successful call to this API
     * via callback. Note that receiving an immediate WIFI_AP_STATE_FAILED value for soft AP state
     * indicates that the latest attempt to start soft AP has failed. Caller can unregister a
     * previously registered callback using {@link unregisterSoftApCallback}
     * <p>
     * Applications should have the
     * {@link android.Manifest.permission#NETWORK_SETTINGS NETWORK_SETTINGS} permission. Callers
     * without the permission will trigger a {@link java.lang.SecurityException}.
     * <p>
     *
     * @param callback Callback for soft AP events
     * @param handler  The Handler on whose thread to execute the callbacks of the {@code callback}
     *                 object. If null, then the application's main thread will be used.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void registerSoftApCallback(@NonNull SoftApCallback callback,
                                       @Nullable Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "registerSoftApCallback: callback=" + callback + ", handler=" + handler);

        Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
        Binder binder = new Binder();
        try {
            mService.registerSoftApCallback(binder, new SoftApCallbackProxy(looper, callback),
                    callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow callers to unregister a previously registered callback. After calling this method,
     * applications will no longer receive soft AP events.
     *
     * @param callback Callback to unregister for soft AP events
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void unregisterSoftApCallback(@NonNull SoftApCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "unregisterSoftApCallback: callback=" + callback);

        try {
            mService.unregisterSoftApCallback(callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * LocalOnlyHotspotReservation that contains the {@link WifiConfiguration} for the active
     * LocalOnlyHotspot request.
     * <p>
     * Applications requesting LocalOnlyHotspot for sharing will receive an instance of the
     * LocalOnlyHotspotReservation in the
     * {@link LocalOnlyHotspotCallback#onStarted(LocalOnlyHotspotReservation)} call.  This
     * reservation contains the relevant {@link WifiConfiguration}.
     * When an application is done with the LocalOnlyHotspot, they should call {@link
     * LocalOnlyHotspotReservation#close()}.  Once this happens, the application will not receive
     * any further callbacks. If the LocalOnlyHotspot is stopped due to a
     * user triggered mode change, applications will be notified via the {@link
     * LocalOnlyHotspotCallback#onStopped()} callback.
     */
    public class LocalOnlyHotspotReservation implements AutoCloseable {

        private final CloseGuard mCloseGuard = CloseGuard.get();
        private final WifiConfiguration mConfig;

        /** @hide */
        @VisibleForTesting
        public LocalOnlyHotspotReservation(WifiConfiguration config) {
            mConfig = config;
            mCloseGuard.open("close");
        }

        public WifiConfiguration getWifiConfiguration() {
            return mConfig;
        }

        @Override
        public void close() {
            try {
                stopLocalOnlyHotspot();
                mCloseGuard.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop Local Only Hotspot.");
            }
        }

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
    }

    /**
     * Callback class for applications to receive updates about the LocalOnlyHotspot status.
     */
    public static class LocalOnlyHotspotCallback {
        /** @hide */
        public static final int REQUEST_REGISTERED = 0;

        public static final int ERROR_NO_CHANNEL = 1;
        public static final int ERROR_GENERIC = 2;
        public static final int ERROR_INCOMPATIBLE_MODE = 3;
        public static final int ERROR_TETHERING_DISALLOWED = 4;

        /** LocalOnlyHotspot start succeeded. */
        public void onStarted(LocalOnlyHotspotReservation reservation) {};

        /**
         * LocalOnlyHotspot stopped.
         * <p>
         * The LocalOnlyHotspot can be disabled at any time by the user.  When this happens,
         * applications will be notified that it was stopped. This will not be invoked when an
         * application calls {@link LocalOnlyHotspotReservation#close()}.
         */
        public void onStopped() {};

        /**
         * LocalOnlyHotspot failed to start.
         * <p>
         * Applications can attempt to call
         * {@link WifiManager#startLocalOnlyHotspot(LocalOnlyHotspotCallback, Handler)} again at
         * a later time.
         * <p>
         * @param reason The reason for failure could be one of: {@link
         * #ERROR_TETHERING_DISALLOWED}, {@link #ERROR_INCOMPATIBLE_MODE},
         * {@link #ERROR_NO_CHANNEL}, or {@link #ERROR_GENERIC}.
         */
        public void onFailed(int reason) { };
    }

    /**
     * Callback proxy for LocalOnlyHotspotCallback objects.
     */
    private static class LocalOnlyHotspotCallbackProxy {
        private final Handler mHandler;
        private final WeakReference<WifiManager> mWifiManager;
        private final Looper mLooper;
        private final Messenger mMessenger;

        /**
         * Constructs a {@link LocalOnlyHotspotCallback} using the specified looper.  All callbacks
         * will be delivered on the thread of the specified looper.
         *
         * @param manager WifiManager
         * @param looper Looper for delivering callbacks
         * @param callback LocalOnlyHotspotCallback to notify the calling application.
         */
        LocalOnlyHotspotCallbackProxy(WifiManager manager, Looper looper,
                final LocalOnlyHotspotCallback callback) {
            mWifiManager = new WeakReference<>(manager);
            mLooper = looper;

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    Log.d(TAG, "LocalOnlyHotspotCallbackProxy: handle message what: "
                            + msg.what + " msg: " + msg);

                    WifiManager manager = mWifiManager.get();
                    if (manager == null) {
                        Log.w(TAG, "LocalOnlyHotspotCallbackProxy: handle message post GC");
                        return;
                    }

                    switch (msg.what) {
                        case HOTSPOT_STARTED:
                            WifiConfiguration config = (WifiConfiguration) msg.obj;
                            if (config == null) {
                                Log.e(TAG, "LocalOnlyHotspotCallbackProxy: config cannot be null.");
                                callback.onFailed(LocalOnlyHotspotCallback.ERROR_GENERIC);
                                return;
                            }
                            callback.onStarted(manager.new LocalOnlyHotspotReservation(config));
                            break;
                        case HOTSPOT_STOPPED:
                            Log.w(TAG, "LocalOnlyHotspotCallbackProxy: hotspot stopped");
                            callback.onStopped();
                            break;
                        case HOTSPOT_FAILED:
                            int reasonCode = msg.arg1;
                            Log.w(TAG, "LocalOnlyHotspotCallbackProxy: failed to start.  reason: "
                                    + reasonCode);
                            callback.onFailed(reasonCode);
                            Log.w(TAG, "done with the callback...");
                            break;
                        default:
                            Log.e(TAG, "LocalOnlyHotspotCallbackProxy unhandled message.  type: "
                                    + msg.what);
                    }
                }
            };
            mMessenger = new Messenger(mHandler);
        }

        public Messenger getMessenger() {
            return mMessenger;
        }

        /**
         * Helper method allowing the the incoming application call to move the onFailed callback
         * over to the desired callback thread.
         *
         * @param reason int representing the error type
         */
        public void notifyFailed(int reason) throws RemoteException {
            Message msg = Message.obtain();
            msg.what = HOTSPOT_FAILED;
            msg.arg1 = reason;
            mMessenger.send(msg);
        }
    }

    /**
     * LocalOnlyHotspotSubscription that is an AutoCloseable object for tracking applications
     * watching for LocalOnlyHotspot changes.
     *
     * @hide
     */
    public class LocalOnlyHotspotSubscription implements AutoCloseable {
        private final CloseGuard mCloseGuard = CloseGuard.get();

        /** @hide */
        @VisibleForTesting
        public LocalOnlyHotspotSubscription() {
            mCloseGuard.open("close");
        }

        @Override
        public void close() {
            try {
                unregisterLocalOnlyHotspotObserver();
                mCloseGuard.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister LocalOnlyHotspotObserver.");
            }
        }

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
    }

    /**
     * Class to notify calling applications that watch for changes in LocalOnlyHotspot of updates.
     *
     * @hide
     */
    public static class LocalOnlyHotspotObserver {
        /**
         * Confirm registration for LocalOnlyHotspotChanges by returning a
         * LocalOnlyHotspotSubscription.
         */
        public void onRegistered(LocalOnlyHotspotSubscription subscription) {};

        /**
         * LocalOnlyHotspot started with the supplied config.
         */
        public void onStarted(WifiConfiguration config) {};

        /**
         * LocalOnlyHotspot stopped.
         */
        public void onStopped() {};
    }

    /**
     * Callback proxy for LocalOnlyHotspotObserver objects.
     */
    private static class LocalOnlyHotspotObserverProxy {
        private final Handler mHandler;
        private final WeakReference<WifiManager> mWifiManager;
        private final Looper mLooper;
        private final Messenger mMessenger;

        /**
         * Constructs a {@link LocalOnlyHotspotObserverProxy} using the specified looper.
         * All callbacks will be delivered on the thread of the specified looper.
         *
         * @param manager WifiManager
         * @param looper Looper for delivering callbacks
         * @param observer LocalOnlyHotspotObserver to notify the calling application.
         */
        LocalOnlyHotspotObserverProxy(WifiManager manager, Looper looper,
                final LocalOnlyHotspotObserver observer) {
            mWifiManager = new WeakReference<>(manager);
            mLooper = looper;

            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    Log.d(TAG, "LocalOnlyHotspotObserverProxy: handle message what: "
                            + msg.what + " msg: " + msg);

                    WifiManager manager = mWifiManager.get();
                    if (manager == null) {
                        Log.w(TAG, "LocalOnlyHotspotObserverProxy: handle message post GC");
                        return;
                    }

                    switch (msg.what) {
                        case HOTSPOT_OBSERVER_REGISTERED:
                            observer.onRegistered(manager.new LocalOnlyHotspotSubscription());
                            break;
                        case HOTSPOT_STARTED:
                            WifiConfiguration config = (WifiConfiguration) msg.obj;
                            if (config == null) {
                                Log.e(TAG, "LocalOnlyHotspotObserverProxy: config cannot be null.");
                                return;
                            }
                            observer.onStarted(config);
                            break;
                        case HOTSPOT_STOPPED:
                            observer.onStopped();
                            break;
                        default:
                            Log.e(TAG, "LocalOnlyHotspotObserverProxy unhandled message.  type: "
                                    + msg.what);
                    }
                }
            };
            mMessenger = new Messenger(mHandler);
        }

        public Messenger getMessenger() {
            return mMessenger;
        }

        public void registered() throws RemoteException {
            Message msg = Message.obtain();
            msg.what = HOTSPOT_OBSERVER_REGISTERED;
            mMessenger.send(msg);
        }
    }

    // Ensure that multiple ServiceHandler threads do not interleave message dispatch.
    private static final Object sServiceHandlerDispatchLock = new Object();

    private class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            synchronized (sServiceHandlerDispatchLock) {
                dispatchMessageToListeners(message);
            }
        }

        private void dispatchMessageToListeners(Message message) {
            Object listener = removeListener(message.arg2);
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    } else {
                        Log.e(TAG, "Failed to set up channel connection");
                        // This will cause all further async API calls on the WifiManager
                        // to fail and throw an exception
                        mAsyncChannel = null;
                    }
                    mConnected.countDown();
                    break;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    // Ignore
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel connection lost");
                    // This will cause all further async API calls on the WifiManager
                    // to fail and throw an exception
                    mAsyncChannel = null;
                    getLooper().quit();
                    break;
                    /* ActionListeners grouped together */
                case WifiManager.CONNECT_NETWORK_FAILED:
                case WifiManager.FORGET_NETWORK_FAILED:
                case WifiManager.SAVE_NETWORK_FAILED:
                case WifiManager.DISABLE_NETWORK_FAILED:
                    if (listener != null) {
                        ((ActionListener) listener).onFailure(message.arg1);
                    }
                    break;
                    /* ActionListeners grouped together */
                case WifiManager.CONNECT_NETWORK_SUCCEEDED:
                case WifiManager.FORGET_NETWORK_SUCCEEDED:
                case WifiManager.SAVE_NETWORK_SUCCEEDED:
                case WifiManager.DISABLE_NETWORK_SUCCEEDED:
                    if (listener != null) {
                        ((ActionListener) listener).onSuccess();
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

    private int putListener(Object listener) {
        if (listener == null) return INVALID_KEY;
        int key;
        synchronized (mListenerMapLock) {
            do {
                key = mListenerKey++;
            } while (key == INVALID_KEY);
            mListenerMap.put(key, listener);
        }
        return key;
    }

    private Object removeListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (mListenerMapLock) {
            Object listener = mListenerMap.get(key);
            mListenerMap.remove(key);
            return listener;
        }
    }

    private synchronized AsyncChannel getChannel() {
        if (mAsyncChannel == null) {
            Messenger messenger = getWifiServiceMessenger();
            if (messenger == null) {
                throw new IllegalStateException(
                        "getWifiServiceMessenger() returned null!  This is invalid.");
            }

            mAsyncChannel = new AsyncChannel();
            mConnected = new CountDownLatch(1);

            Handler handler = new ServiceHandler(mLooper);
            mAsyncChannel.connect(mContext, handler, messenger);
            try {
                mConnected.await();
            } catch (InterruptedException e) {
                Log.e(TAG, "interrupted wait at init");
            }
        }
        return mAsyncChannel;
    }

    /**
     * Connect to a network with the given configuration. The network also
     * gets added to the list of configured networks for the foreground user.
     *
     * For a new network, this function is used instead of a
     * sequence of addNetwork(), enableNetwork(), and reconnect()
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD,
            android.Manifest.permission.NETWORK_STACK
    })
    public void connect(@NonNull WifiConfiguration config, @Nullable ActionListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        // Use INVALID_NETWORK_ID for arg1 when passing a config object
        // arg1 is used to pass network id when the network already exists
        getChannel().sendMessage(CONNECT_NETWORK, WifiConfiguration.INVALID_NETWORK_ID,
                putListener(listener), config);
    }

    /**
     * Connect to a network with the given networkId.
     *
     * This function is used instead of a enableNetwork() and reconnect()
     *
     * @param networkId the ID of the network as returned by {@link #addNetwork} or {@link
     *        getConfiguredNetworks}.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD,
            android.Manifest.permission.NETWORK_STACK
    })
    public void connect(int networkId, @Nullable ActionListener listener) {
        if (networkId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        getChannel().sendMessage(CONNECT_NETWORK, networkId, putListener(listener));
    }

    /**
     * Save the given network to the list of configured networks for the
     * foreground user. If the network already exists, the configuration
     * is updated. Any new network is enabled by default.
     *
     * For a new network, this function is used instead of a
     * sequence of addNetwork() and enableNetwork().
     *
     * For an existing network, it accomplishes the task of updateNetwork()
     *
     * This API will cause reconnect if the crecdentials of the current active
     * connection has been changed.
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD,
            android.Manifest.permission.NETWORK_STACK
    })
    public void save(@NonNull WifiConfiguration config, @Nullable ActionListener listener) {
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        getChannel().sendMessage(SAVE_NETWORK, 0, putListener(listener), config);
    }

    /**
     * Delete the network from the list of configured networks for the
     * foreground user.
     *
     * This function is used instead of a sequence of removeNetwork()
     *
     * @param config the set of variables that describe the configuration,
     *            contained in a {@link WifiConfiguration} object.
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be
     * initialized again
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD,
            android.Manifest.permission.NETWORK_STACK
    })
    public void forget(int netId, @Nullable ActionListener listener) {
        if (netId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        getChannel().sendMessage(FORGET_NETWORK, netId, putListener(listener));
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
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD,
            android.Manifest.permission.NETWORK_STACK
    })
    public void disable(int netId, @Nullable ActionListener listener) {
        if (netId < 0) throw new IllegalArgumentException("Network id cannot be negative");
        getChannel().sendMessage(DISABLE_NETWORK, netId, putListener(listener));
    }

    /**
     * Disable ephemeral Network
     *
     * @param SSID, in the format of WifiConfiguration's SSID.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_STACK
    })
    public void disableEphemeralNetwork(String SSID) {
        if (SSID == null) throw new IllegalArgumentException("SSID cannot be null");
        try {
            mService.disableEphemeralNetwork(SSID, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * WPS suport has been deprecated from Client mode and this method will immediately trigger
     * {@link WpsCallback#onFailed(int)} with a generic error.
     *
     * @param config WPS configuration (does not support {@link WpsInfo#LABEL})
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be initialized again
     * @deprecated This API is deprecated
     */
    public void startWps(WpsInfo config, WpsCallback listener) {
        if (listener != null ) {
            listener.onFailed(ERROR);
        }
    }

    /**
     * WPS support has been deprecated from Client mode and this method will immediately trigger
     * {@link WpsCallback#onFailed(int)} with a generic error.
     *
     * @param listener for callbacks on success or failure. Can be null.
     * @throws IllegalStateException if the WifiManager instance needs to be initialized again
     * @deprecated This API is deprecated
     */
    public void cancelWps(WpsCallback listener) {
        if (listener != null) {
            listener.onFailed(ERROR);
        }
    }

    /**
     * Get a reference to WifiService handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     *
     * @return Messenger pointing to the WifiService handler
     */
    @UnsupportedAppUsage
    private Messenger getWifiServiceMessenger() {
        try {
            return mService.getWifiServiceMessenger(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * permission in an {@code <uses-permission>} element of the application's manifest.
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
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
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
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
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
                if (ws != null && ws.isEmpty()) {
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
                        changed = !mWorkSource.equals(ws);
                        if (changed) {
                            mWorkSource.set(ws);
                        }
                    }
                }
                if (changed && mHeld) {
                    try {
                        mService.updateWifiLockWorkSource(mBinder, mWorkSource);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
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
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }
    }

    /**
     * Creates a new WifiLock.
     *
     * @param lockType the type of lock to create. See {@link #WIFI_MODE_FULL_HIGH_PERF}
     * and {@link #WIFI_MODE_FULL_LOW_LATENCY} for descriptions of the types of Wi-Fi locks.
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
     *
     * @deprecated This API is non-functional.
     */
    @Deprecated
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
     * addresses.  Processing these extra packets can cause a noticeable
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
                                mService.releaseMulticastLock(mTag);
                                throw new UnsupportedOperationException(
                                        "Exceeded maximum number of wifi locks");
                            }
                            mActiveLockCount++;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
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
                        mService.releaseMulticastLock(mTag);
                        synchronized (WifiManager.this) {
                            mActiveLockCount--;
                        }
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Initialize the multicast filtering to 'on'
     * @hide no intent to publish
     */
    @UnsupportedAppUsage
    public boolean initializeMulticastFiltering() {
        try {
            mService.initializeMulticastFiltering();
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    protected void finalize() throws Throwable {
        try {
            if (mAsyncChannel != null) {
                mAsyncChannel.disconnect();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Set wifi verbose log. Called from developer settings.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    @UnsupportedAppUsage
    public void enableVerboseLogging (int verbose) {
        try {
            mService.enableVerboseLogging(verbose);
        } catch (Exception e) {
            //ignore any failure here
            Log.e(TAG, "enableVerboseLogging " + e.toString());
        }
    }

    /**
     * Get the WiFi verbose logging level.This is used by settings
     * to decide what to show within the picker.
     * @hide
     */
    @UnsupportedAppUsage
    public int getVerboseLoggingLevel() {
        try {
            return mService.getVerboseLoggingLevel();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all saved wifi networks.
     *
     * @hide
     */
    public void factoryReset() {
        try {
            mService.factoryReset(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get Network object of current wifi network
     * @return Get Network object of current wifi network
     * @hide
     */
    @UnsupportedAppUsage
    public Network getCurrentNetwork() {
        try {
            return mService.getCurrentNetwork();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deprecated
     * returns false
     * @hide
     * @deprecated
     */
    public boolean setEnableAutoJoinWhenAssociated(boolean enabled) {
        return false;
    }

    /**
     * Deprecated
     * returns false
     * @hide
     * @deprecated
     */
    public boolean getEnableAutoJoinWhenAssociated() {
        return false;
    }

    /**
     * Enable/disable WifiConnectivityManager
     * @hide
     */
    public void enableWifiConnectivityManager(boolean enabled) {
        try {
            mService.enableWifiConnectivityManager(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve the data to be backed to save the current state.
     * @hide
     */
    public byte[] retrieveBackupData() {
        try {
            return mService.retrieveBackupData();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restore state from the backed up data.
     * @hide
     */
    public void restoreBackupData(byte[] data) {
        try {
            mService.restoreBackupData(data);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restore state from the older version of back up data.
     * The old backup data was essentially a backup of wpa_supplicant.conf
     * and ipconfig.txt file.
     * @deprecated this is no longer supported.
     * @hide
     */
    @Deprecated
    public void restoreSupplicantBackupData(byte[] supplicantData, byte[] ipConfigData) {
        try {
            mService.restoreSupplicantBackupData(supplicantData, ipConfigData);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start subscription provisioning flow
     *
     * @param provider {@link OsuProvider} to provision with
     * @param executor the Executor on which to run the callback.
     * @param callback {@link ProvisioningCallback} for updates regarding provisioning flow
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD
    })
    public void startSubscriptionProvisioning(@NonNull OsuProvider provider,
            @NonNull @CallbackExecutor Executor executor, @NonNull ProvisioningCallback callback) {
        // Verify arguments
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        try {
            mService.startSubscriptionProvisioning(provider,
                    new ProvisioningCallbackProxy(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Helper class to support OSU Provisioning callbacks
     */
    private static class ProvisioningCallbackProxy extends IProvisioningCallback.Stub {
        private final Executor mExecutor;
        private final ProvisioningCallback mCallback;

        ProvisioningCallbackProxy(Executor executor, ProvisioningCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onProvisioningStatus(int status) {
            mExecutor.execute(() -> mCallback.onProvisioningStatus(status));
        }

        @Override
        public void onProvisioningFailure(int status) {
            mExecutor.execute(() -> mCallback.onProvisioningFailure(status));
        }

        @Override
        public void onProvisioningComplete() {
            mExecutor.execute(() -> mCallback.onProvisioningComplete());
        }
    }

    /**
     * Base class for Traffic state callback. Should be extended by applications and set when
     * calling {@link WifiManager#registerTrafficStateCallback(TrafficStateCallback, Handler)}.
     * @hide
     */
    public interface TrafficStateCallback {
        /**
         * Lowest bit indicates data reception and the second lowest
         * bit indicates data transmitted
         */
        /** @hide */
        int DATA_ACTIVITY_NONE         = 0x00;
        /** @hide */
        int DATA_ACTIVITY_IN           = 0x01;
        /** @hide */
        int DATA_ACTIVITY_OUT          = 0x02;
        /** @hide */
        int DATA_ACTIVITY_INOUT        = 0x03;

        /**
         * Callback invoked to inform clients about the current traffic state.
         *
         * @param state One of the values: {@link #DATA_ACTIVITY_NONE}, {@link #DATA_ACTIVITY_IN},
         * {@link #DATA_ACTIVITY_OUT} & {@link #DATA_ACTIVITY_INOUT}.
         * @hide
         */
        void onStateChanged(int state);
    }

    /**
     * Callback proxy for TrafficStateCallback objects.
     *
     * @hide
     */
    private class TrafficStateCallbackProxy extends ITrafficStateCallback.Stub {
        private final Handler mHandler;
        private final TrafficStateCallback mCallback;

        TrafficStateCallbackProxy(Looper looper, TrafficStateCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onStateChanged(int state) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "TrafficStateCallbackProxy: onStateChanged state=" + state);
            }
            mHandler.post(() -> {
                mCallback.onStateChanged(state);
            });
        }
    }

    /**
     * Registers a callback for monitoring traffic state. See {@link TrafficStateCallback}. These
     * callbacks will be invoked periodically by platform to inform clients about the current
     * traffic state. Caller can unregister a previously registered callback using
     * {@link #unregisterTrafficStateCallback(TrafficStateCallback)}
     * <p>
     * Applications should have the
     * {@link android.Manifest.permission#NETWORK_SETTINGS NETWORK_SETTINGS} permission. Callers
     * without the permission will trigger a {@link java.lang.SecurityException}.
     * <p>
     *
     * @param callback Callback for traffic state events
     * @param handler  The Handler on whose thread to execute the callbacks of the {@code callback}
     *                 object. If null, then the application's main thread will be used.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void registerTrafficStateCallback(@NonNull TrafficStateCallback callback,
                                             @Nullable Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "registerTrafficStateCallback: callback=" + callback + ", handler=" + handler);

        Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
        Binder binder = new Binder();
        try {
            mService.registerTrafficStateCallback(
                    binder, new TrafficStateCallbackProxy(looper, callback), callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow callers to unregister a previously registered callback. After calling this method,
     * applications will no longer receive traffic state notifications.
     *
     * @param callback Callback to unregister for traffic state events
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public void unregisterTrafficStateCallback(@NonNull TrafficStateCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "unregisterTrafficStateCallback: callback=" + callback);

        try {
            mService.unregisterTrafficStateCallback(callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Helper method to update the local verbose logging flag based on the verbose logging
     * level from wifi service.
     */
    private void updateVerboseLoggingEnabledFromService() {
        mVerboseLoggingEnabled = getVerboseLoggingLevel() > 0;
    }

    /**
     * @return true if this device supports WPA3-Personal SAE
     */
    public boolean isWpa3SaeSupported() {
        return isFeatureSupported(WIFI_FEATURE_WPA3_SAE);
    }

    /**
     * @return true if this device supports WPA3-Enterprise Suite-B-192
     */
    public boolean isWpa3SuiteBSupported() {
        return isFeatureSupported(WIFI_FEATURE_WPA3_SUITE_B);
    }

    /**
     * @return true if this device supports Wi-Fi Enhanced Open (OWE)
     */
    public boolean isEnhancedOpenSupported() {
        return isFeatureSupported(WIFI_FEATURE_OWE);
    }

    /**
     * Wi-Fi Easy Connect (DPP) introduces standardized mechanisms to simplify the provisioning and
     * configuration of Wi-Fi devices.
     * For more details, visit <a href="https://www.wi-fi.org/">https://www.wi-fi.org/</a> and
     * search for "Easy Connect" or "Device Provisioning Protocol specification".
     *
     * @return true if this device supports Wi-Fi Easy-connect (Device Provisioning Protocol)
     */
    public boolean isEasyConnectSupported() {
        return isFeatureSupported(WIFI_FEATURE_DPP);
    }

    /**
     * Gets the factory Wi-Fi MAC addresses.
     * @return Array of String representing Wi-Fi MAC addresses sorted lexically or an empty Array
     * if failed.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_SETTINGS)
    public String[] getFactoryMacAddresses() {
        try {
            return mService.getFactoryMacAddresses();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"DEVICE_MOBILITY_STATE_"}, value = {
            DEVICE_MOBILITY_STATE_UNKNOWN,
            DEVICE_MOBILITY_STATE_HIGH_MVMT,
            DEVICE_MOBILITY_STATE_LOW_MVMT,
            DEVICE_MOBILITY_STATE_STATIONARY})
    public @interface DeviceMobilityState {}

    /**
     * Unknown device mobility state
     *
     * @see #setDeviceMobilityState(int)
     *
     * @hide
     */
    @SystemApi
    public static final int DEVICE_MOBILITY_STATE_UNKNOWN = 0;

    /**
     * High movement device mobility state.
     * e.g. on a bike, in a motor vehicle
     *
     * @see #setDeviceMobilityState(int)
     *
     * @hide
     */
    @SystemApi
    public static final int DEVICE_MOBILITY_STATE_HIGH_MVMT = 1;

    /**
     * Low movement device mobility state.
     * e.g. walking, running
     *
     * @see #setDeviceMobilityState(int)
     *
     * @hide
     */
    @SystemApi
    public static final int DEVICE_MOBILITY_STATE_LOW_MVMT = 2;

    /**
     * Stationary device mobility state
     *
     * @see #setDeviceMobilityState(int)
     *
     * @hide
     */
    @SystemApi
    public static final int DEVICE_MOBILITY_STATE_STATIONARY = 3;

    /**
     * Updates the device mobility state. Wifi uses this information to adjust the interval between
     * Wifi scans in order to balance power consumption with scan accuracy.
     * The default mobility state when the device boots is {@link #DEVICE_MOBILITY_STATE_UNKNOWN}.
     * This API should be called whenever there is a change in the mobility state.
     * @param state the updated device mobility state
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WIFI_SET_DEVICE_MOBILITY_STATE)
    public void setDeviceMobilityState(@DeviceMobilityState int state) {
        try {
            mService.setDeviceMobilityState(state);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /* Easy Connect - AKA Device Provisioning Protocol (DPP) */

    /**
     * Easy Connect Network role: Station.
     *
     * @hide
     */
    @SystemApi
    public static final int EASY_CONNECT_NETWORK_ROLE_STA = 0;

    /**
     * Easy Connect Network role: Access Point.
     *
     * @hide
     */
    @SystemApi
    public static final int EASY_CONNECT_NETWORK_ROLE_AP = 1;

    /** @hide */
    @IntDef(prefix = {"EASY_CONNECT_NETWORK_ROLE_"}, value = {
            EASY_CONNECT_NETWORK_ROLE_STA,
            EASY_CONNECT_NETWORK_ROLE_AP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EasyConnectNetworkRole {
    }

    /**
     * Start Easy Connect (DPP) in Configurator-Initiator role. The current device will initiate
     * Easy Connect bootstrapping with a peer, and configure the peer with the SSID and password of
     * the specified network using the Easy Connect protocol on an encrypted link.
     *
     * @param enrolleeUri         URI of the Enrollee obtained separately (e.g. QR code scanning)
     * @param selectedNetworkId   Selected network ID to be sent to the peer
     * @param enrolleeNetworkRole The network role of the enrollee
     * @param callback            Callback for status updates
     * @param executor            The Executor on which to run the callback.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public void startEasyConnectAsConfiguratorInitiator(@NonNull String enrolleeUri,
            int selectedNetworkId, @EasyConnectNetworkRole int enrolleeNetworkRole,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull EasyConnectStatusCallback callback) {
        Binder binder = new Binder();
        try {
            mService.startDppAsConfiguratorInitiator(binder, enrolleeUri, selectedNetworkId,
                    enrolleeNetworkRole, new EasyConnectCallbackProxy(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start Easy Connect (DPP) in Enrollee-Initiator role. The current device will initiate Easy
     * Connect bootstrapping with a peer, and receive the SSID and password from the peer
     * configurator.
     *
     * @param configuratorUri URI of the Configurator obtained separately (e.g. QR code scanning)
     * @param callback        Callback for status updates
     * @param executor        The Executor on which to run the callback.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public void startEasyConnectAsEnrolleeInitiator(@NonNull String configuratorUri,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull EasyConnectStatusCallback callback) {
        Binder binder = new Binder();
        try {
            mService.startDppAsEnrolleeInitiator(binder, configuratorUri,
                    new EasyConnectCallbackProxy(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop or abort a current Easy Connect (DPP) session. This call, once processed, will
     * terminate any ongoing transaction, and clean up all associated resources. Caller should not
     * expect any callbacks once this call is made. However, due to the asynchronous nature of
     * this call, a callback may be fired if it was already pending in the queue.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public void stopEasyConnectSession() {
        try {
            /* Request lower layers to stop/abort and clear resources */
            mService.stopDppSession();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Helper class to support Easy Connect (DPP) callbacks
     *
     * @hide
     */
    private static class EasyConnectCallbackProxy extends IDppCallback.Stub {
        private final Executor mExecutor;
        private final EasyConnectStatusCallback mEasyConnectStatusCallback;

        EasyConnectCallbackProxy(Executor executor,
                EasyConnectStatusCallback easyConnectStatusCallback) {
            mExecutor = executor;
            mEasyConnectStatusCallback = easyConnectStatusCallback;
        }

        @Override
        public void onSuccessConfigReceived(int newNetworkId) {
            Log.d(TAG, "Easy Connect onSuccessConfigReceived callback");
            mExecutor.execute(() -> {
                mEasyConnectStatusCallback.onEnrolleeSuccess(newNetworkId);
            });
        }

        @Override
        public void onSuccess(int status) {
            Log.d(TAG, "Easy Connect onSuccess callback");
            mExecutor.execute(() -> {
                mEasyConnectStatusCallback.onConfiguratorSuccess(status);
            });
        }

        @Override
        public void onFailure(int status) {
            Log.d(TAG, "Easy Connect onFailure callback");
            mExecutor.execute(() -> {
                mEasyConnectStatusCallback.onFailure(status);
            });
        }

        @Override
        public void onProgress(int status) {
            Log.d(TAG, "Easy Connect onProgress callback");
            mExecutor.execute(() -> {
                mEasyConnectStatusCallback.onProgress(status);
            });
        }
    }

    /**
     * Interface for Wi-Fi usability statistics listener. Should be implemented by applications and
     * set when calling {@link WifiManager#addOnWifiUsabilityStatsListener(Executor,
     * OnWifiUsabilityStatsListener)}.
     *
     * @hide
     */
    @SystemApi
    public interface OnWifiUsabilityStatsListener {
        /**
         * Called when Wi-Fi usability statistics is updated.
         *
         * @param seqNum The sequence number of statistics, used to derive the timing of updated
         *               Wi-Fi usability statistics, set by framework and incremented by one after
         *               each update.
         * @param isSameBssidAndFreq The flag to indicate whether the BSSID and the frequency of
         *                           network stays the same or not relative to the last update of
         *                           Wi-Fi usability stats.
         * @param stats The updated Wi-Fi usability statistics.
         */
        void onWifiUsabilityStats(int seqNum, boolean isSameBssidAndFreq,
                @NonNull WifiUsabilityStatsEntry stats);
    }

    /**
     * Adds a listener for Wi-Fi usability statistics. See {@link OnWifiUsabilityStatsListener}.
     * Multiple listeners can be added. Callers will be invoked periodically by framework to
     * inform clients about the current Wi-Fi usability statistics. Callers can remove a previously
     * added listener using {@link removeOnWifiUsabilityStatsListener}.
     *
     * @param executor The executor on which callback will be invoked.
     * @param listener Listener for Wifi usability statistics.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE)
    public void addOnWifiUsabilityStatsListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnWifiUsabilityStatsListener listener) {
        if (executor == null) throw new IllegalArgumentException("executor cannot be null");
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "addOnWifiUsabilityStatsListener: listener=" + listener);
        }
        try {
            mService.addOnWifiUsabilityStatsListener(new Binder(),
                    new IOnWifiUsabilityStatsListener.Stub() {
                        @Override
                        public void onWifiUsabilityStats(int seqNum, boolean isSameBssidAndFreq,
                                WifiUsabilityStatsEntry stats) {
                            if (mVerboseLoggingEnabled) {
                                Log.v(TAG, "OnWifiUsabilityStatsListener: "
                                        + "onWifiUsabilityStats: seqNum=" + seqNum);
                            }
                            Binder.withCleanCallingIdentity(() ->
                                    executor.execute(() -> listener.onWifiUsabilityStats(seqNum,
                                            isSameBssidAndFreq, stats)));
                        }
                    },
                    listener.hashCode()
            );
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow callers to remove a previously registered listener. After calling this method,
     * applications will no longer receive Wi-Fi usability statistics.
     *
     * @param listener Listener to remove the Wi-Fi usability statistics.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE)
    public void removeOnWifiUsabilityStatsListener(@NonNull OnWifiUsabilityStatsListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "removeOnWifiUsabilityStatsListener: listener=" + listener);
        }
        try {
            mService.removeOnWifiUsabilityStatsListener(listener.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Provide a Wi-Fi usability score information to be recorded (but not acted upon) by the
     * framework. The Wi-Fi usability score is derived from {@link OnWifiUsabilityStatsListener}
     * where a score is matched to Wi-Fi usability statistics using the sequence number. The score
     * is used to quantify whether Wi-Fi is usable in a future time.
     *
     * @param seqNum Sequence number of the Wi-Fi usability score.
     * @param score The Wi-Fi usability score, expected range: [0, 100].
     * @param predictionHorizonSec Prediction horizon of the Wi-Fi usability score in second,
     *                             expected range: [0, 30].
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE)
    public void updateWifiUsabilityScore(int seqNum, int score, int predictionHorizonSec) {
        try {
            mService.updateWifiUsabilityScore(seqNum, score, predictionHorizonSec);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
