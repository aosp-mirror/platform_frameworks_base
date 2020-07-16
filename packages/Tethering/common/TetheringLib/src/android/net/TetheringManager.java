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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * This class provides the APIs to control the tethering service.
 * <p> The primary responsibilities of this class are to provide the APIs for applications to
 * start tethering, stop tethering, query configuration and query status.
 *
 * @hide
 */
@SystemApi
@SystemApi(client = MODULE_LIBRARIES)
@TestApi
public class TetheringManager {
    private static final String TAG = TetheringManager.class.getSimpleName();
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final long CONNECTOR_POLL_INTERVAL_MILLIS = 200L;

    @GuardedBy("mConnectorWaitQueue")
    @Nullable
    private ITetheringConnector mConnector;
    @GuardedBy("mConnectorWaitQueue")
    @NonNull
    private final List<ConnectorConsumer> mConnectorWaitQueue = new ArrayList<>();
    private final Supplier<IBinder> mConnectorSupplier;

    private final TetheringCallbackInternal mCallback;
    private final Context mContext;
    private final ArrayMap<TetheringEventCallback, ITetheringEventCallback>
            mTetheringEventCallbacks = new ArrayMap<>();

    private volatile TetheringConfigurationParcel mTetheringConfiguration;
    private volatile TetherStatesParcel mTetherStatesParcel;

    /**
     * Broadcast Action: A tetherable connection has come or gone.
     * Uses {@code TetheringManager.EXTRA_AVAILABLE_TETHER},
     * {@code TetheringManager.EXTRA_ACTIVE_LOCAL_ONLY},
     * {@code TetheringManager.EXTRA_ACTIVE_TETHER}, and
     * {@code TetheringManager.EXTRA_ERRORED_TETHER} to indicate
     * the current state of tethering.  Each include a list of
     * interface names in that state (may be empty).
     */
    public static final String ACTION_TETHER_STATE_CHANGED =
            "android.net.conn.TETHER_STATE_CHANGED";

    /**
     * gives a String[] listing all the interfaces configured for
     * tethering and currently available for tethering.
     */
    public static final String EXTRA_AVAILABLE_TETHER = "availableArray";

    /**
     * gives a String[] listing all the interfaces currently in local-only
     * mode (ie, has DHCPv4+IPv6-ULA support and no packet forwarding)
     */
    public static final String EXTRA_ACTIVE_LOCAL_ONLY = "android.net.extra.ACTIVE_LOCAL_ONLY";

    /**
     * gives a String[] listing all the interfaces currently tethered
     * (ie, has DHCPv4 support and packets potentially forwarded/NATed)
     */
    public static final String EXTRA_ACTIVE_TETHER = "tetherArray";

