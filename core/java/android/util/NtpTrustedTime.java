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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.SntpClient;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A singleton that connects with a remote NTP server as its trusted time source. This class
 * is thread-safe. The {@link #forceRefresh()} method is synchronous, i.e. it may occupy the
 * current thread while performing an NTP request. All other threads calling {@link #forceRefresh()}
 * will block during that request.
 *
 * @hide
 */
public abstract class NtpTrustedTime implements TrustedTime {

    private static final String URI_SCHEME_NTP = "ntp";
    @VisibleForTesting
    public static final String NTP_SETTING_SERVER_NAME_DELIMITER = "|";
    private static final String NTP_SETTING_SERVER_NAME_DELIMITER_REGEXP = "\\|";

    /**
     * NTP server configuration.
     *
     * @hide
     */
    public static final class NtpConfig {

        @NonNull private final List<URI> mServerUris;
        @NonNull private final Duration mTimeout;

        /**
         * Creates an instance with the supplied properties. There must be at least one NTP server
         * URI and the timeout must be non-zero / non-negative.
         *
         * <p>If the arguments are invalid then an {@link IllegalArgumentException} will be thrown.
         * See {@link #parseNtpUriStrict(String)} and {@link #parseNtpServerSetting(String)} to
         * create valid URIs.
         */
        public NtpConfig(@NonNull List<URI> serverUris, @NonNull Duration timeout)
                throws IllegalArgumentException {

            Objects.requireNonNull(serverUris);
            if (serverUris.isEmpty()) {
                throw new IllegalArgumentException("Server URIs is empty");
            }

            List<URI> validatedServerUris = new ArrayList<>();
            for (URI serverUri : serverUris) {
                try {
                    URI validatedServerUri = validateNtpServerUri(
                            Objects.requireNonNull(serverUri));
                    validatedServerUris.add(validatedServerUri);
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("Bad server URI", e);
                }
            }
            mServerUris = Collections.unmodifiableList(validatedServerUris);

            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout < 0");
            }
            mTimeout = timeout;
        }

        /** Returns a non-empty, immutable list of NTP server URIs. */
        @NonNull
        public List<URI> getServerUris() {
            return mServerUris;
        }

        @NonNull
        public Duration getTimeout() {
            return mTimeout;
        }

        @Override
        public String toString() {
            return "NtpConnectionInfo{"
                    + "mServerUris=" + mServerUris
                    + ", mTimeout=" + mTimeout
                    + '}';
        }
    }

    /**
     * The result of a successful NTP query.
     *
     * @hide
     */
    // Non-final for mocking frameworks
    public static class TimeResult {
        private final long mUnixEpochTimeMillis;
        private final long mElapsedRealtimeMillis;
        private final int mUncertaintyMillis;
        @NonNull private final InetSocketAddress mNtpServerSocketAddress;

        public TimeResult(
                long unixEpochTimeMillis, long elapsedRealtimeMillis, int uncertaintyMillis,
                @NonNull InetSocketAddress ntpServerSocketAddress) {
            mUnixEpochTimeMillis = unixEpochTimeMillis;
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
            mUncertaintyMillis = uncertaintyMillis;
            mNtpServerSocketAddress = Objects.requireNonNull(ntpServerSocketAddress);
        }

        public long getTimeMillis() {
            return mUnixEpochTimeMillis;
        }

        public long getElapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        public int getUncertaintyMillis() {
            return mUncertaintyMillis;
        }

        /**
         * Calculates and returns the current Unix epoch time accounting for the age of this result.
         */
        public long currentTimeMillis() {
            return mUnixEpochTimeMillis + getAgeMillis();
        }

        /** Calculates and returns the age of this result. */
        public long getAgeMillis() {
            return getAgeMillis(SystemClock.elapsedRealtime());
        }

        /**
         * Calculates and returns the age of this result relative to currentElapsedRealtimeMillis.
         *
         * @param currentElapsedRealtimeMillis - reference elapsed real time
         */
        public long getAgeMillis(long currentElapsedRealtimeMillis) {
            return currentElapsedRealtimeMillis - mElapsedRealtimeMillis;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TimeResult)) {
                return false;
            }
            TimeResult that = (TimeResult) o;
            return mUnixEpochTimeMillis == that.mUnixEpochTimeMillis
                    && mElapsedRealtimeMillis == that.mElapsedRealtimeMillis
                    && mUncertaintyMillis == that.mUncertaintyMillis
                    && mNtpServerSocketAddress.equals(
                    that.mNtpServerSocketAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUnixEpochTimeMillis, mElapsedRealtimeMillis, mUncertaintyMillis,
                    mNtpServerSocketAddress);
        }

        @Override
        public String toString() {
            return "TimeResult{"
                    + "unixEpochTime=" + Instant.ofEpochMilli(mUnixEpochTimeMillis)
                    + ", elapsedRealtime=" + Duration.ofMillis(mElapsedRealtimeMillis)
                    + ", mUncertaintyMillis=" + mUncertaintyMillis
                    + ", mNtpServerSocketAddress=" + mNtpServerSocketAddress
                    + '}';
        }
    }

    private static final String TAG = "NtpTrustedTime";
    private static final boolean LOGD = false;

    private static NtpTrustedTime sSingleton;

    /** A lock to prevent multiple refreshes taking place at the same time. */
    private final Object mRefreshLock = new Object();

    /** A lock to ensure safe read/writes to configuration. */
    private final Object mConfigLock = new Object();

    /** An in-memory config override for use during tests. */
    @GuardedBy("mConfigLock")
    @Nullable
    private NtpConfig mNtpConfigForTests;

    /**
     * The latest time result.
     *
     * <p>Written when holding {@link #mRefreshLock} but declared volatile and can be read outside
     * synchronized blocks to avoid blocking dump() during {@link #forceRefresh}.
     */
    @Nullable
    private volatile TimeResult mTimeResult;

    /**
     * The last successful NTP server URI, i.e. the one used to obtain {@link #mTimeResult} when it
     * is non-null.
     *
     * <p>Written when holding {@link #mRefreshLock} but declared volatile and can be read outside
     * synchronized blocks to avoid blocking dump() during {@link #forceRefresh}.
     */
    @Nullable
    private volatile URI mLastSuccessfulNtpServerUri;

    protected NtpTrustedTime() {
    }

    @UnsupportedAppUsage
    public static synchronized NtpTrustedTime getInstance(Context context) {
        if (sSingleton == null) {
            Context appContext = context.getApplicationContext();
            sSingleton = new NtpTrustedTimeImpl(appContext);
        }
        return sSingleton;
    }

    /**
     * Overrides the NTP server config for tests. Passing {@code null} to a parameter clears the
     * test value, i.e. so the normal value will be used next time.
     */
    public void setServerConfigForTests(@NonNull NtpConfig ntpConfig) {
        synchronized (mConfigLock) {
            mNtpConfigForTests = ntpConfig;
        }
    }

    /** Forces a refresh using the default network. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean forceRefresh() {
        synchronized (mRefreshLock) {
            Network network = getDefaultNetwork();
            if (network == null) {
                if (LOGD) Log.d(TAG, "forceRefresh: no network available");
                return false;
            }

            return forceRefreshLocked(network);
        }
    }

    /** Forces a refresh using the specified network. */
    public boolean forceRefresh(@NonNull Network network) {
        Objects.requireNonNull(network);

        synchronized (mRefreshLock) {
            // Prevent concurrent refreshes.
            return forceRefreshLocked(network);
        }
    }

    @GuardedBy("mRefreshLock")
    private boolean forceRefreshLocked(@NonNull Network network) {
        Objects.requireNonNull(network);

        if (!isNetworkConnected(network)) {
            if (LOGD) Log.d(TAG, "forceRefreshLocked: network=" + network + " is not connected");
            return false;
        }

        NtpConfig ntpConfig = getNtpConfig();
        if (ntpConfig == null) {
            // missing server config, so no NTP time available
            if (LOGD) Log.d(TAG, "forceRefreshLocked: invalid server config");
            return false;
        }

        if (LOGD) {
            Log.d(TAG, "forceRefreshLocked: NTP request network=" + network
                    + " ntpConfig=" + ntpConfig);
        }

        List<URI> unorderedServerUris = ntpConfig.getServerUris();

        // Android supports multiple NTP server URIs for situations where servers might be
        // unreachable for some devices due to network topology, e.g. we understand that devices
        // travelling to China often have difficulty accessing "time.android.com". Android
        // partners may want to configure alternative URIs for devices sold globally, or those
        // that are likely to travel to part of the world without access to the full internet.
        //
        // The server URI list is expected to contain one element in the general case, with two
        // or three as the anticipated maximum. The list is never empty. Server URIs are
        // considered to be in a rough priority order of servers to try initially (no
        // randomization), but besides that there is assumed to be no preference.
        //
        // The server selection algorithm below tries to stick with a successfully accessed NTP
        // server's URI where possible:
        //
        // The algorithm based on the assumption that a cluster of NTP servers sharing the same
        // host name, particularly commercially run ones, are likely to agree more closely on
        // the time than servers from different URIs, so it's best to be sticky. Switching
        // between URIs could result in flip-flopping between reference clocks or involve
        // talking to server clusters with different approaches to leap second handling.
        //
        // Stickiness may also be useful if some server URIs early in the list are permanently
        // black-holing requests, or if the responses are not routed back. In those cases it's
        // best not to try those URIs more than we have to, as might happen if the algorithm
        // always started at the beginning of the list.
        //
        // Generally, we have to assume that any of the configured servers are going to be "good
        // enough" as an external reference clock when reachable, so the stickiness is a very
        // lightly applied bias. There's no tracking of failure rates or back-off on a per-URI
        // basis; higher level code is expected to handle rate limiting of NTP requests in the
        // event of failure to contact any server.

        List<URI> orderedServerUris = new ArrayList<>();
        for (URI serverUri : unorderedServerUris) {
            if (serverUri.equals(mLastSuccessfulNtpServerUri)) {
                orderedServerUris.add(0, serverUri);
            } else {
                orderedServerUris.add(serverUri);
            }
        }

        for (URI serverUri : orderedServerUris) {
            TimeResult timeResult = queryNtpServer(network, serverUri, ntpConfig.getTimeout());
            // Only overwrite previous state if the request was successful.
            if (timeResult != null) {
                mLastSuccessfulNtpServerUri = serverUri;
                mTimeResult = timeResult;
                return true;
            }
        }
        return false;
    }

    private NtpConfig getNtpConfig() {
        synchronized (mConfigLock) {
            if (mNtpConfigForTests != null) {
                return mNtpConfigForTests;
            }
            return getNtpConfigInternal();
        }
    }

    /**
     * Returns the {@link NtpConfig} to use during an NTP query. This method can return {@code null}
     * if there is no config, or the config found is invalid.
     *
     * <p>This method has been made public for easy replacement during tests.
     */
    @GuardedBy("mConfigLock")
    @VisibleForTesting
    @Nullable
    public abstract NtpConfig getNtpConfigInternal();

    /**
     * Returns the default {@link Network} to use during an NTP query when no network is specified.
     * This method can return {@code null} if the device hasn't fully initialized or there is no
     * active network.
     *
     * <p>This method has been made public for easy replacement during tests.
     */
    @VisibleForTesting
    @Nullable
    public abstract Network getDefaultNetwork();

    /**
     * Returns {@code true} if there is likely to be connectivity on the supplied network.
     *
     * <p>This method has been made public for easy replacement during tests.
     */
    @VisibleForTesting
    public abstract boolean isNetworkConnected(@NonNull Network network);

    /**
     * Queries the specified NTP server. This is a blocking call. Returns {@code null} if the query
     * fails.
     *
     * <p>This method has been made public for easy replacement during tests.
     */
    @VisibleForTesting
    @Nullable
    public abstract TimeResult queryNtpServer(
            @NonNull Network network, @NonNull URI ntpServerUri, @NonNull Duration timeout);

    /**
     * Only kept for UnsupportedAppUsage.
     *
     * @deprecated Use {@link #getCachedTimeResult()} to obtain a {@link TimeResult} atomically.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean hasCache() {
        return mTimeResult != null;
    }

    /**
     * Only kept for UnsupportedAppUsage.
     *
     * @deprecated Use {@link #getCachedTimeResult()} to obtain a {@link TimeResult} atomically.
     */
    @Deprecated
    @Override
    public long getCacheAge() {
        TimeResult timeResult = mTimeResult;
        if (timeResult != null) {
            return SystemClock.elapsedRealtime() - timeResult.getElapsedRealtimeMillis();
        } else {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Only kept for UnsupportedAppUsage.
     *
     * @deprecated Use {@link #getCachedTimeResult()} to obtain a {@link TimeResult} atomically.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long currentTimeMillis() {
        TimeResult timeResult = mTimeResult;
        if (timeResult == null) {
            throw new IllegalStateException("Missing authoritative time source");
        }
        if (LOGD) Log.d(TAG, "currentTimeMillis() cache hit");

        // current time is age after the last ntp cache; callers who
        // want fresh values will hit forceRefresh() first.
        return timeResult.currentTimeMillis();
    }

    /**
     * Only kept for UnsupportedAppUsage.
     *
     * @deprecated Use {@link #getCachedTimeResult()} to obtain a {@link TimeResult} atomically.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getCachedNtpTime() {
        if (LOGD) Log.d(TAG, "getCachedNtpTime() cache hit");
        TimeResult timeResult = mTimeResult;
        return timeResult == null ? 0 : timeResult.getTimeMillis();
    }

    /**
     * Only kept for UnsupportedAppUsage.
     *
     * @deprecated Use {@link #getCachedTimeResult()} to obtain a {@link TimeResult} atomically.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getCachedNtpTimeReference() {
        TimeResult timeResult = mTimeResult;
        return timeResult == null ? 0 : timeResult.getElapsedRealtimeMillis();
    }

    /**
     * Returns an object containing the latest NTP information available. Can return {@code null} if
     * no information is available.
     */
    @Nullable
    public TimeResult getCachedTimeResult() {
        return mTimeResult;
    }

    /** Sets the last received NTP time. Intended for use during tests. */
    public void setCachedTimeResult(TimeResult timeResult) {
        synchronized (mRefreshLock) {
            mTimeResult = timeResult;
        }
    }

    /** Clears the last received NTP time. Intended for use during tests. */
    public void clearCachedTimeResult() {
        synchronized (mRefreshLock) {
            mTimeResult = null;
        }
    }

    /**
     * Parses and returns an NTP server config URI, or throws an exception if the URI doesn't
     * conform to expectations.
     *
     * <p>NTP server config URIs are in the form "ntp://{hostname}[:port]". This is not a registered
     * IANA URI scheme.
     */
    @NonNull
    public static URI parseNtpUriStrict(@NonNull String ntpServerUriString)
            throws URISyntaxException {
        // java.net.URI is used in preference to android.net.Uri, since android.net.Uri is very
        // forgiving of obvious errors. URI catches issues sooner.
        URI unvalidatedUri = new URI(ntpServerUriString);
        return validateNtpServerUri(unvalidatedUri);
    }

    /**
     * Parses a setting string and returns a list of URIs that will be accepted by {@link
     * NtpConfig}, or {@code null} if the string is invalid.
     *
     * <p>The setting string is expected to be one or more server values separated by a pipe ("|")
     * character.
     *
     * <p>NTP server config URIs are in the form "ntp://{hostname}[:port]". This is not a registered
     * IANA URI scheme.
     *
     * <p>Unlike {@link #parseNtpUriStrict(String)} this method will not throw an exception. It
     * checks each value for a leading "ntp:" and will call through to {@link
     * #parseNtpUriStrict(String)} to attempt to parse it, returning {@code null} if it fails.
     * To support legacy settings values, it will also accept string values that only consists of a
     * server name, which will be coerced into a URI in the form "ntp://{server name}".
     */
    @VisibleForTesting
    @Nullable
    public static List<URI> parseNtpServerSetting(@Nullable String ntpServerSetting) {
        if (TextUtils.isEmpty(ntpServerSetting)) {
            return null;
        } else {
            String[] values = ntpServerSetting.split(NTP_SETTING_SERVER_NAME_DELIMITER_REGEXP);
            if (values.length == 0) {
                return null;
            }

            List<URI> uris = new ArrayList<>();
            for (String value : values) {
                if (value.startsWith(URI_SCHEME_NTP + ":")) {
                    try {
                        uris.add(parseNtpUriStrict(value));
                    } catch (URISyntaxException e) {
                        Log.w(TAG, "Rejected NTP uri setting=" + ntpServerSetting, e);
                        return null;
                    }
                } else {
                    // This is the legacy settings path. Assumes that the string is just a host name
                    // and creates a URI in the form ntp://<host name>
                    try {
                        URI uri = new URI(URI_SCHEME_NTP, /*host=*/value,
                                /*path=*/null, /*fragment=*/null);
                        // Paranoia: validate just in case the host name somehow results in a bad
                        // URI.
                        URI validatedUri = validateNtpServerUri(uri);
                        uris.add(validatedUri);
                    } catch (URISyntaxException e) {
                        Log.w(TAG, "Rejected NTP legacy setting=" + ntpServerSetting, e);
                        return null;
                    }
                }
            }
            return uris;
        }
    }

    /**
     * Checks that the supplied URI can be used to identify an NTP server.
     * This method currently ignores Uri components that are not used, only checking the parts that
     * must be present. Returns the supplied {@code uri} if validation is successful.
     */
    @NonNull
    private static URI validateNtpServerUri(@NonNull URI uri) throws URISyntaxException {
        if (!uri.isAbsolute()) {
            throw new URISyntaxException(uri.toString(), "Relative URI not supported");
        }
        if (!URI_SCHEME_NTP.equals(uri.getScheme())) {
            throw new URISyntaxException(uri.toString(), "Unrecognized scheme");
        }
        String host = uri.getHost();
        if (TextUtils.isEmpty(host)) {
            throw new URISyntaxException(uri.toString(), "Missing host");
        }
        return uri;
    }

    /** Prints debug information. */
    public void dump(PrintWriter pw) {
        synchronized (mConfigLock) {
            pw.println("getNtpConfig()=" + getNtpConfig());
            pw.println("mNtpConfigForTests=" + mNtpConfigForTests);
        }

        pw.println("mLastSuccessfulNtpServerUri=" + mLastSuccessfulNtpServerUri);

        TimeResult timeResult = mTimeResult;
        pw.println("mTimeResult=" + timeResult);
        if (timeResult != null) {
            pw.println("mTimeResult.getAgeMillis()="
                    + Duration.ofMillis(timeResult.getAgeMillis()));
        }
    }

    /**
     * The real implementation of {@link NtpTrustedTime}. Contains the parts that are more difficult
     * to test.
     */
    private static final class NtpTrustedTimeImpl extends NtpTrustedTime {

        @GuardedBy("this")
        private ConnectivityManager mConnectivityManager;

        @NonNull
        private final Context mContext;

        private NtpTrustedTimeImpl(@NonNull Context context) {
            mContext = Objects.requireNonNull(context);
        }

        @Override
        @VisibleForTesting
        @Nullable
        public NtpConfig getNtpConfigInternal() {
            final ContentResolver resolver = mContext.getContentResolver();
            final Resources res = mContext.getResources();

            // The Settings value has priority over static config. Check settings first.
            final String serverGlobalSetting =
                    Settings.Global.getString(resolver, Settings.Global.NTP_SERVER);
            final List<URI> settingsServerUris = parseNtpServerSetting(serverGlobalSetting);

            List<URI> ntpServerUris;
            if (settingsServerUris != null) {
                ntpServerUris = settingsServerUris;
            } else {
                String[] configValues =
                        res.getStringArray(com.android.internal.R.array.config_ntpServers);
                try {
                    List<URI> configServerUris = new ArrayList<>();
                    for (String configValue : configValues) {
                        configServerUris.add(parseNtpUriStrict(configValue));
                    }
                    ntpServerUris = configServerUris;
                } catch (URISyntaxException e) {
                    ntpServerUris = null;
                }
            }

            final int defaultTimeoutMillis =
                    res.getInteger(com.android.internal.R.integer.config_ntpTimeout);
            final Duration timeout = Duration.ofMillis(Settings.Global.getInt(
                    resolver, Settings.Global.NTP_TIMEOUT, defaultTimeoutMillis));
            return ntpServerUris == null ? null : new NtpConfig(ntpServerUris, timeout);
        }

        @Override
        public Network getDefaultNetwork() {
            ConnectivityManager connectivityManager = getConnectivityManager();
            if (connectivityManager == null) {
                return null;
            }
            return connectivityManager.getActiveNetwork();
        }

        @Override
        public boolean isNetworkConnected(@NonNull Network network) {
            ConnectivityManager connectivityManager = getConnectivityManager();
            if (connectivityManager == null) {
                return false;
            }
            final NetworkInfo ni = connectivityManager.getNetworkInfo(network);

            // This connectivity check is to avoid performing a DNS lookup for the time server on a
            // unconnected network. There are races to obtain time in Android when connectivity
            // changes, which means that forceRefresh() can be called by various components before
            // the network is actually available. This led in the past to DNS lookup failures being
            // cached (~2 seconds) thereby preventing the device successfully making an NTP request
            // when connectivity had actually been established.
            // A side effect of check is that tests that run a fake NTP server on the device itself
            // will only be able to use it if the active network is connected, even though loopback
            // addresses are actually reachable.
            if (ni == null || !ni.isConnected()) {
                if (LOGD) Log.d(TAG, "getNetwork: no connectivity");
                return false;
            }
            return true;
        }

        private synchronized ConnectivityManager getConnectivityManager() {
            if (mConnectivityManager == null) {
                mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            }
            if (mConnectivityManager == null) {
                if (LOGD) Log.d(TAG, "getConnectivityManager: no ConnectivityManager");
            }
            return mConnectivityManager;
        }

        @Override
        @Nullable
        public TimeResult queryNtpServer(
                @NonNull Network network, @NonNull URI ntpServerUri, @NonNull Duration timeout) {

            final SntpClient client = new SntpClient();
            final String serverName = ntpServerUri.getHost();
            final int port = ntpServerUri.getPort() == -1
                    ? SntpClient.STANDARD_NTP_PORT : ntpServerUri.getPort();
            final int timeoutMillis = saturatedCast(timeout.toMillis());
            if (client.requestTime(serverName, port, timeoutMillis, network)) {
                int ntpUncertaintyMillis = saturatedCast(client.getRoundTripTime() / 2);
                InetSocketAddress ntpServerSocketAddress = client.getServerSocketAddress();
                return new TimeResult(
                        client.getNtpTime(), client.getNtpTimeReference(), ntpUncertaintyMillis,
                        ntpServerSocketAddress);
            } else {
                return null;
            }
        }

        /**
         * Casts a {@code long} to an {@code int}, clamping the value within the int range.
         */
        private static int saturatedCast(long longValue) {
            if (longValue > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (longValue < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return (int) longValue;
        }
    }
}
