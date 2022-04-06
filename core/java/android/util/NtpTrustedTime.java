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

import java.io.PrintWriter;
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
public class NtpTrustedTime implements TrustedTime {

    /**
     * The result of a successful NTP query.
     *
     * @hide
     */
    public static class TimeResult {
        private final long mTimeMillis;
        private final long mElapsedRealtimeMillis;
        private final long mCertaintyMillis;

        public TimeResult(long timeMillis, long elapsedRealtimeMillis, long certaintyMillis) {
            mTimeMillis = timeMillis;
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
            mCertaintyMillis = certaintyMillis;
        }

        public long getTimeMillis() {
            return mTimeMillis;
        }

        public long getElapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        public long getCertaintyMillis() {
            return mCertaintyMillis;
        }

        /** Calculates and returns the current time accounting for the age of this result. */
        public long currentTimeMillis() {
            return mTimeMillis + getAgeMillis();
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
        public String toString() {
            return "TimeResult{"
                    + "mTimeMillis=" + Instant.ofEpochMilli(mTimeMillis)
                    + ", mElapsedRealtimeMillis=" + Duration.ofMillis(mElapsedRealtimeMillis)
                    + ", mCertaintyMillis=" + mCertaintyMillis
                    + '}';
        }
    }

    private static final String TAG = "NtpTrustedTime";
    private static final boolean LOGD = false;

    private static NtpTrustedTime sSingleton;

    @NonNull
    private final Context mContext;

    /**
     * A supplier that returns the ConnectivityManager. The Supplier can return null if
     * ConnectivityService isn't running yet.
     */
    private final Supplier<ConnectivityManager> mConnectivityManagerSupplier =
            new Supplier<ConnectivityManager>() {
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

    /** An in-memory config override for use during tests. */
    @Nullable
    private String mHostnameForTests;

    /** An in-memory config override for use during tests. */
    @Nullable
    private Integer mPortForTests;

    /** An in-memory config override for use during tests. */
    @Nullable
    private Duration mTimeoutForTests;

    // Declared volatile and accessed outside of synchronized blocks to avoid blocking reads during
    // forceRefresh().
    private volatile TimeResult mTimeResult;

    private NtpTrustedTime(Context context) {
        mContext = Objects.requireNonNull(context);
    }

    @UnsupportedAppUsage
    public static synchronized NtpTrustedTime getInstance(Context context) {
        if (sSingleton == null) {
            Context appContext = context.getApplicationContext();
            sSingleton = new NtpTrustedTime(appContext);
        }
        return sSingleton;
    }

    /**
     * Overrides the NTP server config for tests. Passing {@code null} to a parameter clears the
     * test value, i.e. so the normal value will be used next time.
     */
    public void setServerConfigForTests(
            @Nullable String hostname, @Nullable Integer port, @Nullable Duration timeout) {
        synchronized (this) {
            mHostnameForTests = hostname;
            mPortForTests = port;
            mTimeoutForTests = timeout;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean forceRefresh() {
        synchronized (this) {
            NtpConnectionInfo connectionInfo = getNtpConnectionInfo();
            if (connectionInfo == null) {
                // missing server config, so no NTP time available
                if (LOGD) Log.d(TAG, "forceRefresh: invalid server config");
                return false;
            }

            ConnectivityManager connectivityManager = mConnectivityManagerSupplier.get();
            if (connectivityManager == null) {
                if (LOGD) Log.d(TAG, "forceRefresh: no ConnectivityManager");
                return false;
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
                if (LOGD) Log.d(TAG, "forceRefresh: no connectivity");
                return false;
            }

            if (LOGD) Log.d(TAG, "forceRefresh() from cache miss");
            final SntpClient client = new SntpClient();
            final String serverName = connectionInfo.getServer();
            final int port = connectionInfo.getPort();
            final int timeoutMillis = connectionInfo.getTimeoutMillis();
            if (client.requestTime(serverName, port, timeoutMillis, network)) {
                long ntpCertainty = client.getRoundTripTime() / 2;
                mTimeResult = new TimeResult(
                        client.getNtpTime(), client.getNtpTimeReference(), ntpCertainty);
                return true;
            } else {
                return false;
            }
        }
    }

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

    private static class NtpConnectionInfo {

        @NonNull private final String mServer;
        private final int mPort;
        private final int mTimeoutMillis;

        NtpConnectionInfo(@NonNull String server, int port, int timeoutMillis) {
            mServer = Objects.requireNonNull(server);
            mPort = port;
            mTimeoutMillis = timeoutMillis;
        }

        @NonNull
        public String getServer() {
            return mServer;
        }

        @NonNull
        public int getPort() {
            return mPort;
        }

        int getTimeoutMillis() {
            return mTimeoutMillis;
        }

        @Override
        public String toString() {
            return "NtpConnectionInfo{"
                    + "mServer='" + mServer + '\''
                    + ", mPort='" + mPort + '\''
                    + ", mTimeoutMillis=" + mTimeoutMillis
                    + '}';
        }
    }

    @GuardedBy("this")
    private NtpConnectionInfo getNtpConnectionInfo() {
        final ContentResolver resolver = mContext.getContentResolver();

        final Resources res = mContext.getResources();

        final String hostname;
        if (mHostnameForTests != null) {
            hostname = mHostnameForTests;
        } else {
            String serverGlobalSetting =
                    Settings.Global.getString(resolver, Settings.Global.NTP_SERVER);
            if (serverGlobalSetting != null) {
                hostname = serverGlobalSetting;
            } else {
                hostname = res.getString(com.android.internal.R.string.config_ntpServer);
            }
        }

        final Integer port;
        if (mPortForTests != null) {
            port = mPortForTests;
        } else {
            port = SntpClient.STANDARD_NTP_PORT;
        }

        final int timeoutMillis;
        if (mTimeoutForTests != null) {
            timeoutMillis = (int) mTimeoutForTests.toMillis();
        } else {
            int defaultTimeoutMillis =
                    res.getInteger(com.android.internal.R.integer.config_ntpTimeout);
            timeoutMillis = Settings.Global.getInt(
                    resolver, Settings.Global.NTP_TIMEOUT, defaultTimeoutMillis);
        }
        return TextUtils.isEmpty(hostname) ? null :
            new NtpConnectionInfo(hostname, port, timeoutMillis);
    }

    /** Prints debug information. */
    public void dump(PrintWriter pw) {
        synchronized (this) {
            pw.println("getNtpConnectionInfo()=" + getNtpConnectionInfo());
            pw.println("mTimeResult=" + mTimeResult);
            if (mTimeResult != null) {
                pw.println("mTimeResult.getAgeMillis()="
                        + Duration.ofMillis(mTimeResult.getAgeMillis()));
            }
        }
    }
}
