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

package com.android.server.connectivity;

import static android.net.ConnectivityManager.PRIVATE_DNS_DEFAULT_MODE;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
import static android.provider.Settings.Global.DNS_RESOLVER_MIN_SAMPLES;
import static android.provider.Settings.Global.DNS_RESOLVER_MAX_SAMPLES;
import static android.provider.Settings.Global.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS;
import static android.provider.Settings.Global.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT;
import static android.provider.Settings.Global.PRIVATE_DNS_MODE;
import static android.provider.Settings.Global.PRIVATE_DNS_SPECIFIER;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.Uri;
import android.os.Binder;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.system.GaiException;
import android.system.OsConstants;
import android.system.StructAddrinfo;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.connectivity.MockableSystemProperties;

import libcore.io.Libcore;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.StringJoiner;


/**
 * Encapsulate the management of DNS settings for networks.
 *
 * This class it NOT designed for concurrent access. Furthermore, all non-static
 * methods MUST be called from ConnectivityService's thread.
 *
 * @hide
 */
public class DnsManager {
    private static final String TAG = DnsManager.class.getSimpleName();

    /* Defaults for resolver parameters. */
    private static final int DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    private static final int DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    private static final int DNS_RESOLVER_DEFAULT_MIN_SAMPLES = 8;
    private static final int DNS_RESOLVER_DEFAULT_MAX_SAMPLES = 64;

    public static class PrivateDnsConfig {
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

        public boolean inStrictMode() {
            return useTls && !TextUtils.isEmpty(hostname);
        }

        public String toString() {
            return PrivateDnsConfig.class.getSimpleName() +
                    "{" + useTls + ":" + hostname + "/" + Arrays.toString(ips) + "}";
        }
    }

    public static PrivateDnsConfig getPrivateDnsConfig(ContentResolver cr) {
        final String mode = getPrivateDnsMode(cr);

        final boolean useTls = !TextUtils.isEmpty(mode) && !PRIVATE_DNS_MODE_OFF.equals(mode);

        if (PRIVATE_DNS_MODE_PROVIDER_HOSTNAME.equals(mode)) {
            final String specifier = getStringSetting(cr, PRIVATE_DNS_SPECIFIER);
            return new PrivateDnsConfig(specifier, null);
        }

        return new PrivateDnsConfig(useTls);
    }

    public static PrivateDnsConfig tryBlockingResolveOf(Network network, String name) {
        final StructAddrinfo hints = new StructAddrinfo();
        // Unnecessary, but expressly no AI_ADDRCONFIG.
        hints.ai_flags = 0;
        // Fetch all IP addresses at once to minimize re-resolution.
        hints.ai_family = OsConstants.AF_UNSPEC;
        hints.ai_socktype = OsConstants.SOCK_DGRAM;

        try {
            final InetAddress[] ips = Libcore.os.android_getaddrinfo(name, hints, network.netId);
            if (ips != null && ips.length > 0) {
                return new PrivateDnsConfig(name, ips);
            }
        } catch (GaiException ignored) {}

        return null;
    }

    public static Uri[] getPrivateDnsSettingsUris() {
        final Uri[] uris = new Uri[2];
        uris[0] = Settings.Global.getUriFor(PRIVATE_DNS_MODE);
        uris[1] = Settings.Global.getUriFor(PRIVATE_DNS_SPECIFIER);
        return uris;
    }

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final INetworkManagementService mNMS;
    private final MockableSystemProperties mSystemProperties;
    private final Map<Integer, PrivateDnsConfig> mPrivateDnsMap;

    private int mNumDnsEntries;
    private int mSampleValidity;
    private int mSuccessThreshold;
    private int mMinSamples;
    private int mMaxSamples;
    private String mPrivateDnsMode;
    private String mPrivateDnsSpecifier;

    public DnsManager(Context ctx, INetworkManagementService nms, MockableSystemProperties sp) {
        mContext = ctx;
        mContentResolver = mContext.getContentResolver();
        mNMS = nms;
        mSystemProperties = sp;
        mPrivateDnsMap = new HashMap<>();

        // TODO: Create and register ContentObservers to track every setting
        // used herein, posting messages to respond to changes.
    }

    public PrivateDnsConfig getPrivateDnsConfig() {
        return getPrivateDnsConfig(mContentResolver);
    }

    public void removeNetwork(Network network) {
        mPrivateDnsMap.remove(network.netId);
    }

    public PrivateDnsConfig updatePrivateDns(Network network, PrivateDnsConfig cfg) {
        Slog.w(TAG, "updatePrivateDns(" + network + ", " + cfg + ")");
        return (cfg != null)
                ? mPrivateDnsMap.put(network.netId, cfg)
                : mPrivateDnsMap.remove(network.netId);
    }