    /**
     * gives a String[] listing all the interfaces we tried to tether and
     * failed.  Use {@link #getLastTetherError} to find the error code
     * for any interfaces listed here.
     */
    public static final String EXTRA_ERRORED_TETHER = "erroredArray";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, value = {
            TETHERING_WIFI,
            TETHERING_USB,
            TETHERING_BLUETOOTH,
            TETHERING_WIFI_P2P,
            TETHERING_NCM,
            TETHERING_ETHERNET,
    })
    public @interface TetheringType {
    }

    /**
     * Invalid tethering type.
     * @see #startTethering.
     */
    public static final int TETHERING_INVALID   = -1;

    /**
     * Wifi tethering type.
     * @see #startTethering.
     */
    public static final int TETHERING_WIFI      = 0;

    /**
     * USB tethering type.
     * @see #startTethering.
     */
    public static final int TETHERING_USB       = 1;

    /**
     * Bluetooth tethering type.
     * @see #startTethering.
     */
    public static final int TETHERING_BLUETOOTH = 2;

    /**
     * Wifi P2p tethering type.
     * Wifi P2p tethering is set through events automatically, and don't
     * need to start from #startTethering.
     */
    public static final int TETHERING_WIFI_P2P = 3;

    /**
     * Ncm local tethering type.
     * @see #startTethering(TetheringRequest, Executor, StartTetheringCallback)
     */
    public static final int TETHERING_NCM = 4;

    /**
     * Ethernet tethering type.
     * @see #startTethering(TetheringRequest, Executor, StartTetheringCallback)
     */
    public static final int TETHERING_ETHERNET = 5;

    /**
     * WIGIG tethering type. Use a separate type to prevent
     * conflicts with TETHERING_WIFI
     * This type is only used internally by the tethering module
     * @hide
     */
    public static final int TETHERING_WIGIG = 6;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            TETHER_ERROR_NO_ERROR,
            TETHER_ERROR_PROVISIONING_FAILED,
            TETHER_ERROR_ENTITLEMENT_UNKNOWN,
    })
    public @interface EntitlementResult {
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            TETHER_ERROR_NO_ERROR,
            TETHER_ERROR_UNKNOWN_IFACE,
            TETHER_ERROR_SERVICE_UNAVAIL,
            TETHER_ERROR_INTERNAL_ERROR,
            TETHER_ERROR_TETHER_IFACE_ERROR,
            TETHER_ERROR_ENABLE_FORWARDING_ERROR,
            TETHER_ERROR_DISABLE_FORWARDING_ERROR,
            TETHER_ERROR_IFACE_CFG_ERROR,
            TETHER_ERROR_DHCPSERVER_ERROR,
    })
    public @interface TetheringIfaceError {
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            TETHER_ERROR_SERVICE_UNAVAIL,
            TETHER_ERROR_INTERNAL_ERROR,
            TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION,
            TETHER_ERROR_UNKNOWN_TYPE,
    })
    public @interface StartTetheringError {
    }

    public static final int TETHER_ERROR_NO_ERROR = 0;
    public static final int TETHER_ERROR_UNKNOWN_IFACE = 1;
    public static final int TETHER_ERROR_SERVICE_UNAVAIL = 2;
    public static final int TETHER_ERROR_UNSUPPORTED = 3;
    public static final int TETHER_ERROR_UNAVAIL_IFACE = 4;
    public static final int TETHER_ERROR_INTERNAL_ERROR = 5;
    public static final int TETHER_ERROR_TETHER_IFACE_ERROR = 6;
    public static final int TETHER_ERROR_UNTETHER_IFACE_ERROR = 7;
    public static final int TETHER_ERROR_ENABLE_FORWARDING_ERROR = 8;
    public static final int TETHER_ERROR_DISABLE_FORWARDING_ERROR = 9;
    public static final int TETHER_ERROR_IFACE_CFG_ERROR = 10;
    public static final int TETHER_ERROR_PROVISIONING_FAILED = 11;
    public static final int TETHER_ERROR_DHCPSERVER_ERROR = 12;
    public static final int TETHER_ERROR_ENTITLEMENT_UNKNOWN = 13;
    public static final int TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION = 14;
    public static final int TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION = 15;
    public static final int TETHER_ERROR_UNKNOWN_TYPE = 16;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, value = {
            TETHER_HARDWARE_OFFLOAD_STOPPED,
            TETHER_HARDWARE_OFFLOAD_STARTED,
            TETHER_HARDWARE_OFFLOAD_FAILED,
    })
    public @interface TetherOffloadStatus {
    }

    /** Tethering offload status is stopped. */
    public static final int TETHER_HARDWARE_OFFLOAD_STOPPED = 0;
    /** Tethering offload status is started. */
    public static final int TETHER_HARDWARE_OFFLOAD_STARTED = 1;
    /** Fail to start tethering offload. */
    public static final int TETHER_HARDWARE_OFFLOAD_FAILED = 2;

    /**
     * Create a TetheringManager object for interacting with the tethering service.
     *
     * @param context Context for the manager.
     * @param connectorSupplier Supplier for the manager connector; may return null while the
     *                          service is not connected.
     * {@hide}
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public TetheringManager(@NonNull final Context context,
            @NonNull Supplier<IBinder> connectorSupplier) {
        mContext = context;
        mCallback = new TetheringCallbackInternal();
        mConnectorSupplier = connectorSupplier;

        final String pkgName = mContext.getOpPackageName();

        final IBinder connector = mConnectorSupplier.get();
        // If the connector is available on start, do not start a polling thread. This introduces
        // differences in the thread that sends the oneway binder calls to the service between the
        // first few seconds after boot and later, but it avoids always having differences between
        // the first usage of TetheringManager from a process and subsequent usages (so the
        // difference is only on boot). On boot binder calls may be queued until the service comes
        // up and be sent from a worker thread; later, they are always sent from the caller thread.
        // Considering that it's just oneway binder calls, and ordering is preserved, this seems
        // better than inconsistent behavior persisting after boot.
        if (connector != null) {
            mConnector = ITetheringConnector.Stub.asInterface(connector);
        } else {
            startPollingForConnector();
        }

        Log.i(TAG, "registerTetheringEventCallback:" + pkgName);
        getConnector(c -> c.registerTetheringEventCallback(mCallback, pkgName));
    }

    private void startPollingForConnector() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CONNECTOR_POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    // Not much to do here, the system needs to wait for the connector
                }

                final IBinder connector = mConnectorSupplier.get();
                if (connector != null) {
                    onTetheringConnected(ITetheringConnector.Stub.asInterface(connector));
                    return;
                }
            }
        }).start();
    }

    private interface ConnectorConsumer {
        void onConnectorAvailable(ITetheringConnector connector) throws RemoteException;
    }

    private void onTetheringConnected(ITetheringConnector connector) {
        // Process the connector wait queue in order, including any items that are added
        // while processing.
        //
        // 1. Copy the queue to a local variable under lock.
        // 2. Drain the local queue with the lock released (otherwise, enqueuing future commands
        //    would block on the lock).
        // 3. Acquire the lock again. If any new tasks were queued during step 2, goto 1.
        //    If not, set mConnector to non-null so future tasks are run immediately, not queued.
        //
        // For this to work, all calls to the tethering service must use getConnector(), which
        // ensures that tasks are added to the queue with the lock held.
        //
        // Once mConnector is set to non-null, it will never be null again. If the network stack
        // process crashes, no recovery is possible.
        // TODO: evaluate whether it is possible to recover from network stack process crashes
        // (though in most cases the system will have crashed when the network stack process
        // crashes).
        do {
            final List<ConnectorConsumer> localWaitQueue;
            synchronized (mConnectorWaitQueue) {
                localWaitQueue = new ArrayList<>(mConnectorWaitQueue);
                mConnectorWaitQueue.clear();
            }

            // Allow more tasks to be added at the end without blocking while draining the queue.
            for (ConnectorConsumer task : localWaitQueue) {
                try {
                    task.onConnectorAvailable(connector);
                } catch (RemoteException e) {
                    // Most likely the network stack process crashed, which is likely to crash the
                    // system. Keep processing other requests but report the error loudly.
                    Log.wtf(TAG, "Error processing request for the tethering connector", e);
                }
            }

            synchronized (mConnectorWaitQueue) {
                if (mConnectorWaitQueue.size() == 0) {
                    mConnector = connector;
                    return;
                }
            }
        } while (true);
    }

    /**
     * Asynchronously get the ITetheringConnector to execute some operation.
     *
     * <p>If the connector is already available, the operation will be executed on the caller's
     * thread. Otherwise it will be queued and executed on a worker thread. The operation should be
     * limited to performing oneway binder calls to minimize differences due to threading.
     */
    private void getConnector(ConnectorConsumer consumer) {
        final ITetheringConnector connector;
        synchronized (mConnectorWaitQueue) {
            connector = mConnector;
            if (connector == null) {
                mConnectorWaitQueue.add(consumer);
                return;
            }
        }

        try {
            consumer.onConnectorAvailable(connector);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface RequestHelper {
        void runRequest(ITetheringConnector connector, IIntResultListener listener);
    }

    // Used to dispatch legacy ConnectivityManager methods that expect tethering to be able to
    // return results and perform operations synchronously.
    // TODO: remove once there are no callers of these legacy methods.
    private class RequestDispatcher {
        private final ConditionVariable mWaiting;
        public volatile int mRemoteResult;

        private final IIntResultListener mListener = new IIntResultListener.Stub() {
                @Override
                public void onResult(final int resultCode) {
                    mRemoteResult = resultCode;
                    mWaiting.open();
                }
        };

        RequestDispatcher() {
            mWaiting = new ConditionVariable();
        }

        int waitForResult(final RequestHelper request) {
            getConnector(c -> request.runRequest(c, mListener));
            if (!mWaiting.block(DEFAULT_TIMEOUT_MS)) {
                throw new IllegalStateException("Callback timeout");
            }

            throwIfPermissionFailure(mRemoteResult);

            return mRemoteResult;
        }
    }

    private void throwIfPermissionFailure(final int errorCode) {
        switch (errorCode) {
            case TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION:
                throw new SecurityException("No android.permission.TETHER_PRIVILEGED"
                        + " or android.permission.WRITE_SETTINGS permission");
            case TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION:
                throw new SecurityException(
                        "No android.permission.ACCESS_NETWORK_STATE permission");
        }
    }

    private class TetheringCallbackInternal extends ITetheringEventCallback.Stub {
        private volatile int mError = TETHER_ERROR_NO_ERROR;
        private final ConditionVariable mWaitForCallback = new ConditionVariable();

        @Override
        public void onCallbackStarted(TetheringCallbackStartedParcel parcel) {
            mTetheringConfiguration = parcel.config;
            mTetherStatesParcel = parcel.states;
            mWaitForCallback.open();
        }

        @Override
        public void onCallbackStopped(int errorCode) {
            mError = errorCode;
            mWaitForCallback.open();
        }

        @Override
        public void onUpstreamChanged(Network network) { }

        @Override
        public void onConfigurationChanged(TetheringConfigurationParcel config) {
            mTetheringConfiguration = config;
        }

        @Override
        public void onTetherStatesChanged(TetherStatesParcel states) {
            mTetherStatesParcel = states;
        }

        @Override
        public void onTetherClientsChanged(List<TetheredClient> clients) { }

        @Override
        public void onOffloadStatusChanged(int status) { }

        public void waitForStarted() {
            mWaitForCallback.block(DEFAULT_TIMEOUT_MS);
            throwIfPermissionFailure(mError);
        }
    }

    /**
     * Attempt to tether the named interface.  This will setup a dhcp server
     * on the interface, forward and NAT IP v4 packets and forward DNS requests
     * to the best active upstream network interface.  Note that if no upstream
     * IP network interface is available, dhcp will still run and traffic will be
     * allowed between the tethered devices and this device, though upstream net
     * access will of course fail until an upstream network interface becomes
     * active.
     *
     * @deprecated The only usages is PanService. It uses this for legacy reasons
     * and will migrate away as soon as possible.
     *
     * @param iface the interface name to tether.
     * @return error a {@code TETHER_ERROR} value indicating success or failure type
     *
     * {@hide}
     */
    @Deprecated
    @SystemApi(client = MODULE_LIBRARIES)
    public int tether(@NonNull final String iface) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "tether caller:" + callerPkg);
        final RequestDispatcher dispatcher = new RequestDispatcher();

        return dispatcher.waitForResult((connector, listener) -> {
            try {
                connector.tether(iface, callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Stop tethering the named interface.
     *
     * @deprecated The only usages is PanService. It uses this for legacy reasons
     * and will migrate away as soon as possible.
     *
     * {@hide}
     */
    @Deprecated
    @SystemApi(client = MODULE_LIBRARIES)
    public int untether(@NonNull final String iface) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "untether caller:" + callerPkg);

        final RequestDispatcher dispatcher = new RequestDispatcher();

        return dispatcher.waitForResult((connector, listener) -> {
            try {
                connector.untether(iface, callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Attempt to both alter the mode of USB and Tethering of USB.
     *
     * @deprecated New client should not use this API anymore. All clients should use
     * #startTethering or #stopTethering which encapsulate proper entitlement logic. If the API is
     * used and an entitlement check is needed, downstream USB tethering will be enabled but will
     * not have any upstream.
     *
     * {@hide}
     */
    @Deprecated
    @SystemApi(client = MODULE_LIBRARIES)
    public int setUsbTethering(final boolean enable) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "setUsbTethering caller:" + callerPkg);

        final RequestDispatcher dispatcher = new RequestDispatcher();

        return dispatcher.waitForResult((connector, listener) -> {
            try {
                connector.setUsbTethering(enable, callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     *  Use with {@link #startTethering} to specify additional parameters when starting tethering.
     */
    public static class TetheringRequest {
        /** A configuration set for TetheringRequest. */
        private final TetheringRequestParcel mRequestParcel;

        private TetheringRequest(final TetheringRequestParcel request) {
            mRequestParcel = request;
        }

        /** Builder used to create TetheringRequest. */
        public static class Builder {
            private final TetheringRequestParcel mBuilderParcel;

            /** Default constructor of Builder. */
            public Builder(@TetheringType final int type) {
                mBuilderParcel = new TetheringRequestParcel();
                mBuilderParcel.tetheringType = type;
                mBuilderParcel.localIPv4Address = null;
                mBuilderParcel.staticClientAddress = null;
                mBuilderParcel.exemptFromEntitlementCheck = false;
                mBuilderParcel.showProvisioningUi = true;
            }

            /**
             * Configure tethering with static IPv4 assignment.
             *
             * A DHCP server will be started, but will only be able to offer the client address.
             * The two addresses must be in the same prefix.
             *
             * @param localIPv4Address The preferred local IPv4 link address to use.
             * @param clientAddress The static client address.
             */
            @RequiresPermission(android.Manifest.permission.TETHER_PRIVILEGED)
            @NonNull
            public Builder setStaticIpv4Addresses(@NonNull final LinkAddress localIPv4Address,
                    @NonNull final LinkAddress clientAddress) {
                Objects.requireNonNull(localIPv4Address);
                Objects.requireNonNull(clientAddress);
                if (!checkStaticAddressConfiguration(localIPv4Address, clientAddress)) {
                    throw new IllegalArgumentException("Invalid server or client addresses");
                }

                mBuilderParcel.localIPv4Address = localIPv4Address;
                mBuilderParcel.staticClientAddress = clientAddress;
                return this;
            }

            /** Start tethering without entitlement checks. */
            @RequiresPermission(android.Manifest.permission.TETHER_PRIVILEGED)
            @NonNull
            public Builder setExemptFromEntitlementCheck(boolean exempt) {
                mBuilderParcel.exemptFromEntitlementCheck = exempt;
                return this;
            }

            /**
             * If an entitlement check is needed, sets whether to show the entitlement UI or to
             * perform a silent entitlement check. By default, the entitlement UI is shown.
             */
            @RequiresPermission(android.Manifest.permission.TETHER_PRIVILEGED)
            @NonNull
            public Builder setShouldShowEntitlementUi(boolean showUi) {
                mBuilderParcel.showProvisioningUi = showUi;
                return this;
            }

            /** Build {@link TetheringRequest] with the currently set configuration. */
            @NonNull
            public TetheringRequest build() {
                return new TetheringRequest(mBuilderParcel);
            }
        }

        /**
         * Get the local IPv4 address, if one was configured with
         * {@link Builder#setStaticIpv4Addresses}.
         */
        @Nullable
        public LinkAddress getLocalIpv4Address() {
            return mRequestParcel.localIPv4Address;
        }

        /**
         * Get the static IPv4 address of the client, if one was configured with
         * {@link Builder#setStaticIpv4Addresses}.
         */
        @Nullable
        public LinkAddress getClientStaticIpv4Address() {
            return mRequestParcel.staticClientAddress;
        }

        /** Get tethering type. */
        @TetheringType
        public int getTetheringType() {
            return mRequestParcel.tetheringType;
        }

        /** Check if exempt from entitlement check. */
        public boolean isExemptFromEntitlementCheck() {
            return mRequestParcel.exemptFromEntitlementCheck;
        }

        /** Check if show entitlement ui.  */
        public boolean getShouldShowEntitlementUi() {
            return mRequestParcel.showProvisioningUi;
        }

        /**
         * Check whether the two addresses are ipv4 and in the same prefix.
         * @hide
         */
        public static boolean checkStaticAddressConfiguration(
                @NonNull final LinkAddress localIPv4Address,
                @NonNull final LinkAddress clientAddress) {
            return localIPv4Address.getPrefixLength() == clientAddress.getPrefixLength()
                    && localIPv4Address.isIpv4() && clientAddress.isIpv4()
                    && new IpPrefix(localIPv4Address.toString()).equals(
                    new IpPrefix(clientAddress.toString()));
        }

        /**
         * Get a TetheringRequestParcel from the configuration
         * @hide
         */
        public TetheringRequestParcel getParcel() {
            return mRequestParcel;
        }

        /** String of TetheringRequest detail. */
        public String toString() {
            return "TetheringRequest [ type= " + mRequestParcel.tetheringType
                    + ", localIPv4Address= " + mRequestParcel.localIPv4Address
                    + ", staticClientAddress= " + mRequestParcel.staticClientAddress
                    + ", exemptFromEntitlementCheck= "
                    + mRequestParcel.exemptFromEntitlementCheck + ", showProvisioningUi= "
                    + mRequestParcel.showProvisioningUi + " ]";
        }
    }

    /**
     * Callback for use with {@link #startTethering} to find out whether tethering succeeded.
     */
    public interface StartTetheringCallback {
        /**
         * Called when tethering has been successfully started.
         */
        default void onTetheringStarted() {}

        /**
         * Called when starting tethering failed.
         *
         * @param error The error that caused the failure.
         */
        default void onTetheringFailed(@StartTetheringError final int error) {}
    }

    /**
     * Starts tethering and runs tether provisioning for the given type if needed. If provisioning
     * fails, stopTethering will be called automatically.
     *
     * <p>Without {@link android.Manifest.permission.TETHER_PRIVILEGED} permission, the call will
     * fail if a tethering entitlement check is required.
     *
     * @param request a {@link TetheringRequest} which can specify the preferred configuration.
     * @param executor {@link Executor} to specify the thread upon which the callback of
     *         TetheringRequest will be invoked.
     * @param callback A callback that will be called to indicate the success status of the
     *                 tethering start request.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.TETHER_PRIVILEGED,
            android.Manifest.permission.WRITE_SETTINGS
    })
    public void startTethering(@NonNull final TetheringRequest request,
            @NonNull final Executor executor, @NonNull final StartTetheringCallback callback) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "startTethering caller:" + callerPkg);

        final IIntResultListener listener = new IIntResultListener.Stub() {
            @Override
            public void onResult(final int resultCode) {
                executor.execute(() -> {
                    if (resultCode == TETHER_ERROR_NO_ERROR) {
                        callback.onTetheringStarted();
                    } else {
                        callback.onTetheringFailed(resultCode);
                    }
                });
            }
        };
        getConnector(c -> c.startTethering(request.getParcel(), callerPkg, listener));
    }

    /**
     * Starts tethering and runs tether provisioning for the given type if needed. If provisioning
     * fails, stopTethering will be called automatically.
     *
     * <p>Without {@link android.Manifest.permission.TETHER_PRIVILEGED} permission, the call will
     * fail if a tethering entitlement check is required.
     *
     * @param type The tethering type, on of the {@code TetheringManager#TETHERING_*} constants.
     * @param executor {@link Executor} to specify the thread upon which the callback of
     *         TetheringRequest will be invoked.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.TETHER_PRIVILEGED,
            android.Manifest.permission.WRITE_SETTINGS
    })
    @SystemApi(client = MODULE_LIBRARIES)
    public void startTethering(int type, @NonNull final Executor executor,
            @NonNull final StartTetheringCallback callback) {
        startTethering(new TetheringRequest.Builder(type).build(), executor, callback);
    }

    /**
     * Stops tethering for the given type. Also cancels any provisioning rechecks for that type if
     * applicable.
     *
     * <p>Without {@link android.Manifest.permission.TETHER_PRIVILEGED} permission, the call will
     * fail if a tethering entitlement check is required.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.TETHER_PRIVILEGED,
            android.Manifest.permission.WRITE_SETTINGS
    })
    public void stopTethering(@TetheringType final int type) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "stopTethering caller:" + callerPkg);

        getConnector(c -> c.stopTethering(type, callerPkg, new IIntResultListener.Stub() {
            @Override
            public void onResult(int resultCode) {
                // TODO: provide an API to obtain result
                // This has never been possible as stopTethering has always been void and never
                // taken a callback object. The only indication that callers have is if the call
                // results in a TETHER_STATE_CHANGE broadcast.
            }
        }));
    }

    /**
     * Callback for use with {@link #getLatestTetheringEntitlementResult} to find out whether
     * entitlement succeeded.
     */
    public interface OnTetheringEntitlementResultListener  {
        /**
         * Called to notify entitlement result.
         *
         * @param resultCode an int value of entitlement result. It may be one of
         *         {@link #TETHER_ERROR_NO_ERROR},
         *         {@link #TETHER_ERROR_PROVISIONING_FAILED}, or
         *         {@link #TETHER_ERROR_ENTITLEMENT_UNKNOWN}.
         */
        void onTetheringEntitlementResult(@EntitlementResult int result);
    }

    /**
     * Request the latest value of the tethering entitlement check.
     *
     * <p>This method will only return the latest entitlement result if it is available. If no
     * cached entitlement result is available, and {@code showEntitlementUi} is false,
     * {@link #TETHER_ERROR_ENTITLEMENT_UNKNOWN} will be returned. If {@code showEntitlementUi} is
     * true, entitlement will be run.
     *
     * <p>Without {@link android.Manifest.permission.TETHER_PRIVILEGED} permission, the call will
     * fail if a tethering entitlement check is required.
     *
     * @param type the downstream type of tethering. Must be one of {@code #TETHERING_*} constants.
     * @param showEntitlementUi a boolean indicating whether to check result for the UI-based
     *         entitlement check or the silent entitlement check.
     * @param executor the executor on which callback will be invoked.
     * @param listener an {@link OnTetheringEntitlementResultListener} which will be called to
     *         notify the caller of the result of entitlement check. The listener may be called zero
     *         or one time.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.TETHER_PRIVILEGED,
            android.Manifest.permission.WRITE_SETTINGS
    })
    public void requestLatestTetheringEntitlementResult(@TetheringType int type,
            boolean showEntitlementUi,
            @NonNull Executor executor,
            @NonNull final OnTetheringEntitlementResultListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException(
                    "OnTetheringEntitlementResultListener cannot be null.");
        }

        ResultReceiver wrappedListener = new ResultReceiver(null /* handler */) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                executor.execute(() -> {
                    listener.onTetheringEntitlementResult(resultCode);
                });
            }
        };

        requestLatestTetheringEntitlementResult(type, wrappedListener,
                    showEntitlementUi);
    }

    /**
     * Helper function of #requestLatestTetheringEntitlementResult to remain backwards compatible
     * with ConnectivityManager#getLatestTetheringEntitlementResult
     *
     * {@hide}
     */
    // TODO: improve the usage of ResultReceiver, b/145096122
    @SystemApi(client = MODULE_LIBRARIES)
    public void requestLatestTetheringEntitlementResult(@TetheringType final int type,
            @NonNull final ResultReceiver receiver, final boolean showEntitlementUi) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "getLatestTetheringEntitlementResult caller:" + callerPkg);

        getConnector(c -> c.requestLatestTetheringEntitlementResult(
                type, receiver, showEntitlementUi, callerPkg));
    }

    /**
     * Callback for use with {@link registerTetheringEventCallback} to find out tethering
     * upstream status.
     */
    public interface TetheringEventCallback {
        /**
         * Called when tethering supported status changed.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * <p>Tethering may be disabled via system properties, device configuration, or device
         * policy restrictions.
         *
         * @param supported The new supported status
         */
        default void onTetheringSupported(boolean supported) {}

        /**
         * Called when tethering upstream changed.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * @param network the {@link Network} of tethering upstream. Null means tethering doesn't
         * have any upstream.
         */
        default void onUpstreamChanged(@Nullable Network network) {}

        /**
         * Called when there was a change in tethering interface regular expressions.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param reg The new regular expressions.
         *
         * @hide
         */
        @SystemApi(client = MODULE_LIBRARIES)
        default void onTetherableInterfaceRegexpsChanged(@NonNull TetheringInterfaceRegexps reg) {}

        /**
         * Called when there was a change in the list of tetherable interfaces. Tetherable
         * interface means this interface is available and can be used for tethering.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param interfaces The list of tetherable interface names.
         */
        default void onTetherableInterfacesChanged(@NonNull List<String> interfaces) {}

        /**
         * Called when there was a change in the list of tethered interfaces.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param interfaces The list of 0 or more String of currently tethered interface names.
         */
        default void onTetheredInterfacesChanged(@NonNull List<String> interfaces) {}

        /**
         * Called when an error occurred configuring tethering.
         *
         * <p>This will be called immediately after the callback is registered if the latest status
         * on the interface is an error, and may be called multiple times later upon changes.
         * @param ifName Name of the interface.
         * @param error One of {@code TetheringManager#TETHER_ERROR_*}.
         */
        default void onError(@NonNull String ifName, @TetheringIfaceError int error) {}

        /**
         * Called when the list of tethered clients changes.
         *
         * <p>This callback provides best-effort information on connected clients based on state
         * known to the system, however the list cannot be completely accurate (and should not be
         * used for security purposes). For example, clients behind a bridge and using static IP
         * assignments are not visible to the tethering device; or even when using DHCP, such
         * clients may still be reported by this callback after disconnection as the system cannot
         * determine if they are still connected.
         * @param clients The new set of tethered clients; the collection is not ordered.
         */
        default void onClientsChanged(@NonNull Collection<TetheredClient> clients) {}

        /**
         * Called when tethering offload status changes.
         *
         * <p>This will be called immediately after the callback is registered.
         * @param status The offload status.
         */
        default void onOffloadStatusChanged(@TetherOffloadStatus int status) {}
    }

    /**
     * Regular expressions used to identify tethering interfaces.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static class TetheringInterfaceRegexps {
        private final String[] mTetherableBluetoothRegexs;
        private final String[] mTetherableUsbRegexs;
        private final String[] mTetherableWifiRegexs;

        /** @hide */
        public TetheringInterfaceRegexps(@NonNull String[] tetherableBluetoothRegexs,
                @NonNull String[] tetherableUsbRegexs, @NonNull String[] tetherableWifiRegexs) {
            mTetherableBluetoothRegexs = tetherableBluetoothRegexs.clone();
            mTetherableUsbRegexs = tetherableUsbRegexs.clone();
            mTetherableWifiRegexs = tetherableWifiRegexs.clone();
        }

        @NonNull
        public List<String> getTetherableBluetoothRegexs() {
            return Collections.unmodifiableList(Arrays.asList(mTetherableBluetoothRegexs));
        }

        @NonNull
        public List<String> getTetherableUsbRegexs() {
            return Collections.unmodifiableList(Arrays.asList(mTetherableUsbRegexs));
        }

        @NonNull
        public List<String> getTetherableWifiRegexs() {
            return Collections.unmodifiableList(Arrays.asList(mTetherableWifiRegexs));
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTetherableBluetoothRegexs, mTetherableUsbRegexs,
                    mTetherableWifiRegexs);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof TetheringInterfaceRegexps)) return false;
            final TetheringInterfaceRegexps other = (TetheringInterfaceRegexps) obj;
            return Arrays.equals(mTetherableBluetoothRegexs, other.mTetherableBluetoothRegexs)
                    && Arrays.equals(mTetherableUsbRegexs, other.mTetherableUsbRegexs)
                    && Arrays.equals(mTetherableWifiRegexs, other.mTetherableWifiRegexs);
        }
    }

    /**
     * Start listening to tethering change events. Any new added callback will receive the last
     * tethering status right away. If callback is registered,
     * {@link TetheringEventCallback#onUpstreamChanged} will immediately be called. If tethering
     * has no upstream or disabled, the argument of callback will be null. The same callback object
     * cannot be registered twice.
     *
     * @param executor the executor on which callback will be invoked.
     * @param callback the callback to be called when tethering has change events.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    public void registerTetheringEventCallback(@NonNull Executor executor,
            @NonNull TetheringEventCallback callback) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "registerTetheringEventCallback caller:" + callerPkg);

        synchronized (mTetheringEventCallbacks) {
            if (mTetheringEventCallbacks.containsKey(callback)) {
                throw new IllegalArgumentException("callback was already registered.");
            }
            final ITetheringEventCallback remoteCallback = new ITetheringEventCallback.Stub() {
                // Only accessed with a lock on this object
                private final HashMap<String, Integer> mErrorStates = new HashMap<>();
                private String[] mLastTetherableInterfaces = null;
                private String[] mLastTetheredInterfaces = null;

                @Override
                public void onUpstreamChanged(Network network) throws RemoteException {
                    executor.execute(() -> {
                        callback.onUpstreamChanged(network);
                    });
                }

                private synchronized void sendErrorCallbacks(final TetherStatesParcel newStates) {
                    for (int i = 0; i < newStates.erroredIfaceList.length; i++) {
                        final String iface = newStates.erroredIfaceList[i];
                        final Integer lastError = mErrorStates.get(iface);
                        final int newError = newStates.lastErrorList[i];
                        if (newError != TETHER_ERROR_NO_ERROR
                                && !Objects.equals(lastError, newError)) {
                            callback.onError(iface, newError);
                        }
                        mErrorStates.put(iface, newError);
                    }
                }

                private synchronized void maybeSendTetherableIfacesChangedCallback(
                        final TetherStatesParcel newStates) {
                    if (Arrays.equals(mLastTetherableInterfaces, newStates.availableList)) return;
                    mLastTetherableInterfaces = newStates.availableList.clone();
                    callback.onTetherableInterfacesChanged(
                            Collections.unmodifiableList(Arrays.asList(mLastTetherableInterfaces)));
                }

                private synchronized void maybeSendTetheredIfacesChangedCallback(
                        final TetherStatesParcel newStates) {
                    if (Arrays.equals(mLastTetheredInterfaces, newStates.tetheredList)) return;
                    mLastTetheredInterfaces = newStates.tetheredList.clone();
                    callback.onTetheredInterfacesChanged(
                            Collections.unmodifiableList(Arrays.asList(mLastTetheredInterfaces)));
                }

                // Called immediately after the callbacks are registered.
                @Override
                public void onCallbackStarted(TetheringCallbackStartedParcel parcel) {
                    executor.execute(() -> {
                        callback.onTetheringSupported(parcel.tetheringSupported);
                        callback.onUpstreamChanged(parcel.upstreamNetwork);
                        sendErrorCallbacks(parcel.states);
                        sendRegexpsChanged(parcel.config);
                        maybeSendTetherableIfacesChangedCallback(parcel.states);
                        maybeSendTetheredIfacesChangedCallback(parcel.states);
                        callback.onClientsChanged(parcel.tetheredClients);
                        callback.onOffloadStatusChanged(parcel.offloadStatus);
                    });
                }

                @Override
                public void onCallbackStopped(int errorCode) {
                    executor.execute(() -> {
                        throwIfPermissionFailure(errorCode);
                    });
                }

                private void sendRegexpsChanged(TetheringConfigurationParcel parcel) {
                    callback.onTetherableInterfaceRegexpsChanged(new TetheringInterfaceRegexps(
                            parcel.tetherableBluetoothRegexs,
                            parcel.tetherableUsbRegexs,
                            parcel.tetherableWifiRegexs));
                }

                @Override
                public void onConfigurationChanged(TetheringConfigurationParcel config) {
                    executor.execute(() -> sendRegexpsChanged(config));
                }

                @Override
                public void onTetherStatesChanged(TetherStatesParcel states) {
                    executor.execute(() -> {
                        sendErrorCallbacks(states);
                        maybeSendTetherableIfacesChangedCallback(states);
                        maybeSendTetheredIfacesChangedCallback(states);
                    });
                }

                @Override
                public void onTetherClientsChanged(final List<TetheredClient> clients) {
                    executor.execute(() -> callback.onClientsChanged(clients));
                }

                @Override
                public void onOffloadStatusChanged(final int status) {
                    executor.execute(() -> callback.onOffloadStatusChanged(status));
                }
            };
            getConnector(c -> c.registerTetheringEventCallback(remoteCallback, callerPkg));
            mTetheringEventCallbacks.put(callback, remoteCallback);
        }
    }

    /**
     * Remove tethering event callback previously registered with
     * {@link #registerTetheringEventCallback}.
     *
     * @param callback previously registered callback.
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.TETHER_PRIVILEGED,
            Manifest.permission.ACCESS_NETWORK_STATE
    })
    public void unregisterTetheringEventCallback(@NonNull final TetheringEventCallback callback) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "unregisterTetheringEventCallback caller:" + callerPkg);

        synchronized (mTetheringEventCallbacks) {
            ITetheringEventCallback remoteCallback = mTetheringEventCallbacks.remove(callback);
            if (remoteCallback == null) {
                throw new IllegalArgumentException("callback was not registered.");
            }

            getConnector(c -> c.unregisterTetheringEventCallback(remoteCallback, callerPkg));
        }
    }

    /**
     * Get a more detailed error code after a Tethering or Untethering
     * request asynchronously failed.
     *
     * @param iface The name of the interface of interest
     * @return error The error code of the last error tethering or untethering the named
     *               interface
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public int getLastTetherError(@NonNull final String iface) {
        mCallback.waitForStarted();
        if (mTetherStatesParcel == null) return TETHER_ERROR_NO_ERROR;

        int i = 0;
        for (String errored : mTetherStatesParcel.erroredIfaceList) {
            if (iface.equals(errored)) return mTetherStatesParcel.lastErrorList[i];

            i++;
        }
        return TETHER_ERROR_NO_ERROR;
    }

    /**
     * Get the list of regular expressions that define any tetherable
     * USB network interfaces.  If USB tethering is not supported by the
     * device, this list should be empty.
     *
     * @return an array of 0 or more regular expression Strings defining
     *        what interfaces are considered tetherable usb interfaces.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public @NonNull String[] getTetherableUsbRegexs() {
        mCallback.waitForStarted();
        return mTetheringConfiguration.tetherableUsbRegexs;
    }

    /**
     * Get the list of regular expressions that define any tetherable
     * Wifi network interfaces.  If Wifi tethering is not supported by the
     * device, this list should be empty.
     *
     * @return an array of 0 or more regular expression Strings defining
     *        what interfaces are considered tetherable wifi interfaces.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public @NonNull String[] getTetherableWifiRegexs() {
        mCallback.waitForStarted();
        return mTetheringConfiguration.tetherableWifiRegexs;
    }

    /**
     * Get the list of regular expressions that define any tetherable
     * Bluetooth network interfaces.  If Bluetooth tethering is not supported by the
     * device, this list should be empty.
     *
     * @return an array of 0 or more regular expression Strings defining
     *        what interfaces are considered tetherable bluetooth interfaces.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public @NonNull String[] getTetherableBluetoothRegexs() {
        mCallback.waitForStarted();
        return mTetheringConfiguration.tetherableBluetoothRegexs;
    }

    /**
     * Get the set of tetherable, available interfaces.  This list is limited by
     * device configuration and current interface existence.
     *
     * @return an array of 0 or more Strings of tetherable interface names.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public @NonNull String[] getTetherableIfaces() {
        mCallback.waitForStarted();
        if (mTetherStatesParcel == null) return new String[0];

        return mTetherStatesParcel.availableList;
    }

    /**
     * Get the set of tethered interfaces.
     *
     * @return an array of 0 or more String of currently tethered interface names.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public @NonNull String[] getTetheredIfaces() {
        mCallback.waitForStarted();
        if (mTetherStatesParcel == null) return new String[0];

        return mTetherStatesParcel.tetheredList;
    }

    /**
     * Get the set of interface names which attempted to tether but
     * failed.  Re-attempting to tether may cause them to reset to the Tethered
     * state.  Alternatively, causing the interface to be destroyed and recreated
     * may cause them to reset to the available state.
     * {@link TetheringManager#getLastTetherError} can be used to get more
     * information on the cause of the errors.
     *
     * @return an array of 0 or more String indicating the interface names
     *        which failed to tether.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public @NonNull String[] getTetheringErroredIfaces() {
        mCallback.waitForStarted();
        if (mTetherStatesParcel == null) return new String[0];

        return mTetherStatesParcel.erroredIfaceList;
    }

    /**
     * Get the set of tethered dhcp ranges.
     *
     * @deprecated This API just return the default value which is not used in DhcpServer.
     * @hide
     */
    @Deprecated
    public @NonNull String[] getTetheredDhcpRanges() {
        mCallback.waitForStarted();
        return mTetheringConfiguration.legacyDhcpRanges;
    }

    /**
     * Check if the device allows for tethering.  It may be disabled via
     * {@code ro.tether.denied} system property, Settings.TETHER_SUPPORTED or
     * due to device configuration.
     *
     * @return a boolean - {@code true} indicating Tethering is supported.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public boolean isTetheringSupported() {
        final String callerPkg = mContext.getOpPackageName();

        return isTetheringSupported(callerPkg);
    }

    /**
     * Check if the device allows for tethering. It may be disabled via {@code ro.tether.denied}
     * system property, Settings.TETHER_SUPPORTED or due to device configuration. This is useful
     * for system components that query this API on behalf of an app. In particular, Bluetooth
     * has @UnsupportedAppUsage calls that will let apps turn on bluetooth tethering if they have
     * the right permissions, but such an app needs to know whether it can (permissions as well
     * as support from the device) turn on tethering in the first place to show the appropriate UI.
     *
     * @param callerPkg The caller package name, if it is not matching the calling uid,
     *       SecurityException would be thrown.
     * @return a boolean - {@code true} indicating Tethering is supported.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public boolean isTetheringSupported(@NonNull final String callerPkg) {

        final RequestDispatcher dispatcher = new RequestDispatcher();
        final int ret = dispatcher.waitForResult((connector, listener) -> {
            try {
                connector.isTetheringSupported(callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });

        return ret == TETHER_ERROR_NO_ERROR;
    }

    /**
     * Stop all active tethering.
     *
     * <p>Without {@link android.Manifest.permission.TETHER_PRIVILEGED} permission, the call will
     * fail if a tethering entitlement check is required.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.TETHER_PRIVILEGED,
            android.Manifest.permission.WRITE_SETTINGS
    })
    public void stopAllTethering() {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "stopAllTethering caller:" + callerPkg);

        getConnector(c -> c.stopAllTethering(callerPkg, new IIntResultListener.Stub() {
            @Override
            public void onResult(int resultCode) {
                // TODO: add an API parameter to send result to caller.
                // This has never been possible as stopAllTethering has always been void and never
                // taken a callback object. The only indication that callers have is if the call
                // results in a TETHER_STATE_CHANGE broadcast.
            }
        }));
    }
}
