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

package com.android.internal.net;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple container used to carry information in VpnBuilder, VpnDialogs,
 * and com.android.server.connectivity.Vpn. Internal use only.
 *
 * @hide
 */
public class VpnConfig implements Parcelable {

    public static final String SERVICE_INTERFACE = "android.net.VpnService";

    public static final String DIALOGS_PACKAGE = "com.android.vpndialogs";

    public static final String LEGACY_VPN = "[Legacy VPN]";

    public static Intent getIntentForConfirmation() {
        Intent intent = new Intent();
        ComponentName componentName = ComponentName.unflattenFromString(
                Resources.getSystem().getString(
                        com.android.internal.R.string.config_customVpnConfirmDialogComponent));
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        return intent;
    }

    public static CharSequence getVpnLabel(Context context, String packageName)
            throws NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(SERVICE_INTERFACE);
        intent.setPackage(packageName);
        List<ResolveInfo> services = pm.queryIntentServices(intent, 0 /* flags */);
        if (services != null && services.size() == 1) {
            // This app contains exactly one VPN service. Call loadLabel, which will attempt to
            // load the service's label, and fall back to the app label if none is present.
            return services.get(0).loadLabel(pm);
        } else {
            return pm.getApplicationInfo(packageName, 0).loadLabel(pm);
        }
    }

    public String user;
    public String interfaze;
    public String session;
    public int mtu = -1;
    public List<LinkAddress> addresses = new ArrayList<LinkAddress>();
    public List<RouteInfo> routes = new ArrayList<RouteInfo>();
    public List<String> dnsServers;
    public List<String> searchDomains;
    public PendingIntent configureIntent;
    public long startTime = -1;
    public boolean legacy;
    public boolean blocking;
    public boolean allowBypass;
    public boolean allowIPv4;
    public boolean allowIPv6;

    public void updateAllowedFamilies(InetAddress address) {
        if (address instanceof Inet4Address) {
            allowIPv4 = true;
        } else {
            allowIPv6 = true;
        }
    }

    public void addLegacyRoutes(String routesStr) {
        if (routesStr.trim().equals("")) {
            return;
        }
        String[] routes = routesStr.trim().split(" ");
        for (String route : routes) {
            //each route is ip/prefix
            String[] split = route.split("/");
            RouteInfo info = new RouteInfo(new LinkAddress
                    (InetAddress.parseNumericAddress(split[0]), Integer.parseInt(split[1])), null);
            this.routes.add(info);
            updateAllowedFamilies(info.getDestination().getAddress());
        }
    }

    public void addLegacyAddresses(String addressesStr) {
        if (addressesStr.trim().equals("")) {
            return;
        }
        String[] addresses = addressesStr.trim().split(" ");
        for (String address : addresses) {
            //each address is ip/prefix
            String[] split = address.split("/");
            LinkAddress addr = new LinkAddress(InetAddress.parseNumericAddress(split[0]),
                    Integer.parseInt(split[1]));
            this.addresses.add(addr);
            updateAllowedFamilies(addr.getAddress());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(user);
        out.writeString(interfaze);
        out.writeString(session);
        out.writeInt(mtu);
        out.writeTypedList(addresses);
        out.writeTypedList(routes);
        out.writeStringList(dnsServers);
        out.writeStringList(searchDomains);
        out.writeParcelable(configureIntent, flags);
        out.writeLong(startTime);
        out.writeInt(legacy ? 1 : 0);
        out.writeInt(blocking ? 1 : 0);
        out.writeInt(allowBypass ? 1 : 0);
        out.writeInt(allowIPv4 ? 1 : 0);
        out.writeInt(allowIPv6 ? 1 : 0);
    }

    public static final Parcelable.Creator<VpnConfig> CREATOR =
            new Parcelable.Creator<VpnConfig>() {
        @Override
        public VpnConfig createFromParcel(Parcel in) {
            VpnConfig config = new VpnConfig();
            config.user = in.readString();
            config.interfaze = in.readString();
            config.session = in.readString();
            config.mtu = in.readInt();
            in.readTypedList(config.addresses, LinkAddress.CREATOR);
            in.readTypedList(config.routes, RouteInfo.CREATOR);
            config.dnsServers = in.createStringArrayList();
            config.searchDomains = in.createStringArrayList();
            config.configureIntent = in.readParcelable(null);
            config.startTime = in.readLong();
            config.legacy = in.readInt() != 0;
            config.blocking = in.readInt() != 0;
            config.allowBypass = in.readInt() != 0;
            config.allowIPv4 = in.readInt() != 0;
            config.allowIPv6 = in.readInt() != 0;
            return config;
        }

        @Override
        public VpnConfig[] newArray(int size) {
            return new VpnConfig[size];
        }
    };
}