    public void setDnsConfigurationForNetwork(
            int netId, LinkProperties lp, boolean isDefaultNetwork) {
        // We only use the PrivateDnsConfig data pushed to this class instance
        // from ConnectivityService because it works in coordination with
        // NetworkMonitor to decide which networks need validation and runs the
        // blocking calls to resolve Private DNS strict mode hostnames.
        //
        // At this time we do attempt to enable Private DNS on non-Internet
        // networks like IMS.
        final PrivateDnsConfig privateDnsCfg = mPrivateDnsMap.get(netId);

        final boolean useTls = (privateDnsCfg != null) && privateDnsCfg.useTls;
        final boolean strictMode = (privateDnsCfg != null) && privateDnsCfg.inStrictMode();
        final String tlsHostname = strictMode ? privateDnsCfg.hostname : "";

        final String[] serverStrs = NetworkUtils.makeStrings(
                strictMode ? Arrays.stream(privateDnsCfg.ips)
                                   .filter((ip) -> lp.isReachable(ip))
                                   .collect(Collectors.toList())
                           : lp.getDnsServers());
        final String[] domainStrs = getDomainStrings(lp.getDomains());

        updateParametersSettings();
        final int[] params = { mSampleValidity, mSuccessThreshold, mMinSamples, mMaxSamples };

        Slog.d(TAG, String.format("setDnsConfigurationForNetwork(%d, %s, %s, %s, %s, %s)",
                netId, Arrays.toString(serverStrs), Arrays.toString(domainStrs),
                Arrays.toString(params), useTls, tlsHostname));
        try {
            mNMS.setDnsConfigurationForNetwork(
                    netId, serverStrs, domainStrs, params, useTls, tlsHostname);
        } catch (Exception e) {
            Slog.e(TAG, "Error setting DNS configuration: " + e);
            return;
        }

        // TODO: netd should listen on [::1]:53 and proxy queries to the current
        // default network, and we should just set net.dns1 to ::1, not least
        // because applications attempting to use net.dns resolvers will bypass
        // the privacy protections of things like DNS-over-TLS.
        if (isDefaultNetwork) setDefaultDnsSystemProperties(lp.getDnsServers());
        flushVmDnsCache();
    }

    public void setDefaultDnsSystemProperties(Collection<InetAddress> dnses) {
        int last = 0;
        for (InetAddress dns : dnses) {
            ++last;
            setNetDnsProperty(last, dns.getHostAddress());
        }
        for (int i = last + 1; i <= mNumDnsEntries; ++i) {
            setNetDnsProperty(i, "");
        }
        mNumDnsEntries = last;
    }

    private void flushVmDnsCache() {
        /*
         * Tell the VMs to toss their DNS caches
         */
        final Intent intent = new Intent(Intent.ACTION_CLEAR_DNS_CACHE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        /*
         * Connectivity events can happen before boot has completed ...
         */
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void updateParametersSettings() {
        mSampleValidity = getIntSetting(
                DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS,
                DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS);
        if (mSampleValidity < 0 || mSampleValidity > 65535) {
            Slog.w(TAG, "Invalid sampleValidity=" + mSampleValidity + ", using default=" +
                    DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS);
            mSampleValidity = DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS;
        }

        mSuccessThreshold = getIntSetting(
                DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT,
                DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT);
        if (mSuccessThreshold < 0 || mSuccessThreshold > 100) {
            Slog.w(TAG, "Invalid successThreshold=" + mSuccessThreshold + ", using default=" +
                    DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT);
            mSuccessThreshold = DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT;
        }

        mMinSamples = getIntSetting(DNS_RESOLVER_MIN_SAMPLES, DNS_RESOLVER_DEFAULT_MIN_SAMPLES);
        mMaxSamples = getIntSetting(DNS_RESOLVER_MAX_SAMPLES, DNS_RESOLVER_DEFAULT_MAX_SAMPLES);
        if (mMinSamples < 0 || mMinSamples > mMaxSamples || mMaxSamples > 64) {
            Slog.w(TAG, "Invalid sample count (min, max)=(" + mMinSamples + ", " + mMaxSamples +
                    "), using default=(" + DNS_RESOLVER_DEFAULT_MIN_SAMPLES + ", " +
                    DNS_RESOLVER_DEFAULT_MAX_SAMPLES + ")");
            mMinSamples = DNS_RESOLVER_DEFAULT_MIN_SAMPLES;
            mMaxSamples = DNS_RESOLVER_DEFAULT_MAX_SAMPLES;
        }
    }

    private int getIntSetting(String which, int dflt) {
        return Settings.Global.getInt(mContentResolver, which, dflt);
    }

    private void setNetDnsProperty(int which, String value) {
        final String key = "net.dns" + which;
        // Log and forget errors setting unsupported properties.
        try {
            mSystemProperties.set(key, value);
        } catch (Exception e) {
            Slog.e(TAG, "Error setting unsupported net.dns property: ", e);
        }
    }

    private static String getPrivateDnsMode(ContentResolver cr) {
        final String mode = getStringSetting(cr, PRIVATE_DNS_MODE);
        return !TextUtils.isEmpty(mode) ? mode : PRIVATE_DNS_DEFAULT_MODE;
    }

    private static String getStringSetting(ContentResolver cr, String which) {
        return Settings.Global.getString(cr, which);
    }

    private static String[] getDomainStrings(String domains) {
        return (TextUtils.isEmpty(domains)) ? new String[0] : domains.split(" ");
    }
}
