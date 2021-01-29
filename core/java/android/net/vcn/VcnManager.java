/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.net.vcn;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * VcnManager publishes APIs for applications to configure and manage Virtual Carrier Networks.
 *
 * <p>A VCN creates a virtualization layer to allow MVNOs to aggregate heterogeneous physical
 * networks, unifying them as a single carrier network. This enables infrastructure flexibility on
 * the part of MVNOs without impacting user connectivity, abstracting the physical network
 * technologies as an implementation detail of their public network.
 *
 * <p>Each VCN virtualizes an Carrier's network by building tunnels to a carrier's core network over
 * carrier-managed physical links and supports a IP mobility layer to ensure seamless transitions
 * between the underlying networks. Each VCN is configured based on a Subscription Group (see {@link
 * android.telephony.SubscriptionManager}) and aggregates all networks that are brought up based on
 * a profile or suggestion in the specified Subscription Group.
 *
 * <p>The VCN can be configured to expose one or more {@link android.net.Network}(s), each with
 * different capabilities, allowing for APN virtualization.
 *
 * <p>If a tunnel fails to connect, or otherwise encounters a fatal error, the VCN will attempt to
 * reestablish the connection. If the tunnel still has not reconnected after a system-determined
 * timeout, the VCN Safe Mode (see below) will be entered.
 *
 * <p>The VCN Safe Mode ensures that users (and carriers) have a fallback to restore system
 * connectivity to update profiles, diagnose issues, contact support, or perform other remediation
 * tasks. In Safe Mode, the system will allow underlying cellular networks to be used as default.
 * Additionally, during Safe Mode, the VCN will continue to retry the connections, and will
 * automatically exit Safe Mode if all active tunnels connect successfully.
 *
 * @hide
 */
@SystemService(Context.VCN_MANAGEMENT_SERVICE)
public final class VcnManager {
    @NonNull private static final String TAG = VcnManager.class.getSimpleName();

    /** @hide */
    @VisibleForTesting
    public static final Map<
                    VcnUnderlyingNetworkPolicyListener, VcnUnderlyingNetworkPolicyListenerBinder>
            REGISTERED_POLICY_LISTENERS = new ConcurrentHashMap<>();

    @NonNull private final Context mContext;
    @NonNull private final IVcnManagementService mService;

    /**
     * Construct an instance of VcnManager within an application context.
     *
     * @param ctx the application context for this manager
     * @param service the VcnManagementService binder backing this manager
     *
     * @hide
     */
    public VcnManager(@NonNull Context ctx, @NonNull IVcnManagementService service) {
        mContext = requireNonNull(ctx, "missing context");
        mService = requireNonNull(service, "missing service");
    }

