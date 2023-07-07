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

package com.android.printservice.recommendation.plugin.google;

import static com.android.printservice.recommendation.util.MDNSUtils.ATTRIBUTE_TY;

import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.android.printservice.recommendation.PrintServicePlugin;
import com.android.printservice.recommendation.R;
import com.android.printservice.recommendation.util.MDNSFilteredDiscovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plugin detecting <a href="https://developers.google.com/cloud-print/docs/privet">Google Cloud
 * Print</a> printers.
 */
public class CloudPrintPlugin implements PrintServicePlugin {
    private static final String LOG_TAG = CloudPrintPlugin.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String ATTRIBUTE_TXTVERS = "txtvers";
    private static final String ATTRIBUTE_URL = "url";
    private static final String ATTRIBUTE_TYPE = "type";
    private static final String ATTRIBUTE_ID = "id";
    private static final String ATTRIBUTE_CS = "cs";

    private static final String TYPE = "printer";

    private static final String PRIVET_SERVICE = "_privet._tcp";

    /** The required mDNS service types */
    private static final Set<String> PRINTER_SERVICE_TYPE = Set.of(
            PRIVET_SERVICE); // Not checking _printer_._sub

    /** All possible connection states */
    private static final Set<String> POSSIBLE_CONNECTION_STATES = Set.of(
            "online",
            "offline",
            "connecting",
            "not-configured");

    private static final byte SUPPORTED_TXTVERS = '1';

    /** The mDNS filtered discovery */
    private final MDNSFilteredDiscovery mMDNSFilteredDiscovery;

    /**
     * Create a plugin detecting Google Cloud Print printers.
     *
     * @param context The context the plugin runs in
     */
    public CloudPrintPlugin(@NonNull Context context) {
        mMDNSFilteredDiscovery = new MDNSFilteredDiscovery(context, PRINTER_SERVICE_TYPE,
                nsdServiceInfo -> {
                    // The attributes are case insensitive. For faster searching create a clone of
                    // the map with the attribute-keys all in lower case.
                    ArrayMap<String, byte[]> caseInsensitiveAttributes =
                            new ArrayMap<>(nsdServiceInfo.getAttributes().size());
                    for (Map.Entry<String, byte[]> entry : nsdServiceInfo.getAttributes()
                            .entrySet()) {
                        caseInsensitiveAttributes.put(entry.getKey().toLowerCase(),
                                entry.getValue());
                    }

                    if (DEBUG) {
                        Log.i(LOG_TAG, nsdServiceInfo.getServiceName() + ":");
                        Log.i(LOG_TAG, "type:  " + nsdServiceInfo.getServiceType());
                        Log.i(LOG_TAG, "host:  " + nsdServiceInfo.getHost());
                        for (Map.Entry<String, byte[]> entry : caseInsensitiveAttributes.entrySet()) {
                            if (entry.getValue() == null) {
                                Log.i(LOG_TAG, entry.getKey() + "= null");
                            } else {
                                Log.i(LOG_TAG, entry.getKey() + "=" + new String(entry.getValue(),
                                        StandardCharsets.UTF_8));
                            }
                        }
                    }

                    byte[] txtvers = caseInsensitiveAttributes.get(ATTRIBUTE_TXTVERS);
                    if (txtvers == null || txtvers.length != 1 || txtvers[0] != SUPPORTED_TXTVERS) {
                        // The spec requires this to be the first attribute, but at this time we
                        // lost the order of the attributes
                        return false;
                    }

                    if (caseInsensitiveAttributes.get(ATTRIBUTE_TY) == null) {
                        return false;
                    }

                    byte[] url = caseInsensitiveAttributes.get(ATTRIBUTE_URL);
                    if (url == null || url.length == 0) {
                        return false;
                    }

                    byte[] type = caseInsensitiveAttributes.get(ATTRIBUTE_TYPE);
                    if (type == null || !TYPE.equals(
                            new String(type, StandardCharsets.UTF_8).toLowerCase())) {
                        return false;
                    }

                    if (caseInsensitiveAttributes.get(ATTRIBUTE_ID) == null) {
                        return false;
                    }

                    byte[] cs = caseInsensitiveAttributes.get(ATTRIBUTE_CS);
                    if (cs == null || !POSSIBLE_CONNECTION_STATES.contains(
                            new String(cs, StandardCharsets.UTF_8).toLowerCase())) {
                        return false;
                    }

                    InetAddress address = nsdServiceInfo.getHost();
                    if (!(address instanceof Inet4Address)) {
                        // Not checking for link local address
                        return false;
                    }

                    return true;
                });
    }

    @Override
    @NonNull public CharSequence getPackageName() {
        return "com.google.android.apps.cloudprint";
    }

    @Override
    public void start(@NonNull PrinterDiscoveryCallback callback) throws Exception {
        mMDNSFilteredDiscovery.start(callback);
    }

    @Override
    @StringRes public int getName() {
        return R.string.plugin_vendor_google_cloud_print;
    }

    @Override
    public void stop() throws Exception {
        mMDNSFilteredDiscovery.stop();
    }
}
