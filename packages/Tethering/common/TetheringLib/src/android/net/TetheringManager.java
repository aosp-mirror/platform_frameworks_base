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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class provides the APIs to control the tethering service.
 * <p> The primary responsibilities of this class are to provide the APIs for applications to
 * start tethering, stop tethering, query configuration and query status.
 *
 * @hide
 */
@SystemApi
@TestApi
public class TetheringManager {
    private static final String TAG = TetheringManager.class.getSimpleName();
    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private static TetheringManager sInstance;

    private final ITetheringConnector mConnector;
    private final TetheringCallbackInternal mCallback;
    private final Context mContext;
    private final ArrayMap<TetheringEventCallback, ITetheringEventCallback>
            mTetheringEventCallbacks = new ArrayMap<>();

    private TetheringConfigurationParcel mTetheringConfiguration;
    private TetherStatesParcel mTetherStatesParcel;

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

    public static final int TETHER_ERROR_NO_ERROR           = 0;
    public static final int TETHER_ERROR_UNKNOWN_IFACE      = 1;
    public static final int TETHER_ERROR_SERVICE_UNAVAIL    = 2;
    public static final int TETHER_ERROR_UNSUPPORTED        = 3;
    public static final int TETHER_ERROR_UNAVAIL_IFACE      = 4;
    public static final int TETHER_ERROR_MASTER_ERROR       = 5;
    public static final int TETHER_ERROR_TETHER_IFACE_ERROR = 6;
    public static final int TETHER_ERROR_UNTETHER_IFACE_ERROR = 7;
    public static final int TETHER_ERROR_ENABLE_NAT_ERROR     = 8;
    public static final int TETHER_ERROR_DISABLE_NAT_ERROR    = 9;
    public static final int TETHER_ERROR_IFACE_CFG_ERROR      = 10;
    public static final int TETHER_ERROR_PROVISION_FAILED     = 11;
    public static final int TETHER_ERROR_DHCPSERVER_ERROR     = 12;
    public static final int TETHER_ERROR_ENTITLEMENT_UNKNOWN = 13;
    public static final int TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION = 14;
    public static final int TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION = 15;

