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
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    public static final int DETECTION_METHOD_DNS_EVENTS = 1;
    public static final int DETECTION_METHOD_TCP_METRICS = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"DETECTION_METHOD_"},
            value = {DETECTION_METHOD_DNS_EVENTS, DETECTION_METHOD_TCP_METRICS})
    public @interface DetectionMethod {}

    /** @hide */
    public ConnectivityDiagnosticsManager() {}

    /** Class that includes connectivity information for a specific Network at a specific time. */
    public static class ConnectivityReport {
        /** The Network for which this ConnectivityReport applied */
        @NonNull public final Network network;

        /**
         * The timestamp for the report. The timestamp is taken from {@link
         * System#currentTimeMillis}.
         */
        public final long reportTimestamp;

        /** LinkProperties available on the Network at the reported timestamp */
        @NonNull public final LinkProperties linkProperties;

        /** NetworkCapabilities available on the Network at the reported timestamp */
        @NonNull public final NetworkCapabilities networkCapabilities;

        /** PersistableBundle that may contain additional info about the report */
        @NonNull public final PersistableBundle additionalInfo;

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
            this.network = network;
            this.reportTimestamp = reportTimestamp;
            this.linkProperties = linkProperties;
            this.networkCapabilities = networkCapabilities;
            this.additionalInfo = additionalInfo;
        }
    }

    /** Class that includes information for a suspected data stall on a specific Network */
    public static class DataStallReport {
        /** The Network for which this DataStallReport applied */
        @NonNull public final Network network;

        /**
         * The timestamp for the report. The timestamp is taken from {@link
         * System#currentTimeMillis}.
         */
        public final long reportTimestamp;

        /** The detection method used to identify the suspected data stall */
        @DetectionMethod public final int detectionMethod;

        /** PersistableBundle that may contain additional information on the suspected data stall */
        @NonNull public final PersistableBundle stallDetails;

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
            this.network = network;
            this.reportTimestamp = reportTimestamp;
            this.detectionMethod = detectionMethod;
            this.stallDetails = stallDetails;
        }
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
