/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.content.Context.TELEPHONY_SERVICE;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;

import android.content.Context;
import android.content.res.Resources;
import android.net.TetheringConfigurationParcel;
import android.net.util.SharedLog;
import android.provider.DeviceConfig;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;


/**
 * A utility class to encapsulate the various tethering configuration elements.
 *
 * This configuration data includes elements describing upstream properties
 * (preferred and required types of upstream connectivity as well as default
 * DNS servers to use if none are available) and downstream properties (such
 * as regular expressions use to match suitable downstream interfaces and the
 * DHCPv4 ranges to use).
 *
 * @hide
 */
public class TetheringConfiguration {
    private static final String TAG = TetheringConfiguration.class.getSimpleName();

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    // Default ranges used for the legacy DHCP server.
    // USB is  192.168.42.1 and 255.255.255.0
    // Wifi is 192.168.43.1 and 255.255.255.0
    // BT is limited to max default of 5 connections. 192.168.44.1 to 192.168.48.1
    // with 255.255.255.0
    // P2P is 192.168.49.1 and 255.255.255.0
    private static final String[] LEGACY_DHCP_DEFAULT_RANGE = {
        "192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254",
        "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254",
        "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254",
        "192.168.48.2", "192.168.48.254", "192.168.49.2", "192.168.49.254",
    };

    private static final String[] DEFAULT_IPV4_DNS = {"8.8.4.4", "8.8.8.8"};

    /**
     * Use the old dnsmasq DHCP server for tethering instead of the framework implementation.
     */
    public static final String TETHER_ENABLE_LEGACY_DHCP_SERVER =
            "tether_enable_legacy_dhcp_server";

    /**
     * Default value that used to periodic polls tether offload stats from tethering offload HAL
     * to make the data warnings work.
     */
    public static final int DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS = 5000;

    public final String[] tetherableUsbRegexs;
    public final String[] tetherableWifiRegexs;
    public final String[] tetherableWifiP2pRegexs;
    public final String[] tetherableBluetoothRegexs;
    public final String[] tetherableNcmRegexs;
    public final boolean isDunRequired;
    public final boolean chooseUpstreamAutomatically;
    public final Collection<Integer> preferredUpstreamIfaceTypes;
    public final String[] legacyDhcpRanges;
    public final String[] defaultIPv4DNS;
    public final boolean enableLegacyDhcpServer;

    public final String[] provisioningApp;
    public final String provisioningAppNoUi;
    public final int provisioningCheckPeriod;

    public final int activeDataSubId;

    private final int mOffloadPollInterval;

    public TetheringConfiguration(Context ctx, SharedLog log, int id) {
        final SharedLog configLog = log.forSubComponent("config");

        activeDataSubId = id;
        Resources res = getResources(ctx, activeDataSubId);

        tetherableUsbRegexs = getResourceStringArray(res, R.array.config_tether_usb_regexs);
        tetherableNcmRegexs = getResourceStringArray(res, R.array.config_tether_ncm_regexs);
        // TODO: Evaluate deleting this altogether now that Wi-Fi always passes
        // us an interface name. Careful consideration needs to be given to
        // implications for Settings and for provisioning checks.
        tetherableWifiRegexs = getResourceStringArray(res, R.array.config_tether_wifi_regexs);
        tetherableWifiP2pRegexs = getResourceStringArray(
                res, R.array.config_tether_wifi_p2p_regexs);
        tetherableBluetoothRegexs = getResourceStringArray(
                res, R.array.config_tether_bluetooth_regexs);

        isDunRequired = checkDunRequired(ctx);

        chooseUpstreamAutomatically = getResourceBoolean(
                res, R.bool.config_tether_upstream_automatic);
        preferredUpstreamIfaceTypes = getUpstreamIfaceTypes(res, isDunRequired);

        legacyDhcpRanges = getLegacyDhcpRanges(res);
        defaultIPv4DNS = copy(DEFAULT_IPV4_DNS);
        enableLegacyDhcpServer = getEnableLegacyDhcpServer(res);

        provisioningApp = getResourceStringArray(res, R.array.config_mobile_hotspot_provision_app);
        provisioningAppNoUi = getProvisioningAppNoUi(res);
        provisioningCheckPeriod = getResourceInteger(res,
                R.integer.config_mobile_hotspot_provision_check_period,
                0 /* No periodic re-check */);

        mOffloadPollInterval = getResourceInteger(res,
                R.integer.config_tether_offload_poll_interval,
                DEFAULT_TETHER_OFFLOAD_POLL_INTERVAL_MS);

        configLog.log(toString());
    }

