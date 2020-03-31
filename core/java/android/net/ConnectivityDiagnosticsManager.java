/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Class that provides utilities for collecting network connectivity diagnostics information.
 * Connectivity information is made available through triggerable diagnostics tools and by listening
 * to System validations. Some diagnostics information may be permissions-restricted.
 *
 * <p>ConnectivityDiagnosticsManager is intended for use by applications offering network
 * connectivity on a user device. These tools will provide several mechanisms for these applications
 * to be alerted to network conditions as well as diagnose potential network issues themselves.
 *
 * <p>The primary responsibilities of this class are to:
 *
 * <ul>
 *   <li>Allow permissioned applications to register and unregister callbacks for network event
 *       notifications
 *   <li>Invoke callbacks for network event notifications, including:
 *       <ul>
 *         <li>Network validations
 *         <li>Data stalls
 *         <li>Connectivity reports from applications
 *       </ul>
 * </ul>
 */
public class ConnectivityDiagnosticsManager {
    /** @hide */
    @VisibleForTesting
    public static final Map<ConnectivityDiagnosticsCallback, ConnectivityDiagnosticsBinder>
            sCallbacks = new ConcurrentHashMap<>();

    private final Context mContext;
    private final IConnectivityManager mService;

    /** @hide */
    public ConnectivityDiagnosticsManager(Context context, IConnectivityManager service) {
        mContext = Preconditions.checkNotNull(context, "missing context");
        mService = Preconditions.checkNotNull(service, "missing IConnectivityManager");
    }

