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

import static android.net.ConnectivityManager.PRIVATE_DNS_DEFAULT_MODE_FALLBACK;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
import static android.provider.Settings.Global.DNS_RESOLVER_MAX_SAMPLES;
import static android.provider.Settings.Global.DNS_RESOLVER_MIN_SAMPLES;
import static android.provider.Settings.Global.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS;
import static android.provider.Settings.Global.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT;
import static android.provider.Settings.Global.PRIVATE_DNS_DEFAULT_MODE;
import static android.provider.Settings.Global.PRIVATE_DNS_MODE;
import static android.provider.Settings.Global.PRIVATE_DNS_SPECIFIER;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.IDnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.ResolverOptionsParcel;
import android.net.ResolverParamsParcel;
import android.net.Uri;
import android.net.shared.PrivateDnsConfig;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * Encapsulate the management of DNS settings for networks.
 *
 * This class it NOT designed for concurrent access. Furthermore, all non-static
 * methods MUST be called from ConnectivityService's thread. However, an exceptional
 * case is getPrivateDnsConfig(Network) which is exclusively for
 * ConnectivityService#dumpNetworkDiagnostics() on a random binder thread.
 *
 * [ Private DNS ]
 * The code handling Private DNS is spread across several components, but this
 * seems like the least bad place to collect all the observations.
 *
 * Private DNS handling and updating occurs in response to several different
 * events. Each is described here with its corresponding intended handling.
 *
 * [A] Event: A new network comes up.
 * Mechanics:
 *     [1] ConnectivityService gets notifications from NetworkAgents.
 *     [2] in updateNetworkInfo(), the first time the NetworkAgent goes into
 *         into CONNECTED state, the Private DNS configuration is retrieved,
 *         programmed, and strict mode hostname resolution (if applicable) is
 *         enqueued in NetworkAgent's NetworkMonitor, via a call to
 *         handlePerNetworkPrivateDnsConfig().
 *     [3] Re-resolution of strict mode hostnames that fail to return any
 *         IP addresses happens inside NetworkMonitor; it sends itself a
 *         delayed CMD_EVALUATE_PRIVATE_DNS message in a simple backoff
 *         schedule.
 *     [4] Successfully resolved hostnames are sent to ConnectivityService
 *         inside an EVENT_PRIVATE_DNS_CONFIG_RESOLVED message. The resolved
 *         IP addresses are programmed into netd via:
 *
 *             updatePrivateDns() -> updateDnses()
 *
 *         both of which make calls into DnsManager.
 *     [5] Upon a successful hostname resolution NetworkMonitor initiates a
 *         validation attempt in the form of a lookup for a one-time hostname
 *         that uses Private DNS.
 *
 * [B] Event: Private DNS settings are changed.
 * Mechanics:
 *     [1] ConnectivityService gets notifications from its SettingsObserver.
 *     [2] handlePrivateDnsSettingsChanged() is called, which calls
 *         handlePerNetworkPrivateDnsConfig() and the process proceeds
 *         as if from A.3 above.
 *
 * [C] Event: An application calls ConnectivityManager#reportBadNetwork().
 * Mechanics:
 *     [1] NetworkMonitor is notified and initiates a reevaluation, which
 *         always bypasses Private DNS.
 *     [2] Once completed, NetworkMonitor checks if strict mode is in operation
 *         and if so enqueues another evaluation of Private DNS, as if from
 *         step A.5 above.
 *
 * @hide
 */
public class DnsManager {
    private static final String TAG = DnsManager.class.getSimpleName();
    private static final PrivateDnsConfig PRIVATE_DNS_OFF = new PrivateDnsConfig();

    /* Defaults for resolver parameters. */
    private static final int DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    private static final int DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    private static final int DNS_RESOLVER_DEFAULT_MIN_SAMPLES = 8;
    private static final int DNS_RESOLVER_DEFAULT_MAX_SAMPLES = 64;