    /** Check whether input interface belong to usb.*/
    public boolean isUsb(String iface) {
        return matchesDownstreamRegexs(iface, tetherableUsbRegexs);
    }

    /** Check whether input interface belong to wifi.*/
    public boolean isWifi(String iface) {
        return matchesDownstreamRegexs(iface, tetherableWifiRegexs);
    }

    /** Check whether this interface is Wifi P2P interface. */
    public boolean isWifiP2p(String iface) {
        return matchesDownstreamRegexs(iface, tetherableWifiP2pRegexs);
    }

    /** Check whether using legacy mode for wifi P2P. */
    public boolean isWifiP2pLegacyTetheringMode() {
        return (tetherableWifiP2pRegexs == null || tetherableWifiP2pRegexs.length == 0);
    }

    /** Check whether input interface belong to bluetooth.*/
    public boolean isBluetooth(String iface) {
        return matchesDownstreamRegexs(iface, tetherableBluetoothRegexs);
    }

    /** Check if interface is ncm */
    public boolean isNcm(String iface) {
        return matchesDownstreamRegexs(iface, tetherableNcmRegexs);
    }

    /** Check whether no ui entitlement application is available.*/
    public boolean hasMobileHotspotProvisionApp() {
        return !TextUtils.isEmpty(provisioningAppNoUi);
    }

    /** Does the dumping.*/
    public void dump(PrintWriter pw) {
        pw.print("activeDataSubId: ");
        pw.println(activeDataSubId);

        dumpStringArray(pw, "tetherableUsbRegexs", tetherableUsbRegexs);
        dumpStringArray(pw, "tetherableWifiRegexs", tetherableWifiRegexs);
        dumpStringArray(pw, "tetherableWifiP2pRegexs", tetherableWifiP2pRegexs);
        dumpStringArray(pw, "tetherableBluetoothRegexs", tetherableBluetoothRegexs);
        dumpStringArray(pw, "tetherableNcmRegexs", tetherableNcmRegexs);

        pw.print("isDunRequired: ");
        pw.println(isDunRequired);

        pw.print("chooseUpstreamAutomatically: ");
        pw.println(chooseUpstreamAutomatically);
        pw.print("legacyPreredUpstreamIfaceTypes: ");
        pw.println(Arrays.toString(toIntArray(preferredUpstreamIfaceTypes)));

        dumpStringArray(pw, "legacyDhcpRanges", legacyDhcpRanges);
        dumpStringArray(pw, "defaultIPv4DNS", defaultIPv4DNS);

        pw.print("offloadPollInterval: ");
        pw.println(mOffloadPollInterval);

        dumpStringArray(pw, "provisioningApp", provisioningApp);
        pw.print("provisioningAppNoUi: ");
        pw.println(provisioningAppNoUi);

        pw.print("enableLegacyDhcpServer: ");
        pw.println(enableLegacyDhcpServer);
    }

