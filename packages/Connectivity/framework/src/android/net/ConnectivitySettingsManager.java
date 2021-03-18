/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A manager class for connectivity module settings.
 *
 * @hide
 */
public class ConnectivitySettingsManager {

    private ConnectivitySettingsManager() {}

    /** Data activity timeout settings */

    /**
     * Inactivity timeout to track mobile data activity.
     *
     * If set to a positive integer, it indicates the inactivity timeout value in seconds to
     * infer the data activity of mobile network. After a period of no activity on mobile
     * networks with length specified by the timeout, an {@code ACTION_DATA_ACTIVITY_CHANGE}
     * intent is fired to indicate a transition of network status from "active" to "idle". Any
     * subsequent activity on mobile networks triggers the firing of {@code
     * ACTION_DATA_ACTIVITY_CHANGE} intent indicating transition from "idle" to "active".
     *
     * Network activity refers to transmitting or receiving data on the network interfaces.
     *
     * Tracking is disabled if set to zero or negative value.
     */
    public static final String DATA_ACTIVITY_TIMEOUT_MOBILE = "data_activity_timeout_mobile";

    /**
     * Timeout to tracking Wifi data activity. Same as {@code DATA_ACTIVITY_TIMEOUT_MOBILE}
     * but for Wifi network.
     */
    public static final String DATA_ACTIVITY_TIMEOUT_WIFI = "data_activity_timeout_wifi";

    /** Dns resolver settings */

    /**
     * Sample validity in seconds to configure for the system DNS resolver.
     */
    public static final String DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS =
            "dns_resolver_sample_validity_seconds";

    /**
     * Success threshold in percent for use with the system DNS resolver.
     */
    public static final String DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT =
            "dns_resolver_success_threshold_percent";

    /**
     * Minimum number of samples needed for statistics to be considered meaningful in the
     * system DNS resolver.
     */
    public static final String DNS_RESOLVER_MIN_SAMPLES = "dns_resolver_min_samples";

    /**
     * Maximum number taken into account for statistics purposes in the system DNS resolver.
     */
    public static final String DNS_RESOLVER_MAX_SAMPLES = "dns_resolver_max_samples";

    /** Network switch notification settings */

    /**
     * The maximum number of notifications shown in 24 hours when switching networks.
     */
    public static final String NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT =
            "network_switch_notification_daily_limit";

    /**
     * The minimum time in milliseconds between notifications when switching networks.
     */
    public static final String NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS =
            "network_switch_notification_rate_limit_millis";

    /** Captive portal settings */

    /**
     * The URL used for HTTP captive portal detection upon a new connection.
     * A 204 response code from the server is used for validation.
     */
    public static final String CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url";

    /**
     * What to do when connecting a network that presents a captive portal.
     * Must be one of the CAPTIVE_PORTAL_MODE_* constants above.
     *
     * The default for this setting is CAPTIVE_PORTAL_MODE_PROMPT.
     */
    public static final String CAPTIVE_PORTAL_MODE = "captive_portal_mode";

    /**
     * Don't attempt to detect captive portals.
     */
    public static final int CAPTIVE_PORTAL_MODE_IGNORE = 0;

    /**
     * When detecting a captive portal, display a notification that
     * prompts the user to sign in.
     */
    public static final int CAPTIVE_PORTAL_MODE_PROMPT = 1;

    /**
     * When detecting a captive portal, immediately disconnect from the
     * network and do not reconnect to that network in the future.
     */
    public static final int CAPTIVE_PORTAL_MODE_AVOID = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            CAPTIVE_PORTAL_MODE_IGNORE,
            CAPTIVE_PORTAL_MODE_PROMPT,
            CAPTIVE_PORTAL_MODE_AVOID,
    })
    public @interface CaptivePortalMode {}

    /** Global http proxy settings */

    /**
     * Host name for global http proxy. Set via ConnectivityManager.
     */
    public static final String GLOBAL_HTTP_PROXY_HOST = "global_http_proxy_host";

    /**
     * Integer host port for global http proxy. Set via ConnectivityManager.
     */
    public static final String GLOBAL_HTTP_PROXY_PORT = "global_http_proxy_port";

    /**
     * Exclusion list for global proxy. This string contains a list of
     * comma-separated domains where the global proxy does not apply.
     * Domains should be listed in a comma- separated list. Example of
     * acceptable formats: ".domain1.com,my.domain2.com" Use
     * ConnectivityManager to set/get.
     */
    public static final String GLOBAL_HTTP_PROXY_EXCLUSION_LIST =
            "global_http_proxy_exclusion_list";

    /**
     * The location PAC File for the proxy.
     */
    public static final String GLOBAL_HTTP_PROXY_PAC = "global_proxy_pac_url";

    /** Private dns settings */

    /**
     * The requested Private DNS mode (string), and an accompanying specifier (string).
     *
     * Currently, the specifier holds the chosen provider name when the mode requests
     * a specific provider. It may be used to store the provider name even when the
     * mode changes so that temporarily disabling and re-enabling the specific
     * provider mode does not necessitate retyping the provider hostname.
     */
    public static final String PRIVATE_DNS_MODE = "private_dns_mode";

    /**
     * The specific Private DNS provider name.
     */
    public static final String PRIVATE_DNS_SPECIFIER = "private_dns_specifier";

    /**
     * Forced override of the default mode (hardcoded as "automatic", nee "opportunistic").
     * This allows changing the default mode without effectively disabling other modes,
     * all of which require explicit user action to enable/configure. See also b/79719289.
     *
     * Value is a string, suitable for assignment to PRIVATE_DNS_MODE above.
     */
    public static final String PRIVATE_DNS_DEFAULT_MODE = "private_dns_default_mode";

    /** Other settings */

    /**
     * The number of milliseconds to hold on to a PendingIntent based request. This delay gives
     * the receivers of the PendingIntent an opportunity to make a new network request before
     * the Network satisfying the request is potentially removed.
     */
    public static final String CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS =
            "connectivity_release_pending_intent_delay_ms";

    /**
     * Whether the mobile data connection should remain active even when higher
     * priority networks like WiFi are active, to help make network switching faster.
     *
     * See ConnectivityService for more info.
     *
     * (0 = disabled, 1 = enabled)
     */
    public static final String MOBILE_DATA_ALWAYS_ON = "mobile_data_always_on";

    /**
     * Whether the wifi data connection should remain active even when higher
     * priority networks like Ethernet are active, to keep both networks.
     * In the case where higher priority networks are connected, wifi will be
     * unused unless an application explicitly requests to use it.
     *
     * See ConnectivityService for more info.
     *
     * (0 = disabled, 1 = enabled)
     */
    public static final String WIFI_ALWAYS_REQUESTED = "wifi_always_requested";

    /**
     * Whether to automatically switch away from wifi networks that lose Internet access.
     * Only meaningful if config_networkAvoidBadWifi is set to 0, otherwise the system always
     * avoids such networks. Valid values are:
     *
     * 0: Don't avoid bad wifi, don't prompt the user. Get stuck on bad wifi like it's 2013.
     * null: Ask the user whether to switch away from bad wifi.
     * 1: Avoid bad wifi.
     */
    public static final String NETWORK_AVOID_BAD_WIFI = "network_avoid_bad_wifi";

    /**
     * User setting for ConnectivityManager.getMeteredMultipathPreference(). This value may be
     * overridden by the system based on device or application state. If null, the value
     * specified by config_networkMeteredMultipathPreference is used.
     */
    public static final String NETWORK_METERED_MULTIPATH_PREFERENCE =
            "network_metered_multipath_preference";
}