    /**
     * Create a TetheringManager object for interacting with the tethering service.
     *
     * {@hide}
     */
    public TetheringManager(@NonNull final Context context, @NonNull final IBinder service) {
        mContext = context;
        mConnector = ITetheringConnector.Stub.asInterface(service);
        mCallback = new TetheringCallbackInternal();

        final String pkgName = mContext.getOpPackageName();
        Log.i(TAG, "registerTetheringEventCallback:" + pkgName);
        try {
            mConnector.registerTetheringEventCallback(mCallback, pkgName);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface RequestHelper {
        void runRequest(IIntResultListener listener);
    }

    private class RequestDispatcher {
        private final ConditionVariable mWaiting;
        public int mRemoteResult;

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
            request.runRequest(mListener);
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
        private int mError = TETHER_ERROR_NO_ERROR;
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
    public int tether(@NonNull final String iface) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "tether caller:" + callerPkg);
        final RequestDispatcher dispatcher = new RequestDispatcher();

        return dispatcher.waitForResult(listener -> {
            try {
                mConnector.tether(iface, callerPkg, listener);
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
    public int untether(@NonNull final String iface) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "untether caller:" + callerPkg);

        final RequestDispatcher dispatcher = new RequestDispatcher();

        return dispatcher.waitForResult(listener -> {
            try {
                mConnector.untether(iface, callerPkg, listener);
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
    public int setUsbTethering(final boolean enable) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "setUsbTethering caller:" + callerPkg);

        final RequestDispatcher dispatcher = new RequestDispatcher();

        return dispatcher.waitForResult(listener -> {
            try {
                mConnector.setUsbTethering(enable, callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Starts tethering and runs tether provisioning for the given type if needed. If provisioning
     * fails, stopTethering will be called automatically.
     * @hide
     */
    // TODO: improve the usage of ResultReceiver, b/145096122
    public void startTethering(final int type, @NonNull final ResultReceiver receiver,
            final boolean showProvisioningUi) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "startTethering caller:" + callerPkg);

        try {
            mConnector.startTethering(type, receiver, showProvisioningUi, callerPkg);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stops tethering for the given type. Also cancels any provisioning rechecks for that type if
     * applicable.
     */
    public void stopTethering(final int type) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "stopTethering caller:" + callerPkg);

        final RequestDispatcher dispatcher = new RequestDispatcher();

        dispatcher.waitForResult(listener -> {
            try {
                mConnector.stopTethering(type, callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });
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
         *         {@link #TETHER_ERROR_PROVISION_FAILED}, or
         *         {@link #TETHER_ERROR_ENTITLEMENT_UNKNOWN}.
         */
        void onTetheringEntitlementResult(int resultCode);
    }

    /**
     * Request the latest value of the tethering entitlement check.
     *
     * <p>This method will only return the latest entitlement result if it is available. If no
     * cached entitlement result is available, and {@code showEntitlementUi} is false,
     * {@link #TETHER_ERROR_ENTITLEMENT_UNKNOWN} will be returned. If {@code showEntitlementUi} is
     * true, entitlement will be run.
     *
     * @param type the downstream type of tethering. Must be one of {@code #TETHERING_*} constants.
     * @param showEntitlementUi a boolean indicating whether to run UI-based entitlement check.
     * @param executor the executor on which callback will be invoked.
     * @param listener an {@link OnTetheringEntitlementResultListener} which will be called to
     *         notify the caller of the result of entitlement check. The listener may be called zero
     *         or one time.
     */
    @RequiresPermission(android.Manifest.permission.TETHER_PRIVILEGED)
    public void requestLatestTetheringEntitlementResult(int type, boolean showEntitlementUi,
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
    public void requestLatestTetheringEntitlementResult(final int type,
            @NonNull final ResultReceiver receiver, final boolean showEntitlementUi) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "getLatestTetheringEntitlementResult caller:" + callerPkg);

        try {
            mConnector.requestLatestTetheringEntitlementResult(type, receiver, showEntitlementUi,
                    callerPkg);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Callback for use with {@link registerTetheringEventCallback} to find out tethering
     * upstream status.
     */
    public abstract static class TetheringEventCallback {
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
        public void onTetheringSupported(boolean supported) {}

        /**
         * Called when tethering upstream changed.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         *
         * @param network the {@link Network} of tethering upstream. Null means tethering doesn't
         * have any upstream.
         */
        public void onUpstreamChanged(@Nullable Network network) {}

        /**
         * Called when there was a change in tethering interface regular expressions.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param reg The new regular expressions.
         * @deprecated Referencing interfaces by regular expressions is a deprecated mechanism.
         */
        @Deprecated
        public void onTetherableInterfaceRegexpsChanged(@NonNull TetheringInterfaceRegexps reg) {}

        /**
         * Called when there was a change in the list of tetherable interfaces.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param interfaces The list of tetherable interfaces.
         */
        public void onTetherableInterfacesChanged(@NonNull List<String> interfaces) {}

        /**
         * Called when there was a change in the list of tethered interfaces.
         *
         * <p>This will be called immediately after the callback is registered, and may be called
         * multiple times later upon changes.
         * @param interfaces The list of tethered interfaces.
         */
        public void onTetheredInterfacesChanged(@NonNull List<String> interfaces) {}

        /**
         * Called when an error occurred configuring tethering.
         *
         * <p>This will be called immediately after the callback is registered if the latest status
         * on the interface is an error, and may be called multiple times later upon changes.
         * @param ifName Name of the interface.
         * @param error One of {@code TetheringManager#TETHER_ERROR_*}.
         */
        public void onError(@NonNull String ifName, int error) {}
    }

    /**
     * Regular expressions used to identify tethering interfaces.
     * @deprecated Referencing interfaces by regular expressions is a deprecated mechanism.
     */
    @Deprecated
    public static class TetheringInterfaceRegexps {
        private final String[] mTetherableBluetoothRegexs;
        private final String[] mTetherableUsbRegexs;
        private final String[] mTetherableWifiRegexs;

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
            };
            try {
                mConnector.registerTetheringEventCallback(remoteCallback, callerPkg);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
            mTetheringEventCallbacks.put(callback, remoteCallback);
        }
    }

    /**
     * Remove tethering event callback previously registered with
     * {@link #registerTetheringEventCallback}.
     *
     * @param callback previously registered callback.
     */
    public void unregisterTetheringEventCallback(@NonNull final TetheringEventCallback callback) {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "unregisterTetheringEventCallback caller:" + callerPkg);

        synchronized (mTetheringEventCallbacks) {
            ITetheringEventCallback remoteCallback = mTetheringEventCallbacks.remove(callback);
            if (remoteCallback == null) {
                throw new IllegalArgumentException("callback was not registered.");
            }
            try {
                mConnector.unregisterTetheringEventCallback(remoteCallback, callerPkg);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
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
    public boolean isTetheringSupported() {
        final String callerPkg = mContext.getOpPackageName();

        final RequestDispatcher dispatcher = new RequestDispatcher();
        final int ret = dispatcher.waitForResult(listener -> {
            try {
                mConnector.isTetheringSupported(callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });

        return ret == TETHER_ERROR_NO_ERROR;
    }

    /**
     * Stop all active tethering.
     */
    public void stopAllTethering() {
        final String callerPkg = mContext.getOpPackageName();
        Log.i(TAG, "stopAllTethering caller:" + callerPkg);

        final RequestDispatcher dispatcher = new RequestDispatcher();
        dispatcher.waitForResult(listener -> {
            try {
                mConnector.stopAllTethering(callerPkg, listener);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