    public static PrivateDnsConfig getPrivateDnsConfig(ContentResolver cr) {
        final String mode = getPrivateDnsMode(cr);

        final boolean useTls = !TextUtils.isEmpty(mode) && !PRIVATE_DNS_MODE_OFF.equals(mode);

        if (PRIVATE_DNS_MODE_PROVIDER_HOSTNAME.equals(mode)) {
            final String specifier = getStringSetting(cr, PRIVATE_DNS_SPECIFIER);
            return new PrivateDnsConfig(specifier, null);
        }

        return new PrivateDnsConfig(useTls);
    }

    public static Uri[] getPrivateDnsSettingsUris() {
        return new Uri[]{
            Settings.Global.getUriFor(PRIVATE_DNS_DEFAULT_MODE),
            Settings.Global.getUriFor(PRIVATE_DNS_MODE),
            Settings.Global.getUriFor(PRIVATE_DNS_SPECIFIER),
        };
    }

    public static class PrivateDnsValidationUpdate {
        final public int netId;
        final public InetAddress ipAddress;
        final public String hostname;
        final public boolean validated;

        public PrivateDnsValidationUpdate(int netId, InetAddress ipAddress,
                String hostname, boolean validated) {
            this.netId = netId;
            this.ipAddress = ipAddress;
            this.hostname = hostname;
            this.validated = validated;
        }
    }

    private static class PrivateDnsValidationStatuses {
        enum ValidationStatus {
            IN_PROGRESS,
            FAILED,
            SUCCEEDED
        }

        // Validation statuses of <hostname, ipAddress> pairs for a single netId
        // Caution : not thread-safe. As mentioned in the top file comment, all
        // methods of this class must only be called on ConnectivityService's thread.
        private Map<Pair<String, InetAddress>, ValidationStatus> mValidationMap;

        private PrivateDnsValidationStatuses() {
            mValidationMap = new HashMap<>();
        }

        private boolean hasValidatedServer() {
            for (ValidationStatus status : mValidationMap.values()) {
                if (status == ValidationStatus.SUCCEEDED) {
                    return true;
                }
            }
            return false;
        }

        private void updateTrackedDnses(String[] ipAddresses, String hostname) {
            Set<Pair<String, InetAddress>> latestDnses = new HashSet<>();
            for (String ipAddress : ipAddresses) {
                try {
                    latestDnses.add(new Pair(hostname,
                            InetAddress.parseNumericAddress(ipAddress)));
                } catch (IllegalArgumentException e) {}
            }
            // Remove <hostname, ipAddress> pairs that should not be tracked.
            for (Iterator<Map.Entry<Pair<String, InetAddress>, ValidationStatus>> it =
                    mValidationMap.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Pair<String, InetAddress>, ValidationStatus> entry = it.next();
                if (!latestDnses.contains(entry.getKey())) {
                    it.remove();
                }
            }
            // Add new <hostname, ipAddress> pairs that should be tracked.
            for (Pair<String, InetAddress> p : latestDnses) {
                if (!mValidationMap.containsKey(p)) {
                    mValidationMap.put(p, ValidationStatus.IN_PROGRESS);
                }
            }
        }

        private void updateStatus(PrivateDnsValidationUpdate update) {
            Pair<String, InetAddress> p = new Pair(update.hostname,
                    update.ipAddress);
            if (!mValidationMap.containsKey(p)) {
                return;
            }
            if (update.validated) {
                mValidationMap.put(p, ValidationStatus.SUCCEEDED);
            } else {
                mValidationMap.put(p, ValidationStatus.FAILED);
            }
        }

        private LinkProperties fillInValidatedPrivateDns(LinkProperties lp) {
            lp.setValidatedPrivateDnsServers(Collections.EMPTY_LIST);
            mValidationMap.forEach((key, value) -> {
                    if (value == ValidationStatus.SUCCEEDED) {
                        lp.addValidatedPrivateDnsServer(key.second);
                    }
                });
            return lp;
        }
    }

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final IDnsResolver mDnsResolver;
    private final MockableSystemProperties mSystemProperties;
    private final ConcurrentHashMap<Integer, PrivateDnsConfig> mPrivateDnsMap;
    // TODO: Replace the Map with SparseArrays.
    private final Map<Integer, PrivateDnsValidationStatuses> mPrivateDnsValidationMap;
    private final Map<Integer, LinkProperties> mLinkPropertiesMap;
    private final Map<Integer, int[]> mTransportsMap;