    /** @hide */
    @VisibleForTesting
    public static boolean persistableBundleEquals(
            @Nullable PersistableBundle a, @Nullable PersistableBundle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!Objects.equals(a.keySet(), b.keySet())) return false;
        for (String key : a.keySet()) {
            if (!Objects.equals(a.get(key), b.get(key))) return false;
        }
        return true;
    }

    /** Class that includes connectivity information for a specific Network at a specific time. */
    public static final class ConnectivityReport implements Parcelable {
        /**
         * The overall status of the network is that it is invalid; it neither provides
         * connectivity nor has been exempted from validation.
         */
        public static final int NETWORK_VALIDATION_RESULT_INVALID = 0;

        /**
         * The overall status of the network is that it is valid, this may be because it provides
         * full Internet access (all probes succeeded), or because other properties of the network
         * caused probes not to be run.
         */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_RESULT_VALID
        public static final int NETWORK_VALIDATION_RESULT_VALID = 1;

        /**
         * The overall status of the network is that it provides partial connectivity; some
         * probed services succeeded but others failed.
         */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_RESULT_PARTIAL;
        public static final int NETWORK_VALIDATION_RESULT_PARTIALLY_VALID = 2;

        /**
         * Due to the properties of the network, validation was not performed.
         */
        public static final int NETWORK_VALIDATION_RESULT_SKIPPED = 3;

        /** @hide */
        @IntDef(
                prefix = {"NETWORK_VALIDATION_RESULT_"},
                value = {
                        NETWORK_VALIDATION_RESULT_INVALID,
                        NETWORK_VALIDATION_RESULT_VALID,
                        NETWORK_VALIDATION_RESULT_PARTIALLY_VALID,
                        NETWORK_VALIDATION_RESULT_SKIPPED
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface NetworkValidationResult {}

        /**
         * The overall validation result for the Network being reported on.
         *
         * <p>The possible values for this key are:
         * {@link #NETWORK_VALIDATION_RESULT_INVALID},
         * {@link #NETWORK_VALIDATION_RESULT_VALID},
         * {@link #NETWORK_VALIDATION_RESULT_PARTIALLY_VALID},
         * {@link #NETWORK_VALIDATION_RESULT_SKIPPED}.
         *
         * @see android.net.NetworkCapabilities#NET_CAPABILITY_VALIDATED
         */
        @NetworkValidationResult
        public static final String KEY_NETWORK_VALIDATION_RESULT = "networkValidationResult";

        /** DNS probe. */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_PROBE_DNS
        public static final int NETWORK_PROBE_DNS = 0x04;

        /** HTTP probe. */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTP
        public static final int NETWORK_PROBE_HTTP = 0x08;

        /** HTTPS probe. */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTPS;
        public static final int NETWORK_PROBE_HTTPS = 0x10;

        /** Captive portal fallback probe. */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_FALLBACK
        public static final int NETWORK_PROBE_FALLBACK = 0x20;

        /** Private DNS (DNS over TLS) probd. */
        // TODO: link to INetworkMonitor.NETWORK_VALIDATION_PROBE_PRIVDNS
        public static final int NETWORK_PROBE_PRIVATE_DNS = 0x40;

        /** @hide */
        @IntDef(
                prefix = {"NETWORK_PROBE_"},
                value = {
                        NETWORK_PROBE_DNS,
                        NETWORK_PROBE_HTTP,
                        NETWORK_PROBE_HTTPS,
                        NETWORK_PROBE_FALLBACK,
                        NETWORK_PROBE_PRIVATE_DNS
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface NetworkProbe {}

        /**
         * A bitmask of network validation probes that succeeded.
         *
         * <p>The possible bits values reported by this key are:
         * {@link #NETWORK_PROBE_DNS},
         * {@link #NETWORK_PROBE_HTTP},
         * {@link #NETWORK_PROBE_HTTPS},
         * {@link #NETWORK_PROBE_FALLBACK},
         * {@link #NETWORK_PROBE_PRIVATE_DNS}.
         */
        @NetworkProbe
        public static final String KEY_NETWORK_PROBES_SUCCEEDED_BITMASK =
                "networkProbesSucceeded";

        /**
         * A bitmask of network validation probes that were attempted.
         *
         * <p>These probes may have failed or may be incomplete at the time of this report.
         *
         * <p>The possible bits values reported by this key are:
         * {@link #NETWORK_PROBE_DNS},
         * {@link #NETWORK_PROBE_HTTP},
         * {@link #NETWORK_PROBE_HTTPS},
         * {@link #NETWORK_PROBE_FALLBACK},
         * {@link #NETWORK_PROBE_PRIVATE_DNS}.
         */
        @NetworkProbe
        public static final String KEY_NETWORK_PROBES_ATTEMPTED_BITMASK =
                "networkProbesAttempted";

        /** @hide */
        @StringDef(prefix = {"KEY_"}, value = {
                KEY_NETWORK_VALIDATION_RESULT, KEY_NETWORK_PROBES_SUCCEEDED_BITMASK,
                KEY_NETWORK_PROBES_ATTEMPTED_BITMASK})
        @Retention(RetentionPolicy.SOURCE)
        public @interface ConnectivityReportBundleKeys {}

        /** The Network for which this ConnectivityReport applied */
        @NonNull private final Network mNetwork;

        /**
         * The timestamp for the report. The timestamp is taken from {@link
         * System#currentTimeMillis}.
         */
        private final long mReportTimestamp;

        /** LinkProperties available on the Network at the reported timestamp */
        @NonNull private final LinkProperties mLinkProperties;

        /** NetworkCapabilities available on the Network at the reported timestamp */
        @NonNull private final NetworkCapabilities mNetworkCapabilities;

        /** PersistableBundle that may contain additional info about the report */
        @NonNull private final PersistableBundle mAdditionalInfo;

        /**
         * Constructor for ConnectivityReport.
         *
         * <p>Apps should obtain instances through {@link
         * ConnectivityDiagnosticsCallback#onConnectivityReportAvailable} instead of instantiating
         * their own instances (unless for testing purposes).
         *
         * @param network The Network for which this ConnectivityReport applies
         * @param reportTimestamp The timestamp for the report
         * @param linkProperties The LinkProperties available on network at reportTimestamp
         * @param networkCapabilities The NetworkCapabilities available on network at
         *     reportTimestamp
         * @param additionalInfo A PersistableBundle that may contain additional info about the
         *     report
         */
        public ConnectivityReport(
                @NonNull Network network,
                long reportTimestamp,
                @NonNull LinkProperties linkProperties,
                @NonNull NetworkCapabilities networkCapabilities,
                @NonNull PersistableBundle additionalInfo) {
            mNetwork = network;
            mReportTimestamp = reportTimestamp;
            mLinkProperties = new LinkProperties(linkProperties);
            mNetworkCapabilities = new NetworkCapabilities(networkCapabilities);
            mAdditionalInfo = additionalInfo;
        }

        /**
         * Returns the Network for this ConnectivityReport.
         *
         * @return The Network for which this ConnectivityReport applied
         */
        @NonNull
        public Network getNetwork() {
            return mNetwork;
        }

        /**
         * Returns the epoch timestamp (milliseconds) for when this report was taken.
         *
         * @return The timestamp for the report. Taken from {@link System#currentTimeMillis}.
         */
        public long getReportTimestamp() {
            return mReportTimestamp;
        }

        /**
         * Returns the LinkProperties available when this report was taken.
         *
         * @return LinkProperties available on the Network at the reported timestamp
         */
        @NonNull
        public LinkProperties getLinkProperties() {
            return new LinkProperties(mLinkProperties);
        }

        /**
         * Returns the NetworkCapabilities when this report was taken.
         *
         * @return NetworkCapabilities available on the Network at the reported timestamp
         */
        @NonNull
        public NetworkCapabilities getNetworkCapabilities() {
            return new NetworkCapabilities(mNetworkCapabilities);
        }

        /**
         * Returns a PersistableBundle with additional info for this report.
         *
         * @return PersistableBundle that may contain additional info about the report
         */
        @NonNull
        public PersistableBundle getAdditionalInfo() {
            return new PersistableBundle(mAdditionalInfo);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (!(o instanceof ConnectivityReport)) return false;
            final ConnectivityReport that = (ConnectivityReport) o;

            // PersistableBundle is optimized to avoid unparcelling data unless fields are
            // referenced. Because of this, use {@link ConnectivityDiagnosticsManager#equals} over
            // {@link PersistableBundle#kindofEquals}.
            return mReportTimestamp == that.mReportTimestamp
                    && mNetwork.equals(that.mNetwork)
                    && mLinkProperties.equals(that.mLinkProperties)
                    && mNetworkCapabilities.equals(that.mNetworkCapabilities)
                    && persistableBundleEquals(mAdditionalInfo, that.mAdditionalInfo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    mNetwork,
                    mReportTimestamp,
                    mLinkProperties,
                    mNetworkCapabilities,
                    mAdditionalInfo);
        }

        /** {@inheritDoc} */
        @Override
        public int describeContents() {
            return 0;
        }

        /** {@inheritDoc} */
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeParcelable(mNetwork, flags);
            dest.writeLong(mReportTimestamp);
            dest.writeParcelable(mLinkProperties, flags);
            dest.writeParcelable(mNetworkCapabilities, flags);
            dest.writeParcelable(mAdditionalInfo, flags);
        }

        /** Implement the Parcelable interface */
        public static final @NonNull Creator<ConnectivityReport> CREATOR =
                new Creator<ConnectivityReport>() {
                    public ConnectivityReport createFromParcel(Parcel in) {
                        return new ConnectivityReport(
                                in.readParcelable(null),
                                in.readLong(),
                                in.readParcelable(null),
                                in.readParcelable(null),
                                in.readParcelable(null));
                    }

                    public ConnectivityReport[] newArray(int size) {
                        return new ConnectivityReport[size];
                    }
                };
    }

    /** Class that includes information for a suspected data stall on a specific Network */
    public static final class DataStallReport implements Parcelable {
        /**
         * Indicates that the Data Stall was detected using DNS events.
         */
        public static final int DETECTION_METHOD_DNS_EVENTS = 1;

        /**
         * Indicates that the Data Stall was detected using TCP metrics.
         */
        public static final int DETECTION_METHOD_TCP_METRICS = 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"DETECTION_METHOD_"},
                value = {DETECTION_METHOD_DNS_EVENTS, DETECTION_METHOD_TCP_METRICS})
        public @interface DetectionMethod {}

        /**
         * This key represents the period in milliseconds over which other included TCP metrics
         * were measured.
         *
         * <p>This key will be included if the data stall detection method is
         * {@link #DETECTION_METHOD_TCP_METRICS}.
         *
         * <p>This value is an int.
         */
        public static final String KEY_TCP_METRICS_COLLECTION_PERIOD_MILLIS =
                "tcpMetricsCollectionPeriodMillis";

        /**
         * This key represents the fail rate of TCP packets when the suspected data stall was
         * detected.
         *
         * <p>This key will be included if the data stall detection method is
         * {@link #DETECTION_METHOD_TCP_METRICS}.
         *
         * <p>This value is an int percentage between 0 and 100.
         */
        public static final String KEY_TCP_PACKET_FAIL_RATE = "tcpPacketFailRate";

        /**
         * This key represents the consecutive number of DNS timeouts that have occurred.
         *
         * <p>The consecutive count will be reset any time a DNS response is received.
         *
         * <p>This key will be included if the data stall detection method is
         * {@link #DETECTION_METHOD_DNS_EVENTS}.
         *
         * <p>This value is an int.
         */
        public static final String KEY_DNS_CONSECUTIVE_TIMEOUTS = "dnsConsecutiveTimeouts";

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @StringDef(prefix = {"KEY_"}, value = {
                KEY_TCP_PACKET_FAIL_RATE,
                KEY_DNS_CONSECUTIVE_TIMEOUTS
        })
        public @interface DataStallReportBundleKeys {}

        /** The Network for which this DataStallReport applied */
        @NonNull private final Network mNetwork;

        /**
         * The timestamp for the report. The timestamp is taken from {@link
         * System#currentTimeMillis}.
         */
        private long mReportTimestamp;

        /** The detection method used to identify the suspected data stall */
        @DetectionMethod private final int mDetectionMethod;

        /** LinkProperties available on the Network at the reported timestamp */
        @NonNull private final LinkProperties mLinkProperties;

        /** NetworkCapabilities available on the Network at the reported timestamp */
        @NonNull private final NetworkCapabilities mNetworkCapabilities;

        /** PersistableBundle that may contain additional information on the suspected data stall */
        @NonNull private final PersistableBundle mStallDetails;

        /**
         * Constructor for DataStallReport.
         *
         * <p>Apps should obtain instances through {@link
         * ConnectivityDiagnosticsCallback#onDataStallSuspected} instead of instantiating their own
         * instances (unless for testing purposes).
         *
         * @param network The Network for which this DataStallReport applies
         * @param reportTimestamp The timestamp for the report
         * @param detectionMethod The detection method used to identify this data stall
         * @param linkProperties The LinkProperties available on network at reportTimestamp
         * @param networkCapabilities The NetworkCapabilities available on network at
         *     reportTimestamp
         * @param stallDetails A PersistableBundle that may contain additional info about the report
         */
        public DataStallReport(
                @NonNull Network network,
                long reportTimestamp,
                @DetectionMethod int detectionMethod,
                @NonNull LinkProperties linkProperties,
                @NonNull NetworkCapabilities networkCapabilities,
                @NonNull PersistableBundle stallDetails) {
            mNetwork = network;
            mReportTimestamp = reportTimestamp;
            mDetectionMethod = detectionMethod;
            mLinkProperties = new LinkProperties(linkProperties);
            mNetworkCapabilities = new NetworkCapabilities(networkCapabilities);
            mStallDetails = stallDetails;
        }

        /**
         * Returns the Network for this DataStallReport.
         *
         * @return The Network for which this DataStallReport applied
         */
        @NonNull
        public Network getNetwork() {
            return mNetwork;
        }

        /**
         * Returns the epoch timestamp (milliseconds) for when this report was taken.
         *
         * @return The timestamp for the report. Taken from {@link System#currentTimeMillis}.
         */
        public long getReportTimestamp() {
            return mReportTimestamp;
        }

        /**
         * Returns the detection method used to identify this suspected data stall.
         *
         * @return The detection method used to identify the suspected data stall
         */
        public int getDetectionMethod() {
            return mDetectionMethod;
        }

        /**
         * Returns the LinkProperties available when this report was taken.
         *
         * @return LinkProperties available on the Network at the reported timestamp
         */
        @NonNull
        public LinkProperties getLinkProperties() {
            return new LinkProperties(mLinkProperties);
        }

        /**
         * Returns the NetworkCapabilities when this report was taken.
         *
         * @return NetworkCapabilities available on the Network at the reported timestamp
         */
        @NonNull
        public NetworkCapabilities getNetworkCapabilities() {
            return new NetworkCapabilities(mNetworkCapabilities);
        }

        /**
         * Returns a PersistableBundle with additional info for this report.
         *
         * <p>Gets a bundle with details about the suspected data stall including information
         * specific to the monitoring method that detected the data stall.
         *
         * @return PersistableBundle that may contain additional information on the suspected data
         *     stall
         */
        @NonNull
        public PersistableBundle getStallDetails() {
            return new PersistableBundle(mStallDetails);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (!(o instanceof DataStallReport)) return false;
            final DataStallReport that = (DataStallReport) o;

            // PersistableBundle is optimized to avoid unparcelling data unless fields are
            // referenced. Because of this, use {@link ConnectivityDiagnosticsManager#equals} over
            // {@link PersistableBundle#kindofEquals}.
            return mReportTimestamp == that.mReportTimestamp
                    && mDetectionMethod == that.mDetectionMethod
                    && mNetwork.equals(that.mNetwork)
                    && mLinkProperties.equals(that.mLinkProperties)
                    && mNetworkCapabilities.equals(that.mNetworkCapabilities)
                    && persistableBundleEquals(mStallDetails, that.mStallDetails);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    mNetwork,
                    mReportTimestamp,
                    mDetectionMethod,
                    mLinkProperties,
                    mNetworkCapabilities,
                    mStallDetails);
        }

        /** {@inheritDoc} */
        @Override
        public int describeContents() {
            return 0;
        }

        /** {@inheritDoc} */
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeParcelable(mNetwork, flags);
            dest.writeLong(mReportTimestamp);
            dest.writeInt(mDetectionMethod);
            dest.writeParcelable(mLinkProperties, flags);
            dest.writeParcelable(mNetworkCapabilities, flags);
            dest.writeParcelable(mStallDetails, flags);
        }

        /** Implement the Parcelable interface */
        public static final @NonNull Creator<DataStallReport> CREATOR =
                new Creator<DataStallReport>() {
                    public DataStallReport createFromParcel(Parcel in) {
                        return new DataStallReport(
                                in.readParcelable(null),
                                in.readLong(),
                                in.readInt(),
                                in.readParcelable(null),
                                in.readParcelable(null),
                                in.readParcelable(null));
                    }

                    public DataStallReport[] newArray(int size) {
                        return new DataStallReport[size];
                    }
                };
    }

    /** @hide */
    @VisibleForTesting
    public static class ConnectivityDiagnosticsBinder
            extends IConnectivityDiagnosticsCallback.Stub {
        @NonNull private final ConnectivityDiagnosticsCallback mCb;
        @NonNull private final Executor mExecutor;

        /** @hide */
        @VisibleForTesting
        public ConnectivityDiagnosticsBinder(
                @NonNull ConnectivityDiagnosticsCallback cb, @NonNull Executor executor) {
            this.mCb = cb;
            this.mExecutor = executor;
        }

        /** @hide */
        @VisibleForTesting
        public void onConnectivityReportAvailable(@NonNull ConnectivityReport report) {
            Binder.withCleanCallingIdentity(() -> {
                mExecutor.execute(() -> {
                    mCb.onConnectivityReportAvailable(report);
                });
            });
        }

        /** @hide */
        @VisibleForTesting
        public void onDataStallSuspected(@NonNull DataStallReport report) {
            Binder.withCleanCallingIdentity(() -> {
                mExecutor.execute(() -> {
                    mCb.onDataStallSuspected(report);
                });
            });
        }

        /** @hide */
        @VisibleForTesting
        public void onNetworkConnectivityReported(
                @NonNull Network network, boolean hasConnectivity) {
            Binder.withCleanCallingIdentity(() -> {
                mExecutor.execute(() -> {
                    mCb.onNetworkConnectivityReported(network, hasConnectivity);
                });
            });
        }
    }

    /**
     * Abstract base class for Connectivity Diagnostics callbacks. Used for notifications about
     * network connectivity events. Must be extended by applications wanting notifications.
     */
    public abstract static class ConnectivityDiagnosticsCallback {
        /**
         * Called when the platform completes a data connectivity check. This will also be invoked
         * immediately upon registration with the latest report, if a report has already been
         * generated for this network.
         *
         * <p>The Network specified in the ConnectivityReport may not be active any more when this
         * method is invoked.
         *
         * @param report The ConnectivityReport containing information about a connectivity check
         */
        public void onConnectivityReportAvailable(@NonNull ConnectivityReport report) {}

        /**
         * Called when the platform suspects a data stall on some Network.
         *
         * <p>The Network specified in the DataStallReport may not be active any more when this
         * method is invoked.
         *
         * @param report The DataStallReport containing information about the suspected data stall
         */
        public void onDataStallSuspected(@NonNull DataStallReport report) {}

        /**
         * Called when any app reports connectivity to the System.
         *
         * @param network The Network for which connectivity has been reported
         * @param hasConnectivity The connectivity reported to the System
         */
        public void onNetworkConnectivityReported(
                @NonNull Network network, boolean hasConnectivity) {}
    }

    /**
     * Registers a ConnectivityDiagnosticsCallback with the System.
     *
     * <p>Only apps that offer network connectivity to the user should be registering callbacks.
     * These are the only apps whose callbacks will be invoked by the system. Apps considered to
     * meet these conditions include:
     *
     * <ul>
     *   <li>Carrier apps with active subscriptions
     *   <li>Active VPNs
     *   <li>WiFi Suggesters
     * </ul>
     *
     * <p>Callbacks registered by apps not meeting the above criteria will not be invoked.
     *
     * <p>If a registering app loses its relevant permissions, any callbacks it registered will
     * silently stop receiving callbacks.
     *
     * <p>Each register() call <b>MUST</b> use a ConnectivityDiagnosticsCallback instance that is
     * not currently registered. If a ConnectivityDiagnosticsCallback instance is registered with
     * multiple NetworkRequests, an IllegalArgumentException will be thrown.
     *
     * @param request The NetworkRequest that will be used to match with Networks for which
     *     callbacks will be fired
     * @param e The Executor to be used for running the callback method invocations
     * @param callback The ConnectivityDiagnosticsCallback that the caller wants registered with the
     *     System
     * @throws IllegalArgumentException if the same callback instance is registered with multiple
     *     NetworkRequests
     */
    public void registerConnectivityDiagnosticsCallback(
            @NonNull NetworkRequest request,
            @NonNull Executor e,
            @NonNull ConnectivityDiagnosticsCallback callback) {
        final ConnectivityDiagnosticsBinder binder = new ConnectivityDiagnosticsBinder(callback, e);
        if (sCallbacks.putIfAbsent(callback, binder) != null) {
            throw new IllegalArgumentException("Callback is currently registered");
        }

        try {
            mService.registerConnectivityDiagnosticsCallback(
                    binder, request, mContext.getOpPackageName());
        } catch (RemoteException exception) {
            exception.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a ConnectivityDiagnosticsCallback with the System.
     *
     * <p>If the given callback is not currently registered with the System, this operation will be
     * a no-op.
     *
     * @param callback The ConnectivityDiagnosticsCallback to be unregistered from the System.
     */
    public void unregisterConnectivityDiagnosticsCallback(
            @NonNull ConnectivityDiagnosticsCallback callback) {
        // unconditionally removing from sCallbacks prevents race conditions here, since remove() is
        // atomic.
        final ConnectivityDiagnosticsBinder binder = sCallbacks.remove(callback);
        if (binder == null) return;

        try {
            mService.unregisterConnectivityDiagnosticsCallback(binder);
        } catch (RemoteException exception) {
            exception.rethrowFromSystemServer();
        }
    }
}
