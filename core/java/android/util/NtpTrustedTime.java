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
import java.util.Objects;
import java.util.function.Supplier;

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

    /**
     * NTP server configuration.
     *
     * @hide
     */
    public static final class NtpConfig {

        @NonNull private final URI mServerUri;
        @NonNull private final Duration mTimeout;

        /**
         * Creates an instance. If the arguments are invalid then an {@link
         * IllegalArgumentException} will be thrown. See {@link #parseNtpUriStrict(String)} and
         * {@link #parseNtpServerSetting(String)} to create valid URIs.
         */
        public NtpConfig(@NonNull URI serverUri, @NonNull Duration timeout)
                throws IllegalArgumentException {
            try {
                mServerUri = validateNtpServerUri(Objects.requireNonNull(serverUri));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Bad URI", e);
            }

            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout < 0");
            }
            mTimeout = timeout;
        }

        @NonNull
        public URI getServerUri() {
            return mServerUri;
        }

        @NonNull
        public Duration getTimeout() {
            return mTimeout;
        }

        @Override
        public String toString() {
            return "NtpConnectionInfo{"
                    + "mServerUri=" + mServerUri
                    + ", mTimeout=" + mTimeout
                    + '}';
        }
    }

    /**
     * The result of a successful NTP query.
     *
     * @hide
     */
    public static final class TimeResult {
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

    /** An in-memory config override for use during tests. */
    @GuardedBy("this")
    @Nullable
    private NtpConfig mNtpConfigForTests;

    // Declared volatile and accessed outside synchronized blocks to avoid blocking reads during
    // forceRefresh().
    private volatile TimeResult mTimeResult;

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
        synchronized (this) {
            mNtpConfigForTests = ntpConfig;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean forceRefresh() {
        synchronized (this) {
            NtpConfig ntpConfig = getNtpConfig();
            if (ntpConfig == null) {
                // missing server config, so no NTP time available
                if (LOGD) Log.d(TAG, "forceRefresh: invalid server config");
                return false;
            }

            Network network = getNetwork();
            if (network == null) {
                if (LOGD) Log.d(TAG, "forceRefresh: no network available");
                return false;
            }

            if (LOGD) {
                Log.d(TAG, "forceRefresh: NTP request network=" + network
                        + " ntpConfig=" + ntpConfig);
            }
            TimeResult timeResult =
                    queryNtpServer(network, ntpConfig.getServerUri(), ntpConfig.getTimeout());
            if (timeResult != null) {
                // Keep any previous time result.
                mTimeResult = timeResult;
            }
            return timeResult != null;
        }
    }

    @GuardedBy("this")
    private NtpConfig getNtpConfig() {
        if (mNtpConfigForTests != null) {
            return mNtpConfigForTests;
        }
        return getNtpConfigInternal();
    }

    /**
     * Returns the {@link NtpConfig} to use during an NTP query. This method can return {@code null}
     * if there is no config, or the config found is invalid.
     *
     * <p>This method has been made public for easy replacement during tests.
     */
    @VisibleForTesting
    @Nullable
    public abstract NtpConfig getNtpConfigInternal();

    /**
     * Returns the {@link Network} to use during an NTP query. This method can return {@code null}
     * if there is no connectivity
     *
     * <p>This method has been made public for easy replacement during tests.
     */
    @VisibleForTesting
    @Nullable
    public abstract Network getNetwork();

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

    /** Clears the last received NTP. Intended for use during tests. */
    public void clearCachedTimeResult() {
        synchronized (this) {
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
    public static URI parseNtpUriStrict(String ntpServerUriString) throws URISyntaxException {
        // java.net.URI is used in preference to android.net.Uri, since android.net.Uri is very
        // forgiving of obvious errors. URI catches issues sooner.
        URI unvalidatedUri = new URI(ntpServerUriString);
        return validateNtpServerUri(unvalidatedUri);
    }

    /**
     * Parses a setting string and returns a URI that will be accepted by {@link NtpConfig}, or
     * {@code null} if the string does not produce a URI considered valid.
     *
     * <p>NTP server config URIs are in the form "ntp://{hostname}[:port]". This is not a registered
     * IANA URI scheme.
     *
     * <p>Unlike {@link #parseNtpUriStrict(String)} this method will not throw an exception. It
     * checks for a leading "ntp:" and will call through to {@link #parseNtpUriStrict(String)} to
     * attempt to parse it, returning {@code null} if it fails. To support legacy settings values,
     * it will also accept a string that only consists of a server name, which will be coerced into
     * a URI in the form "ntp://{server name}".
     */
    @VisibleForTesting
    public static URI parseNtpServerSetting(String ntpServerSetting) {
        if (TextUtils.isEmpty(ntpServerSetting)) {
            return null;
        } else if (ntpServerSetting.startsWith(URI_SCHEME_NTP + ":")) {
            try {
                return parseNtpUriStrict(ntpServerSetting);
            } catch (URISyntaxException e) {
                Log.w(TAG, "Rejected NTP uri setting=" + ntpServerSetting, e);
                return null;
            }
        } else {
            // This is the legacy settings path. Assumes that the string is just a host name and
            // creates a URI in the form ntp://<host name>
            try {
                URI uri = new URI(URI_SCHEME_NTP, /*host=*/ntpServerSetting,
                        /*path=*/null, /*fragment=*/null);
                // Paranoia: validate just in case the host name somehow results in a bad URI.
                return validateNtpServerUri(uri);
            } catch (URISyntaxException e) {
                Log.w(TAG, "Rejected NTP legacy setting=" + ntpServerSetting, e);
                return null;
            }
        }
    }

    /**
     * Checks that the supplied URI can be used to identify an NTP server.
     * This method currently ignores Uri components that are not used, only checking the parts that
     * must be present. Returns the supplied {@code uri} if validation is successful.
     */
    private static URI validateNtpServerUri(URI uri) throws URISyntaxException {
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
        synchronized (this) {
            pw.println("getNtpConfig()=" + getNtpConfig());
            pw.println("mTimeResult=" + mTimeResult);
            if (mTimeResult != null) {
                pw.println("mTimeResult.getAgeMillis()="
                        + Duration.ofMillis(mTimeResult.getAgeMillis()));
            }
        }
    }

    /**
     * The real implementation of {@link NtpTrustedTime}. Contains the parts that are more difficult
     * to test.
     */
    private static final class NtpTrustedTimeImpl extends NtpTrustedTime {

        /**
         * A supplier that returns the ConnectivityManager. The Supplier can return null if
         * ConnectivityService isn't running yet.
         */
        private final Supplier<ConnectivityManager> mConnectivityManagerSupplier =
                new Supplier<>() {
            private ConnectivityManager mConnectivityManager;

            @Nullable
            @Override
            public synchronized ConnectivityManager get() {
                // We can't do this at initialization time: ConnectivityService might not be running
                // yet.
                if (mConnectivityManager == null) {
                    mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
                }
                return mConnectivityManager;
            }
        };

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
            final URI settingsServerInfo = parseNtpServerSetting(serverGlobalSetting);

            URI ntpServerUri;
            if (settingsServerInfo != null) {
                ntpServerUri = settingsServerInfo;
            } else {
                String configValue = res.getString(com.android.internal.R.string.config_ntpServer);
                try {
                    ntpServerUri = parseNtpUriStrict(configValue);
                } catch (URISyntaxException e) {
                    ntpServerUri = null;
                }
            }

            final int defaultTimeoutMillis =
                    res.getInteger(com.android.internal.R.integer.config_ntpTimeout);
            final Duration timeout = Duration.ofMillis(Settings.Global.getInt(
                    resolver, Settings.Global.NTP_TIMEOUT, defaultTimeoutMillis));
            return ntpServerUri == null ? null : new NtpConfig(ntpServerUri, timeout);
        }

        @Override
        public Network getNetwork() {
            ConnectivityManager connectivityManager = mConnectivityManagerSupplier.get();
            if (connectivityManager == null) {
                if (LOGD) Log.d(TAG, "getNetwork: no ConnectivityManager");
                return null;
            }
            final Network network = connectivityManager.getActiveNetwork();
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
                return null;
            }
            return network;
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