    private int mNumDnsEntries;
    private int mSampleValidity;
    private int mSuccessThreshold;
    private int mMinSamples;
    private int mMaxSamples;

    public DnsManager(Context ctx, IDnsResolver dnsResolver, MockableSystemProperties sp) {
        mContext = ctx;
        mContentResolver = mContext.getContentResolver();
        mDnsResolver = dnsResolver;
        mSystemProperties = sp;
        mPrivateDnsMap = new ConcurrentHashMap<>();
        mPrivateDnsValidationMap = new HashMap<>();
        mLinkPropertiesMap = new HashMap<>();
        mTransportsMap = new HashMap<>();

        // TODO: Create and register ContentObservers to track every setting
        // used herein, posting messages to respond to changes.
    }

    public PrivateDnsConfig getPrivateDnsConfig() {
        return getPrivateDnsConfig(mContentResolver);
    }

    public void removeNetwork(Network network) {
        mPrivateDnsMap.remove(network.netId);
        mPrivateDnsValidationMap.remove(network.netId);
        mTransportsMap.remove(network.netId);
        mLinkPropertiesMap.remove(network.netId);
    }

    // This is exclusively called by ConnectivityService#dumpNetworkDiagnostics() which
    // is not on the ConnectivityService handler thread.
    public PrivateDnsConfig getPrivateDnsConfig(@NonNull Network network) {
        return mPrivateDnsMap.getOrDefault(network.netId, PRIVATE_DNS_OFF);
    }

    public PrivateDnsConfig updatePrivateDns(Network network, PrivateDnsConfig cfg) {
        Slog.w(TAG, "updatePrivateDns(" + network + ", " + cfg + ")");
        return (cfg != null)
                ? mPrivateDnsMap.put(network.netId, cfg)
                : mPrivateDnsMap.remove(network.netId);
    }

    public void updatePrivateDnsStatus(int netId, LinkProperties lp) {
        // Use the PrivateDnsConfig data pushed to this class instance
        // from ConnectivityService.
        final PrivateDnsConfig privateDnsCfg = mPrivateDnsMap.getOrDefault(netId,
                PRIVATE_DNS_OFF);

        final boolean useTls = privateDnsCfg.useTls;
        final PrivateDnsValidationStatuses statuses =
                useTls ? mPrivateDnsValidationMap.get(netId) : null;
        final boolean validated = (null != statuses) && statuses.hasValidatedServer();
        final boolean strictMode = privateDnsCfg.inStrictMode();
        final String tlsHostname = strictMode ? privateDnsCfg.hostname : null;
        final boolean usingPrivateDns = strictMode || validated;

        lp.setUsePrivateDns(usingPrivateDns);
        lp.setPrivateDnsServerName(tlsHostname);
        if (usingPrivateDns && null != statuses) {
            statuses.fillInValidatedPrivateDns(lp);
        } else {
            lp.setValidatedPrivateDnsServers(Collections.EMPTY_LIST);
        }
    }

    public void updatePrivateDnsValidation(PrivateDnsValidationUpdate update) {
        final PrivateDnsValidationStatuses statuses =
                mPrivateDnsValidationMap.get(update.netId);
        if (statuses == null) return;
        statuses.updateStatus(update);
    }

    /**
     * When creating a new network or transport types are changed in a specific network,
     * transport types are always saved to a hashMap before update dns config.
     * When destroying network, the specific network will be removed from the hashMap.
     * The hashMap is always accessed on the same thread.
     */
    public void updateTransportsForNetwork(int netId, @NonNull int[] transportTypes) {
        mTransportsMap.put(netId, transportTypes);
        sendDnsConfigurationForNetwork(netId);
    }

    /**
     * When {@link LinkProperties} are changed in a specific network, they are
     * always saved to a hashMap before update dns config.
     * When destroying network, the specific network will be removed from the hashMap.
     * The hashMap is always accessed on the same thread.
     */
    public void noteDnsServersForNetwork(int netId, @NonNull LinkProperties lp) {
        mLinkPropertiesMap.put(netId, lp);
        sendDnsConfigurationForNetwork(netId);
    }