    /** Returns the string representation of this object.*/
    public String toString() {
        final StringJoiner sj = new StringJoiner(" ");
        sj.add(String.format("activeDataSubId:%d", activeDataSubId));
        sj.add(String.format("tetherableUsbRegexs:%s", makeString(tetherableUsbRegexs)));
        sj.add(String.format("tetherableWifiRegexs:%s", makeString(tetherableWifiRegexs)));
        sj.add(String.format("tetherableWifiP2pRegexs:%s", makeString(tetherableWifiP2pRegexs)));
        sj.add(String.format("tetherableBluetoothRegexs:%s",
                makeString(tetherableBluetoothRegexs)));
        sj.add(String.format("isDunRequired:%s", isDunRequired));
        sj.add(String.format("chooseUpstreamAutomatically:%s", chooseUpstreamAutomatically));
        sj.add(String.format("offloadPollInterval:%d", mOffloadPollInterval));
        sj.add(String.format("preferredUpstreamIfaceTypes:%s",
                toIntArray(preferredUpstreamIfaceTypes)));
        sj.add(String.format("provisioningApp:%s", makeString(provisioningApp)));
        sj.add(String.format("provisioningAppNoUi:%s", provisioningAppNoUi));
        sj.add(String.format("enableLegacyDhcpServer:%s", enableLegacyDhcpServer));
        return String.format("TetheringConfiguration{%s}", sj.toString());
    }

    private static void dumpStringArray(PrintWriter pw, String label, String[] values) {
        pw.print(label);
        pw.print(": ");

        if (values != null) {
            final StringJoiner sj = new StringJoiner(", ", "[", "]");
            for (String value : values) sj.add(value);
            pw.print(sj.toString());
        } else {
            pw.print("null");
        }

        pw.println();
    }

    private static String makeString(String[] strings) {
        if (strings == null) return "null";
        final StringJoiner sj = new StringJoiner(",", "[", "]");
        for (String s : strings) sj.add(s);
        return sj.toString();
    }

    /** Check whether dun is required. */
    public static boolean checkDunRequired(Context ctx) {
        final TelephonyManager tm = (TelephonyManager) ctx.getSystemService(TELEPHONY_SERVICE);
        // TelephonyManager would uses the active data subscription, which should be the one used
        // by tethering.
        return (tm != null) ? tm.isTetheringApnRequired() : false;
    }

    public int getOffloadPollInterval() {
        return mOffloadPollInterval;
    }

    private static Collection<Integer> getUpstreamIfaceTypes(Resources res, boolean dunRequired) {
        final int[] ifaceTypes = res.getIntArray(R.array.config_tether_upstream_types);
        final ArrayList<Integer> upstreamIfaceTypes = new ArrayList<>(ifaceTypes.length);
        for (int i : ifaceTypes) {
            switch (i) {
                case TYPE_MOBILE:
                case TYPE_MOBILE_HIPRI:
                    if (dunRequired) continue;
                    break;
                case TYPE_MOBILE_DUN:
                    if (!dunRequired) continue;
                    break;
            }
            upstreamIfaceTypes.add(i);
        }

        // Fix up upstream interface types for DUN or mobile. NOTE: independent
        // of the value of |dunRequired|, cell data of one form or another is
        // *always* an upstream, regardless of the upstream interface types
        // specified by configuration resources.
        if (dunRequired) {
            appendIfNotPresent(upstreamIfaceTypes, TYPE_MOBILE_DUN);
        } else {
            // Do not modify if a cellular interface type is already present in the
            // upstream interface types. Add TYPE_MOBILE and TYPE_MOBILE_HIPRI if no
            // cellular interface types are found in the upstream interface types.
            // This preserves backwards compatibility and prevents the DUN and default
            // mobile types incorrectly appearing together, which could happen on
            // previous releases in the common case where checkDunRequired returned
            // DUN_UNSPECIFIED.
            if (!containsOneOf(upstreamIfaceTypes, TYPE_MOBILE, TYPE_MOBILE_HIPRI)) {
                upstreamIfaceTypes.add(TYPE_MOBILE);
                upstreamIfaceTypes.add(TYPE_MOBILE_HIPRI);
            }
        }

        // Always make sure our good friend Ethernet is present.
        // TODO: consider unilaterally forcing this at the front.
        prependIfNotPresent(upstreamIfaceTypes, TYPE_ETHERNET);

        return upstreamIfaceTypes;
    }

    private static boolean matchesDownstreamRegexs(String iface, String[] regexs) {
        for (String regex : regexs) {
            if (iface.matches(regex)) return true;
        }
        return false;
    }

