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
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
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
         * ConnectivityDiagnosticsCallback#onConnectivityReport} instead of instantiating their own
         * instances (unless for testing purposes).
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
            mLinkProperties = linkProperties;
            mNetworkCapabilities = networkCapabilities;
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
        public static final int DETECTION_METHOD_DNS_EVENTS = 1;
        public static final int DETECTION_METHOD_TCP_METRICS = 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"DETECTION_METHOD_"},
                value = {DETECTION_METHOD_DNS_EVENTS, DETECTION_METHOD_TCP_METRICS})
        public @interface DetectionMethod {}

        /** The Network for which this DataStallReport applied */
        @NonNull private final Network mNetwork;

        /**
         * The timestamp for the report. The timestamp is taken from {@link
         * System#currentTimeMillis}.
         */
        private long mReportTimestamp;

        /** The detection method used to identify the suspected data stall */
        @DetectionMethod private final int mDetectionMethod;

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
         * @param stallDetails A PersistableBundle that may contain additional info about the report
         */
        public DataStallReport(
                @NonNull Network network,
                long reportTimestamp,
                @DetectionMethod int detectionMethod,
                @NonNull PersistableBundle stallDetails) {
            mNetwork = network;
            mReportTimestamp = reportTimestamp;
            mDetectionMethod = detectionMethod;
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
         * Returns a PersistableBundle with additional info for this report.
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
                    && persistableBundleEquals(mStallDetails, that.mStallDetails);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mNetwork, mReportTimestamp, mDetectionMethod, mStallDetails);
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
                                in.readParcelable(null));
                    }

                    public DataStallReport[] newArray(int size) {
                        return new DataStallReport[size];
                    }
                };
    }

    /**
     * Abstract base class for Connectivity Diagnostics callbacks. Used for notifications about
     * network connectivity events. Must be extended by applications wanting notifications.
     */
    public abstract static class ConnectivityDiagnosticsCallback {
        /**
         * Called when the platform completes a data connectivity check. This will also be invoked
         * upon registration with the latest report.
         *
         * <p>The Network specified in the ConnectivityReport may not be active any more when this
         * method is invoked.
         *
         * @param report The ConnectivityReport containing information about a connectivity check
         */
        public void onConnectivityReport(@NonNull ConnectivityReport report) {}

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
     * <p>Only apps that offer network connectivity to the user are allowed to register callbacks.
     * This includes:
     *
     * <ul>
     *   <li>Carrier apps with active subscriptions
     *   <li>Active VPNs
     *   <li>WiFi Suggesters
     * </ul>
     *
     * <p>Callbacks will be limited to receiving notifications for networks over which apps provide
     * connectivity.
     *
     * <p>If a registering app loses its relevant permissions, any callbacks it registered will
     * silently stop receiving callbacks.
     *
     * <p>Each register() call <b>MUST</b> use a unique ConnectivityDiagnosticsCallback instance. If
     * a single instance is registered with multiple NetworkRequests, an IllegalArgumentException
     * will be thrown.
     *
     * @param request The NetworkRequest that will be used to match with Networks for which
     *     callbacks will be fired
     * @param e The Executor to be used for running the callback method invocations
     * @param callback The ConnectivityDiagnosticsCallback that the caller wants registered with the
     *     System
     * @throws IllegalArgumentException if the same callback instance is registered with multiple
     *     NetworkRequests
     * @throws SecurityException if the caller does not have appropriate permissions to register a
     *     callback
     */
    public void registerConnectivityDiagnosticsCallback(
            @NonNull NetworkRequest request,
            @NonNull Executor e,
            @NonNull ConnectivityDiagnosticsCallback callback) {
        // TODO(b/143187964): implement ConnectivityDiagnostics functionality
        throw new UnsupportedOperationException("registerCallback() not supported yet");
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
        // TODO(b/143187964): implement ConnectivityDiagnostics functionality
        throw new UnsupportedOperationException("registerCallback() not supported yet");
    }
}
