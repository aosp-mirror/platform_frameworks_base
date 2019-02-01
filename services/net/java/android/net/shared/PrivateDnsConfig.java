/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.shared;

import static android.net.shared.ParcelableUtil.fromParcelableArray;
import static android.net.shared.ParcelableUtil.toParcelableArray;

import android.net.PrivateDnsConfigParcel;
import android.text.TextUtils;

import java.net.InetAddress;
import java.util.Arrays;

/** @hide */
public class PrivateDnsConfig {
    public final boolean useTls;
    public final String hostname;
    public final InetAddress[] ips;

    public PrivateDnsConfig() {
        this(false);
    }

    public PrivateDnsConfig(boolean useTls) {
        this.useTls = useTls;
        this.hostname = "";
        this.ips = new InetAddress[0];
    }

    public PrivateDnsConfig(String hostname, InetAddress[] ips) {
        this.useTls = !TextUtils.isEmpty(hostname);
        this.hostname = useTls ? hostname : "";
        this.ips = (ips != null) ? ips : new InetAddress[0];
    }

    public PrivateDnsConfig(PrivateDnsConfig cfg) {
        useTls = cfg.useTls;
        hostname = cfg.hostname;
        ips = cfg.ips;
    }

    /**
     * Indicates whether this is a strict mode private DNS configuration.
     */
    public boolean inStrictMode() {
        return useTls && !TextUtils.isEmpty(hostname);
    }

    @Override
    public String toString() {
        return PrivateDnsConfig.class.getSimpleName()
                + "{" + useTls + ":" + hostname + "/" + Arrays.toString(ips) + "}";
    }

    /**
     * Create a stable AIDL-compatible parcel from the current instance.
     */
    public PrivateDnsConfigParcel toParcel() {
        final PrivateDnsConfigParcel parcel = new PrivateDnsConfigParcel();
        parcel.hostname = hostname;
        parcel.ips = toParcelableArray(
                Arrays.asList(ips), IpConfigurationParcelableUtil::parcelAddress, String.class);

        return parcel;
    }

    /**
     * Build a configuration from a stable AIDL-compatible parcel.
     */
    public static PrivateDnsConfig fromParcel(PrivateDnsConfigParcel parcel) {
        InetAddress[] ips = new InetAddress[parcel.ips.length];
        ips = fromParcelableArray(parcel.ips, IpConfigurationParcelableUtil::unparcelAddress)
                .toArray(ips);
        return new PrivateDnsConfig(parcel.hostname, ips);
    }
}