    private static String[] getLegacyDhcpRanges(Resources res) {
        final String[] fromResource = getResourceStringArray(res, R.array.config_tether_dhcp_range);
        if ((fromResource.length > 0) && (fromResource.length % 2 == 0)) {
            return fromResource;
        }
        return copy(LEGACY_DHCP_DEFAULT_RANGE);
    }

    private static String getProvisioningAppNoUi(Resources res) {
        try {
            return res.getString(R.string.config_mobile_hotspot_provision_app_no_ui);
        } catch (Resources.NotFoundException e) {
            return "";
        }
    }

    private static boolean getResourceBoolean(Resources res, int resId) {
        try {
            return res.getBoolean(resId);
        } catch (Resources.NotFoundException e404) {
            return false;
        }
    }

    private static String[] getResourceStringArray(Resources res, int resId) {
        try {
            final String[] strArray = res.getStringArray(resId);
            return (strArray != null) ? strArray : EMPTY_STRING_ARRAY;
        } catch (Resources.NotFoundException e404) {
            return EMPTY_STRING_ARRAY;
        }
    }

    private static int getResourceInteger(Resources res, int resId, int defaultValue) {
        try {
            return res.getInteger(resId);
        } catch (Resources.NotFoundException e404) {
            return defaultValue;
        }
    }

    private boolean getEnableLegacyDhcpServer(final Resources res) {
        return getResourceBoolean(res, R.bool.config_tether_enable_legacy_dhcp_server)
                || getDeviceConfigBoolean(TETHER_ENABLE_LEGACY_DHCP_SERVER);
    }

    @VisibleForTesting
    protected boolean getDeviceConfigBoolean(final String name) {
        return DeviceConfig.getBoolean(NAMESPACE_CONNECTIVITY, name, false /** defaultValue */);
    }

    private Resources getResources(Context ctx, int subId) {
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return getResourcesForSubIdWrapper(ctx, subId);
        } else {
            return ctx.getResources();
        }
    }

    @VisibleForTesting
    protected Resources getResourcesForSubIdWrapper(Context ctx, int subId) {
        return SubscriptionManager.getResourcesForSubId(ctx, subId);
    }

    private static String[] copy(String[] strarray) {
        return Arrays.copyOf(strarray, strarray.length);
    }

    private static void prependIfNotPresent(ArrayList<Integer> list, int value) {
        if (list.contains(value)) return;
        list.add(0, value);
    }

    private static void appendIfNotPresent(ArrayList<Integer> list, int value) {
        if (list.contains(value)) return;
        list.add(value);
    }

    private static boolean containsOneOf(ArrayList<Integer> list, Integer... values) {
        for (Integer value : values) {
            if (list.contains(value)) return true;
        }
        return false;
    }

    private static int[] toIntArray(Collection<Integer> values) {
        final int[] result = new int[values.size()];
        int index = 0;
        for (Integer value : values) {
            result[index++] = value;
        }
        return result;
    }

    /**
     * Convert this TetheringConfiguration to a TetheringConfigurationParcel.
     */
    public TetheringConfigurationParcel toStableParcelable() {
        final TetheringConfigurationParcel parcel = new TetheringConfigurationParcel();
        parcel.subId = activeDataSubId;
        parcel.tetherableUsbRegexs = tetherableUsbRegexs;
        parcel.tetherableWifiRegexs = tetherableWifiRegexs;
        parcel.tetherableBluetoothRegexs = tetherableBluetoothRegexs;
        parcel.isDunRequired = isDunRequired;
        parcel.chooseUpstreamAutomatically = chooseUpstreamAutomatically;

        parcel.preferredUpstreamIfaceTypes = toIntArray(preferredUpstreamIfaceTypes);

        parcel.legacyDhcpRanges = legacyDhcpRanges;
        parcel.defaultIPv4DNS = defaultIPv4DNS;
        parcel.enableLegacyDhcpServer = enableLegacyDhcpServer;
        parcel.provisioningApp = provisioningApp;
        parcel.provisioningAppNoUi = provisioningAppNoUi;
        parcel.provisioningCheckPeriod = provisioningCheckPeriod;
        return parcel;
    }
}