    // TODO: Make setVcnConfig(), clearVcnConfig() Public API
    /**
     * Sets the VCN configuration for a given subscription group.
     *
     * <p>An app that has carrier privileges for any of the subscriptions in the given group may set
     * a VCN configuration. If a configuration already exists for the given subscription group, it
     * will be overridden. Any active VCN(s) may be forced to restart to use the new configuration.
     *
     * <p>This API is ONLY permitted for callers running as the primary user.
     *
     * @param subscriptionGroup the subscription group that the configuration should be applied to
     * @param config the configuration parameters for the VCN
     * @throws SecurityException if the caller does not have carrier privileges, or is not running
     *     as the primary user
     * @throws IOException if the configuration failed to be persisted. A caller encountering this
     *     exception should attempt to retry (possibly after a delay).
     * @hide
     */
    @RequiresPermission("carrier privileges") // TODO (b/72967236): Define a system-wide constant
    public void setVcnConfig(@NonNull ParcelUuid subscriptionGroup, @NonNull VcnConfig config)
            throws IOException {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");
        requireNonNull(config, "config was null");

        try {
            mService.setVcnConfig(subscriptionGroup, config, mContext.getOpPackageName());
        } catch (ServiceSpecificException e) {
            throw new IOException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO: Make setVcnConfig(), clearVcnConfig() Public API
    /**
     * Clears the VCN configuration for a given subscription group.
     *
     * <p>An app that has carrier privileges for any of the subscriptions in the given group may
     * clear a VCN configuration. This API is ONLY permitted for callers running as the primary
     * user. Any active VCN will be torn down.
     *
     * @param subscriptionGroup the subscription group that the configuration should be applied to
     * @throws SecurityException if the caller does not have carrier privileges, or is not running
     *     as the primary user
     * @throws IOException if the configuration failed to be cleared. A caller encountering this
     *     exception should attempt to retry (possibly after a delay).
     * @hide
     */
    @RequiresPermission("carrier privileges") // TODO (b/72967236): Define a system-wide constant
    public void clearVcnConfig(@NonNull ParcelUuid subscriptionGroup) throws IOException {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");

        try {
            mService.clearVcnConfig(subscriptionGroup);
        } catch (ServiceSpecificException e) {
            throw new IOException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO: make VcnUnderlyingNetworkPolicyListener @SystemApi
    /**
     * VcnUnderlyingNetworkPolicyListener is the interface through which internal system components
     * can register to receive updates for VCN-underlying Network policies from the System Server.
     *
     * @hide
     */
    public interface VcnUnderlyingNetworkPolicyListener {
        /**
         * Notifies the implementation that the VCN's underlying Network policy has changed.
         *
         * <p>After receiving this callback, implementations MUST poll VcnManager for the updated
         * VcnUnderlyingNetworkPolicy via VcnManager#getUnderlyingNetworkPolicy.
         */
        void onPolicyChanged();
    }

    /**
     * Add a listener for VCN-underlying network policy updates.
     *
     * @param executor the Executor that will be used for invoking all calls to the specified
     *     Listener
     * @param listener the VcnUnderlyingNetworkPolicyListener to be added
     * @throws SecurityException if the caller does not have permission NETWORK_FACTORY
     * @throws IllegalArgumentException if the specified VcnUnderlyingNetworkPolicyListener is
     *     already registered
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void addVcnUnderlyingNetworkPolicyListener(
            @NonNull Executor executor, @NonNull VcnUnderlyingNetworkPolicyListener listener) {
        requireNonNull(executor, "executor must not be null");
        requireNonNull(listener, "listener must not be null");

        VcnUnderlyingNetworkPolicyListenerBinder binder =
                new VcnUnderlyingNetworkPolicyListenerBinder(executor, listener);
        if (REGISTERED_POLICY_LISTENERS.putIfAbsent(listener, binder) != null) {
            throw new IllegalArgumentException(
                    "Attempting to add a listener that is already in use");
        }

        try {
            mService.addVcnUnderlyingNetworkPolicyListener(binder);
        } catch (RemoteException e) {
            REGISTERED_POLICY_LISTENERS.remove(listener);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the specified VcnUnderlyingNetworkPolicyListener from VcnManager.
     *
     * <p>If the specified listener is not currently registered, this is a no-op.
     *
     * @param listener the VcnUnderlyingNetworkPolicyListener that will be removed
     * @hide
     */
    public void removeVcnUnderlyingNetworkPolicyListener(
            @NonNull VcnUnderlyingNetworkPolicyListener listener) {
        requireNonNull(listener, "listener must not be null");

        VcnUnderlyingNetworkPolicyListenerBinder binder =
                REGISTERED_POLICY_LISTENERS.remove(listener);
        if (binder == null) {
            return;
        }

        try {
            mService.removeVcnUnderlyingNetworkPolicyListener(binder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Binder wrapper for added VcnUnderlyingNetworkPolicyListeners to receive signals from System
     * Server.
     *
     * @hide
     */
    private static class VcnUnderlyingNetworkPolicyListenerBinder
            extends IVcnUnderlyingNetworkPolicyListener.Stub {
        @NonNull private final Executor mExecutor;
        @NonNull private final VcnUnderlyingNetworkPolicyListener mListener;

        private VcnUnderlyingNetworkPolicyListenerBinder(
                Executor executor, VcnUnderlyingNetworkPolicyListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onPolicyChanged() {
            mExecutor.execute(() -> mListener.onPolicyChanged());
        }
    }
}
