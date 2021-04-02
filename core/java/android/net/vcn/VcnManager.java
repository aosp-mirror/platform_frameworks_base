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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * VcnManager publishes APIs for applications to configure and manage Virtual Carrier Networks.
 *
 * <p>A VCN creates a virtualization layer to allow carriers to aggregate heterogeneous physical
 * networks, unifying them as a single carrier network. This enables infrastructure flexibility on
 * the part of carriers without impacting user connectivity, abstracting the physical network
 * technologies as an implementation detail of their public network.
 *
 * <p>Each VCN virtualizes a carrier's network by building tunnels to a carrier's core network over
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
 */
@SystemService(Context.VCN_MANAGEMENT_SERVICE)
public class VcnManager {
    @NonNull private static final String TAG = VcnManager.class.getSimpleName();

    private static final Map<
                    VcnNetworkPolicyChangeListener, VcnUnderlyingNetworkPolicyListenerBinder>
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

    /**
     * Get all currently registered VcnNetworkPolicyChangeListeners for testing purposes.
     *
     * @hide
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @NonNull
    public static Map<VcnNetworkPolicyChangeListener, VcnUnderlyingNetworkPolicyListenerBinder>
            getAllPolicyListeners() {
        return Collections.unmodifiableMap(REGISTERED_POLICY_LISTENERS);
    }

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
     * @throws SecurityException if the caller does not have carrier privileges for the provided
     *     subscriptionGroup, or is not running as the primary user
     * @throws IOException if the configuration failed to be saved and persisted to disk. This may
     *     occur due to temporary disk errors, or more permanent conditions such as a full disk.
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
     * @throws IOException if the configuration failed to be cleared from disk. This may occur due
     *     to temporary disk errors, or more permanent conditions such as a full disk.
     */
    @RequiresPermission("carrier privileges") // TODO (b/72967236): Define a system-wide constant
    public void clearVcnConfig(@NonNull ParcelUuid subscriptionGroup) throws IOException {
        requireNonNull(subscriptionGroup, "subscriptionGroup was null");

        try {
            mService.clearVcnConfig(subscriptionGroup, mContext.getOpPackageName());
        } catch (ServiceSpecificException e) {
            throw new IOException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/180537630): remove all VcnUnderlyingNetworkPolicyListener refs once Telephony is using
    // the new VcnNetworkPolicyChangeListener API
    /**
     * VcnUnderlyingNetworkPolicyListener is the interface through which internal system components
     * can register to receive updates for VCN-underlying Network policies from the System Server.
     *
     * @hide
     */
    public interface VcnUnderlyingNetworkPolicyListener extends VcnNetworkPolicyChangeListener {}

    /**
     * Add a listener for VCN-underlying network policy updates.
     *
     * @param executor the Executor that will be used for invoking all calls to the specified
     *     Listener
     * @param listener the VcnUnderlyingNetworkPolicyListener to be added
     * @throws SecurityException if the caller does not have permission NETWORK_FACTORY
     * @throws IllegalStateException if the specified VcnUnderlyingNetworkPolicyListener is already
     *     registered
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void addVcnUnderlyingNetworkPolicyListener(
            @NonNull Executor executor, @NonNull VcnUnderlyingNetworkPolicyListener listener) {
        addVcnNetworkPolicyChangeListener(executor, listener);
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
        removeVcnNetworkPolicyChangeListener(listener);
    }

    /**
     * Queries the underlying network policy for a network with the given parameters.
     *
     * <p>Prior to a new NetworkAgent being registered, or upon notification that Carrier VCN policy
     * may have changed via {@link VcnUnderlyingNetworkPolicyListener#onPolicyChanged()}, a Network
     * Provider MUST poll for the updated Network policy based on that Network's capabilities and
     * properties.
     *
     * @param networkCapabilities the NetworkCapabilities to be used in determining the Network
     *     policy for this Network.
     * @param linkProperties the LinkProperties to be used in determining the Network policy for
     *     this Network.
     * @throws SecurityException if the caller does not have permission NETWORK_FACTORY
     * @return the VcnUnderlyingNetworkPolicy to be used for this Network.
     * @hide
     */
    @NonNull
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public VcnUnderlyingNetworkPolicy getUnderlyingNetworkPolicy(
            @NonNull NetworkCapabilities networkCapabilities,
            @NonNull LinkProperties linkProperties) {
        requireNonNull(networkCapabilities, "networkCapabilities must not be null");
        requireNonNull(linkProperties, "linkProperties must not be null");

        try {
            return mService.getUnderlyingNetworkPolicy(networkCapabilities, linkProperties);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * VcnNetworkPolicyChangeListener is the interface through which internal system components
     * (e.g. Network Factories) can register to receive updates for VCN-underlying Network policies
     * from the System Server.
     *
     * <p>Any Network Factory that brings up Networks capable of being VCN-underlying Networks
     * should register a VcnNetworkPolicyChangeListener. VcnManager will then use this listener to
     * notify the registrant when VCN Network policies change. Upon receiving this signal, the
     * listener must check {@link VcnManager} for the current Network policy result for each of its
     * Networks via {@link #applyVcnNetworkPolicy(NetworkCapabilities, LinkProperties)}.
     *
     * @hide
     */
    @SystemApi
    public interface VcnNetworkPolicyChangeListener {
        /**
         * Notifies the implementation that the VCN's underlying Network policy has changed.
         *
         * <p>After receiving this callback, implementations should get the current {@link
         * VcnNetworkPolicyResult} via {@link #applyVcnNetworkPolicy(NetworkCapabilities,
         * LinkProperties)}.
         */
        void onPolicyChanged();
    }

    /**
     * Add a listener for VCN-underlying Network policy updates.
     *
     * <p>A {@link VcnNetworkPolicyChangeListener} is eligible to begin receiving callbacks once it
     * is registered. No callbacks are guaranteed upon registration.
     *
     * @param executor the Executor that will be used for invoking all calls to the specified
     *     Listener
     * @param listener the VcnNetworkPolicyChangeListener to be added
     * @throws SecurityException if the caller does not have permission NETWORK_FACTORY
     * @throws IllegalStateException if the specified VcnNetworkPolicyChangeListener is already
     *     registered
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void addVcnNetworkPolicyChangeListener(
            @NonNull Executor executor, @NonNull VcnNetworkPolicyChangeListener listener) {
        requireNonNull(executor, "executor must not be null");
        requireNonNull(listener, "listener must not be null");

        VcnUnderlyingNetworkPolicyListenerBinder binder =
                new VcnUnderlyingNetworkPolicyListenerBinder(executor, listener);
        if (REGISTERED_POLICY_LISTENERS.putIfAbsent(listener, binder) != null) {
            throw new IllegalStateException("listener is already registered with VcnManager");
        }

        try {
            mService.addVcnUnderlyingNetworkPolicyListener(binder);
        } catch (RemoteException e) {
            REGISTERED_POLICY_LISTENERS.remove(listener);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the specified VcnNetworkPolicyChangeListener from VcnManager.
     *
     * <p>If the specified listener is not currently registered, this is a no-op.
     *
     * @param listener the VcnNetworkPolicyChangeListener that will be removed
     * @throws SecurityException if the caller does not have permission NETWORK_FACTORY
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public void removeVcnNetworkPolicyChangeListener(
            @NonNull VcnNetworkPolicyChangeListener listener) {
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
     * Applies the network policy for a {@link android.net.Network} with the given parameters.
     *
     * <p>Prior to a new NetworkAgent being registered, or upon notification that Carrier VCN policy
     * may have changed via {@link VcnNetworkPolicyChangeListener#onPolicyChanged()}, a Network
     * Provider MUST poll for the updated Network policy based on that Network's capabilities and
     * properties.
     *
     * @param networkCapabilities the NetworkCapabilities to be used in determining the Network
     *     policy result for this Network.
     * @param linkProperties the LinkProperties to be used in determining the Network policy result
     *     for this Network.
     * @throws SecurityException if the caller does not have permission NETWORK_FACTORY
     * @return the {@link VcnNetworkPolicyResult} to be used for this Network.
     * @hide
     */
    @NonNull
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
    public VcnNetworkPolicyResult applyVcnNetworkPolicy(
            @NonNull NetworkCapabilities networkCapabilities,
            @NonNull LinkProperties linkProperties) {
        requireNonNull(networkCapabilities, "networkCapabilities must not be null");
        requireNonNull(linkProperties, "linkProperties must not be null");

        final VcnUnderlyingNetworkPolicy policy =
                getUnderlyingNetworkPolicy(networkCapabilities, linkProperties);
        return new VcnNetworkPolicyResult(
                policy.isTeardownRequested(), policy.getMergedNetworkCapabilities());
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        VCN_STATUS_CODE_NOT_CONFIGURED,
        VCN_STATUS_CODE_INACTIVE,
        VCN_STATUS_CODE_ACTIVE,
        VCN_STATUS_CODE_SAFE_MODE
    })
    public @interface VcnStatusCode {}

    /**
     * Value indicating that the VCN for the subscription group is not configured, or that the
     * callback is not privileged for the subscription group.
     */
    public static final int VCN_STATUS_CODE_NOT_CONFIGURED = 0;

    /**
     * Value indicating that the VCN for the subscription group is inactive.
     *
     * <p>A VCN is inactive if a {@link VcnConfig} is present for the subscription group, but the
     * provisioning package is not privileged.
     */
    public static final int VCN_STATUS_CODE_INACTIVE = 1;

    /**
     * Value indicating that the VCN for the subscription group is active.
     *
     * <p>A VCN is active if a {@link VcnConfig} is present for the subscription, the provisioning
     * package is privileged, and the VCN is not in Safe Mode. In other words, a VCN is considered
     * active while it is connecting, fully connected, and disconnecting.
     */
    public static final int VCN_STATUS_CODE_ACTIVE = 2;

    /**
     * Value indicating that the VCN for the subscription group is in Safe Mode.
     *
     * <p>A VCN will be put into Safe Mode if any of the gateway connections were unable to
     * establish a connection within a system-determined timeout (while underlying networks were
     * available).
     */
    public static final int VCN_STATUS_CODE_SAFE_MODE = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        VCN_ERROR_CODE_INTERNAL_ERROR,
        VCN_ERROR_CODE_CONFIG_ERROR,
        VCN_ERROR_CODE_NETWORK_ERROR
    })
    public @interface VcnErrorCode {}

    /**
     * Value indicating that an internal failure occurred in this Gateway Connection.
     */
    public static final int VCN_ERROR_CODE_INTERNAL_ERROR = 0;

    /**
     * Value indicating that an error with this Gateway Connection's configuration occurred.
     *
     * <p>For example, this error code will be returned after authentication failures.
     */
    public static final int VCN_ERROR_CODE_CONFIG_ERROR = 1;

    /**
     * Value indicating that a Network error occurred with this Gateway Connection.
     *
     * <p>For example, this error code will be returned if an underlying {@link android.net.Network}
     * for this Gateway Connection is lost, or if an error occurs while resolving the connection
     * endpoint address.
     */
    public static final int VCN_ERROR_CODE_NETWORK_ERROR = 2;

    /**
     * VcnStatusCallback is the interface for Carrier apps to receive updates for their VCNs.
     *
     * <p>VcnStatusCallbacks may be registered before {@link VcnConfig}s are provided for a
     * subscription group.
     */
    public abstract static class VcnStatusCallback {
        private VcnStatusCallbackBinder mCbBinder;

        /**
         * Invoked when status of the VCN for this callback's subscription group changes.
         *
         * @param statusCode the code for the status change encountered by this {@link
         *     VcnStatusCallback}'s subscription group.
         */
        public abstract void onStatusChanged(@VcnStatusCode int statusCode);

        /**
         * Invoked when a VCN Gateway Connection corresponding to this callback's subscription group
         * encounters an error.
         *
         * @param gatewayConnectionName the String GatewayConnection name for the GatewayConnection
         *     encountering an error. This will match the name for exactly one {@link
         *     VcnGatewayConnectionConfig} for the {@link VcnConfig} configured for this callback's
         *     subscription group
         * @param errorCode the code to indicate the error that occurred
         * @param detail Throwable to provide additional information about the error, or {@code
         *     null} if none
         */
        public abstract void onGatewayConnectionError(
                @NonNull String gatewayConnectionName,
                @VcnErrorCode int errorCode,
                @Nullable Throwable detail);
    }

    /**
     * Registers the given callback to receive status updates for the specified subscription.
     *
     * <p>Callbacks can be registered for a subscription before {@link VcnConfig}s are set for it.
     *
     * <p>A {@link VcnStatusCallback} may only be registered for one subscription at a time. {@link
     * VcnStatusCallback}s may be reused once unregistered.
     *
     * <p>A {@link VcnStatusCallback} will only be invoked if the registering package has carrier
     * privileges for the specified subscription at the time of invocation.
     *
     * <p>A {@link VcnStatusCallback} is eligible to begin receiving callbacks once it is registered
     * and there is a VCN active for its specified subscription group (this may happen after the
     * callback is registered).
     *
     * <p>{@link VcnStatusCallback#onStatusChanged(int)} will be invoked on registration with the
     * current status for the specified subscription group's VCN. If the registrant is not
     * privileged for this subscription group, {@link #VCN_STATUS_CODE_NOT_CONFIGURED} will be
     * returned.
     *
     * @param subscriptionGroup The subscription group to match for callbacks
     * @param executor The {@link Executor} to be used for invoking callbacks
     * @param callback The VcnStatusCallback to be registered
     * @throws IllegalStateException if callback is currently registered with VcnManager
     */
    public void registerVcnStatusCallback(
            @NonNull ParcelUuid subscriptionGroup,
            @NonNull Executor executor,
            @NonNull VcnStatusCallback callback) {
        requireNonNull(subscriptionGroup, "subscriptionGroup must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        synchronized (callback) {
            if (callback.mCbBinder != null) {
                throw new IllegalStateException("callback is already registered with VcnManager");
            }
            callback.mCbBinder = new VcnStatusCallbackBinder(executor, callback);

            try {
                mService.registerVcnStatusCallback(
                        subscriptionGroup, callback.mCbBinder, mContext.getOpPackageName());
            } catch (RemoteException e) {
                callback.mCbBinder = null;
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters the given callback.
     *
     * <p>Once unregistered, the callback will stop receiving status updates for the subscription it
     * was registered with.
     *
     * @param callback The callback to be unregistered
     */
    public void unregisterVcnStatusCallback(@NonNull VcnStatusCallback callback) {
        requireNonNull(callback, "callback must not be null");

        synchronized (callback) {
            if (callback.mCbBinder == null) {
                // no Binder attached to this callback, so it's not currently registered
                return;
            }

            try {
                mService.unregisterVcnStatusCallback(callback.mCbBinder);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                callback.mCbBinder = null;
            }
        }
    }

    /**
     * Binder wrapper for added VcnNetworkPolicyChangeListeners to receive signals from System
     * Server.
     *
     * @hide
     */
    private static class VcnUnderlyingNetworkPolicyListenerBinder
            extends IVcnUnderlyingNetworkPolicyListener.Stub {
        @NonNull private final Executor mExecutor;
        @NonNull private final VcnNetworkPolicyChangeListener mListener;

        private VcnUnderlyingNetworkPolicyListenerBinder(
                Executor executor, VcnNetworkPolicyChangeListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onPolicyChanged() {
            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> mListener.onPolicyChanged()));
        }
    }

    /**
     * Binder wrapper for VcnStatusCallbacks to receive signals from VcnManagementService.
     *
     * @hide
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class VcnStatusCallbackBinder extends IVcnStatusCallback.Stub {
        @NonNull private final Executor mExecutor;
        @NonNull private final VcnStatusCallback mCallback;

        public VcnStatusCallbackBinder(
                @NonNull Executor executor, @NonNull VcnStatusCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onVcnStatusChanged(@VcnStatusCode int statusCode) {
            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> mCallback.onStatusChanged(statusCode)));
        }

        // TODO(b/180521637): use ServiceSpecificException for safer Exception 'parceling'
        @Override
        public void onGatewayConnectionError(
                @NonNull String gatewayConnectionName,
                @VcnErrorCode int errorCode,
                @Nullable String exceptionClass,
                @Nullable String exceptionMessage) {
            final Throwable cause = createThrowableByClassName(exceptionClass, exceptionMessage);

            Binder.withCleanCallingIdentity(
                    () ->
                            mExecutor.execute(
                                    () ->
                                            mCallback.onGatewayConnectionError(
                                                    gatewayConnectionName, errorCode, cause)));
        }

        private static Throwable createThrowableByClassName(
                @Nullable String className, @Nullable String message) {
            if (className == null) {
                return null;
            }

            try {
                Class<?> c = Class.forName(className);
                return (Throwable) c.getConstructor(String.class).newInstance(message);
            } catch (ReflectiveOperationException | ClassCastException e) {
                return new RuntimeException(className + ": " + message);
            }
        }
    }
}