    /**
     * Send dns configuration parameters to resolver for a given network.
     */
    public void sendDnsConfigurationForNetwork(int netId) {
        final LinkProperties lp = mLinkPropertiesMap.get(netId);
        final int[] transportTypes = mTransportsMap.get(netId);
        if (lp == null || transportTypes == null) return;
        updateParametersSettings();
        final ResolverParamsParcel paramsParcel = new ResolverParamsParcel();

        // We only use the PrivateDnsConfig data pushed to this class instance
        // from ConnectivityService because it works in coordination with
        // NetworkMonitor to decide which networks need validation and runs the
        // blocking calls to resolve Private DNS strict mode hostnames.
        //
        // At this time we do not attempt to enable Private DNS on non-Internet
        // networks like IMS.
        final PrivateDnsConfig privateDnsCfg = mPrivateDnsMap.getOrDefault(netId,
                PRIVATE_DNS_OFF);
        final boolean useTls = privateDnsCfg.useTls;
        final boolean strictMode = privateDnsCfg.inStrictMode();

        paramsParcel.netId = netId;
        paramsParcel.sampleValiditySeconds = mSampleValidity;
        paramsParcel.successThreshold = mSuccessThreshold;
        paramsParcel.minSamples = mMinSamples;
        paramsParcel.maxSamples = mMaxSamples;
        paramsParcel.servers =
                NetworkUtils.makeStrings(lp.getDnsServers());
        paramsParcel.domains = getDomainStrings(lp.getDomains());
        paramsParcel.tlsName = strictMode ? privateDnsCfg.hostname : "";
        paramsParcel.tlsServers =
                strictMode ? NetworkUtils.makeStrings(
                        Arrays.stream(privateDnsCfg.ips)
                              .filter((ip) -> lp.isReachable(ip))
                              .collect(Collectors.toList()))
                : useTls ? paramsParcel.servers  // Opportunistic
                : new String[0];            // Off
        paramsParcel.resolverOptions = new ResolverOptionsParcel();
        paramsParcel.transportTypes = transportTypes;
        // Prepare to track the validation status of the DNS servers in the
        // resolver config when private DNS is in opportunistic or strict mode.
        if (useTls) {
            if (!mPrivateDnsValidationMap.containsKey(netId)) {
                mPrivateDnsValidationMap.put(netId, new PrivateDnsValidationStatuses());
            }
            mPrivateDnsValidationMap.get(netId).updateTrackedDnses(paramsParcel.tlsServers,
                    paramsParcel.tlsName);
        } else {
            mPrivateDnsValidationMap.remove(netId);
        }

        Slog.d(TAG, String.format("sendDnsConfigurationForNetwork(%d, %s, %s, %d, %d, %d, %d, "
                + "%d, %d, %s, %s)", paramsParcel.netId, Arrays.toString(paramsParcel.servers),
                Arrays.toString(paramsParcel.domains), paramsParcel.sampleValiditySeconds,
                paramsParcel.successThreshold, paramsParcel.minSamples,
                paramsParcel.maxSamples, paramsParcel.baseTimeoutMsec,
                paramsParcel.retryCount, paramsParcel.tlsName,
                Arrays.toString(paramsParcel.tlsServers)));

        try {
            mDnsResolver.setResolverConfiguration(paramsParcel);
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Error setting DNS configuration: " + e);
            return;
        }
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

    /**
     * Flush DNS caches and events work before boot has completed.
     */
    public void flushVmDnsCache() {
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
        String mode = getStringSetting(cr, PRIVATE_DNS_MODE);
        if (TextUtils.isEmpty(mode)) mode = getStringSetting(cr, PRIVATE_DNS_DEFAULT_MODE);
        if (TextUtils.isEmpty(mode)) mode = PRIVATE_DNS_DEFAULT_MODE_FALLBACK;
        return mode;
    }

    private static String getStringSetting(ContentResolver cr, String which) {
        return Settings.Global.getString(cr, which);
    }

    private static String[] getDomainStrings(String domains) {
        return (TextUtils.isEmpty(domains)) ? new String[0] : domains.split(" ");
    }
}
