/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.vcn.routeselection;

import static com.android.server.VcnManagementService.LOCAL_LOG;
import static com.android.server.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.IpSecTransform;
import android.net.IpSecTransformState;
import android.net.Network;
import android.os.OutcomeReceiver;
import android.util.CloseGuard;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.VcnContext;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * NetworkMetricMonitor is responsible for managing metric monitoring and tracking validation
 * results.
 *
 * <p>This class is flag gated by "network_metric_monitor"
 */
public abstract class NetworkMetricMonitor implements AutoCloseable {
    private static final String TAG = NetworkMetricMonitor.class.getSimpleName();

    private static final boolean VDBG = false; // STOPSHIP: if true

    @NonNull private final CloseGuard mCloseGuard = new CloseGuard();

    @NonNull private final VcnContext mVcnContext;
    @NonNull private final Network mNetwork;
    @NonNull private final NetworkMetricMonitorCallback mCallback;

    private boolean mIsSelectedUnderlyingNetwork;
    private boolean mIsStarted;
    private boolean mIsValidationFailed;

    protected NetworkMetricMonitor(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @Nullable PersistableBundleWrapper carrierConfig,
            @NonNull NetworkMetricMonitorCallback callback)
            throws IllegalAccessException {
        if (!vcnContext.isFlagNetworkMetricMonitorEnabled()) {
            // Caller error
            logWtf("networkMetricMonitor flag disabled");
            throw new IllegalAccessException("networkMetricMonitor flag disabled");
        }

        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");
        mNetwork = Objects.requireNonNull(network, "Missing network");
        mCallback = Objects.requireNonNull(callback, "Missing callback");

        mIsSelectedUnderlyingNetwork = false;
        mIsStarted = false;
        mIsValidationFailed = false;
    }

    /** Callback to notify caller of the validation result */
    public interface NetworkMetricMonitorCallback {
        /** Called when there is a validation result is ready */
        void onValidationResultReceived();
    }

    /**
     * Start monitoring
     *
     * <p>This method might be called on a an already started monitor for updating monitor
     * properties (e.g. IpSecTransform, carrier config)
     *
     * <p>Subclasses MUST call super.start() when overriding this method
     */
    protected void start() {
        mIsStarted = true;
    }

    /**
     * Stop monitoring
     *
     * <p>Subclasses MUST call super.stop() when overriding this method
     */
    public void stop() {
        mIsValidationFailed = false;
        mIsStarted = false;
    }

    /** Called by the subclasses when the validation result is ready */
    protected void onValidationResultReceivedInternal(boolean isFailed) {
        mIsValidationFailed = isFailed;
        mCallback.onValidationResultReceived();
    }

    /** Called when the underlying network changes to selected or unselected */
    protected abstract void onSelectedUnderlyingNetworkChanged();

    /**
     * Mark the network being monitored selected or unselected
     *
     * <p>Subclasses MUST call super when overriding this method
     */
    public void setIsSelectedUnderlyingNetwork(boolean isSelectedUnderlyingNetwork) {
        if (mIsSelectedUnderlyingNetwork == isSelectedUnderlyingNetwork) {
            return;
        }

        mIsSelectedUnderlyingNetwork = isSelectedUnderlyingNetwork;
        onSelectedUnderlyingNetworkChanged();
    }

    /** Wrapper that allows injection for testing purposes */
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static class IpSecTransformWrapper {
        @NonNull public final IpSecTransform ipSecTransform;

        public IpSecTransformWrapper(@NonNull IpSecTransform ipSecTransform) {
            this.ipSecTransform = ipSecTransform;
        }

        /** Poll an IpSecTransformState */
        public void requestIpSecTransformState(
                @NonNull Executor executor,
                @NonNull OutcomeReceiver<IpSecTransformState, RuntimeException> callback) {
            ipSecTransform.requestIpSecTransformState(executor, callback);
        }

        /** Close this instance and release the underlying resources */
        public void close() {
            ipSecTransform.close();
        }

        @Override
        public int hashCode() {
            return Objects.hash(ipSecTransform);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IpSecTransformWrapper)) {
                return false;
            }

            final IpSecTransformWrapper other = (IpSecTransformWrapper) o;

            return Objects.equals(ipSecTransform, other.ipSecTransform);
        }
    }

    /** Set the IpSecTransform that applied to the Network being monitored */
    public void setInboundTransform(@NonNull IpSecTransform inTransform) {
        setInboundTransformInternal(new IpSecTransformWrapper(inTransform));
    }

    /**
     * Set the IpSecTransform that applied to the Network being monitored *
     *
     * <p>Subclasses MUST call super when overriding this method
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void setInboundTransformInternal(@NonNull IpSecTransformWrapper inTransform) {
        // Subclasses MUST override it if they care
    }

    /** Update the carrierconfig */
    public void setCarrierConfig(@Nullable PersistableBundleWrapper carrierConfig) {
        // Subclasses MUST override it if they care
    }

    public boolean isValidationFailed() {
        return mIsValidationFailed;
    }

    public boolean isSelectedUnderlyingNetwork() {
        return mIsSelectedUnderlyingNetwork;
    }

    public boolean isStarted() {
        return mIsStarted;
    }

    @NonNull
    public VcnContext getVcnContext() {
        return mVcnContext;
    }

    // Override methods for AutoCloseable. Subclasses MUST call super when overriding this method
    @Override
    public void close() {
        mCloseGuard.close();

        stop();
    }

    // Override #finalize() to use closeGuard for flagging that #close() was not called
    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        } finally {
            super.finalize();
        }
    }

    private String getClassName() {
        return this.getClass().getSimpleName();
    }

    protected String getLogPrefix() {
        return " [Network " + mNetwork + "] ";
    }

    protected void logV(String msg) {
        if (VDBG) {
            Slog.v(getClassName(), getLogPrefix() + msg);
            LOCAL_LOG.log("[VERBOSE ] " + getClassName() + getLogPrefix() + msg);
        }
    }

    protected void logInfo(String msg) {
        Slog.i(getClassName(), getLogPrefix() + msg);
        LOCAL_LOG.log("[INFO ] " + getClassName() + getLogPrefix() + msg);
    }

    protected void logW(String msg) {
        Slog.w(getClassName(), getLogPrefix() + msg);
        LOCAL_LOG.log("[WARN ] " + getClassName() + getLogPrefix() + msg);
    }

    protected void logWtf(String msg) {
        Slog.wtf(getClassName(), getLogPrefix() + msg);
        LOCAL_LOG.log("[WTF ] " + getClassName() + getLogPrefix() + msg);
    }

    protected static void logV(String className, String msgWithPrefix) {
        if (VDBG) {
            Slog.wtf(className, msgWithPrefix);
            LOCAL_LOG.log("[VERBOSE ] " + className + msgWithPrefix);
        }
    }

    protected static void logWtf(String className, String msgWithPrefix) {
        Slog.wtf(className, msgWithPrefix);
        LOCAL_LOG.log("[WTF ] " + className + msgWithPrefix);
    }
}
