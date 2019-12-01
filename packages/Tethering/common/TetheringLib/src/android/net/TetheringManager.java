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

import static android.Manifest.permission.NETWORK_STACK;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.util.SharedLog;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.StringJoiner;

/**
 * Service used to communicate with the tethering, which is running in a separate module.
 * @hide
 */
public class TetheringManager {
    private static final String TAG = TetheringManager.class.getSimpleName();

    private static TetheringManager sInstance;

    @Nullable
    private ITetheringConnector mConnector;
    private TetherInternalCallback mCallback;
    private Network mTetherUpstream;
    private TetheringConfigurationParcel mTetheringConfiguration;
    private TetherStatesParcel mTetherStatesParcel;

    private final RemoteCallbackList<ITetheringEventCallback> mTetheringEventCallbacks =
            new RemoteCallbackList<>();
    @GuardedBy("mLog")
    private final SharedLog mLog = new SharedLog(TAG);

    private TetheringManager() { }

    /**
     * Get the TetheringManager singleton instance.
     */
    public static synchronized TetheringManager getInstance() {
        if (sInstance == null) {
            sInstance = new TetheringManager();
        }
        return sInstance;
    }

    private class TetheringConnection implements
            ConnectivityModuleConnector.ModuleServiceCallback {
        @Override
        public void onModuleServiceConnected(@NonNull IBinder service) {
            logi("Tethering service connected");
            registerTetheringService(service);
        }
    }

    private void registerTetheringService(@NonNull IBinder service) {
        final ITetheringConnector connector = ITetheringConnector.Stub.asInterface(service);

        log("Tethering service registered");

        // Currently TetheringManager instance is only used by ConnectivityService and mConnector
        // only expect to assign once when system server start and bind tethering service.
        // STOPSHIP: Change mConnector to final before TetheringManager put into boot classpath.
        mConnector = connector;
        mCallback = new TetherInternalCallback();
        try {
            mConnector.registerTetherInternalCallback(mCallback);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private class TetherInternalCallback extends ITetherInternalCallback.Stub {
        private final ConditionVariable mWaitForCallback = new ConditionVariable(false);
        private static final int EVENT_CALLBACK_TIMEOUT_MS = 60_000;

        @Override
        public void onUpstreamChanged(Network network) {
            mTetherUpstream = network;
            reportUpstreamChanged(network);
        }

        @Override
        public void onConfigurationChanged(TetheringConfigurationParcel config) {
            mTetheringConfiguration = config;
        }

        @Override
        public void onTetherStatesChanged(TetherStatesParcel states) {
            mTetherStatesParcel = states;
        }

        @Override
        public void onCallbackCreated(Network network, TetheringConfigurationParcel config,
                TetherStatesParcel states) {
            mTetherUpstream = network;
            mTetheringConfiguration = config;
            mTetherStatesParcel = states;
            mWaitForCallback.open();
        }

        boolean awaitCallbackCreation() {
            return mWaitForCallback.block(EVENT_CALLBACK_TIMEOUT_MS);
        }
    }

    private void reportUpstreamChanged(Network network) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onUpstreamChanged(network);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /**
     * Start the tethering service. Should be called only once on device startup.
     *
     * <p>This method will start the tethering service either in the network stack process,
     * or inside the system server on devices that do not support the tethering module.
     *
     * {@hide}
     */
    public void start() {
        // Using MAINLINE_NETWORK_STACK permission after cutting off the dpendency of system server.
        ConnectivityModuleConnector.getInstance().startModuleService(
                ITetheringConnector.class.getName(), NETWORK_STACK,
                new TetheringConnection());
        log("Tethering service start requested");
    }

    /**
     * Attempt to tether the named interface.  This will setup a dhcp server
     * on the interface, forward and NAT IP v4 packets and forward DNS requests
     * to the best active upstream network interface.  Note that if no upstream
     * IP network interface is available, dhcp will still run and traffic will be
     * allowed between the tethered devices and this device, though upstream net
     * access will of course fail until an upstream network interface becomes
     * active. Note: return value do not have any meaning. It is better to use
     * #getTetherableIfaces() to ensure corresponding interface is available for
     * tethering before calling #tether().
     *
     * @deprecated The only usages should be in PanService and Wifi P2P which
     * need direct access.
     *
     * {@hide}
     */
    @Deprecated
    public int tether(@NonNull String iface) {
        try {
            mConnector.tether(iface);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return TETHER_ERROR_NO_ERROR;
    }

    /**
     * Stop tethering the named interface.
     *
     * @deprecated
     * {@hide}
     */
    @Deprecated
    public int untether(@NonNull String iface) {
        try {
            mConnector.untether(iface);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return TETHER_ERROR_NO_ERROR;
    }

    /**
     * Attempt to both alter the mode of USB and Tethering of USB. WARNING: New client should not
     * use this API anymore. All clients should use #startTethering or #stopTethering which
     * encapsulate proper entitlement logic. If the API is used and an entitlement check is needed,
     * downstream USB tethering will be enabled but will not have any upstream.
     *
     * @deprecated
     * {@hide}
     */
    @Deprecated
    public int setUsbTethering(boolean enable) {
        try {
            mConnector.setUsbTethering(enable);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return TETHER_ERROR_NO_ERROR;
    }

    /**
     * Starts tethering and runs tether provisioning for the given type if needed. If provisioning
     * fails, stopTethering will be called automatically.
     *
     * {@hide}
     */
    // TODO: improve the usage of ResultReceiver, b/145096122
    public void startTethering(int type, @NonNull ResultReceiver receiver,
            boolean showProvisioningUi) {
        try {
            mConnector.startTethering(type, receiver, showProvisioningUi);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops tethering for the given type. Also cancels any provisioning rechecks for that type if
     * applicable.
     *
     * {@hide}
     */
    public void stopTethering(int type) {
        try {
            mConnector.stopTethering(type);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Request the latest value of the tethering entitlement check.
     *
     * Note: Allow privileged apps who have TETHER_PRIVILEGED permission to access. If it turns
     * out some such apps are observed to abuse this API, change to per-UID limits on this API
     * if it's really needed.
     */
    // TODO: improve the usage of ResultReceiver, b/145096122
    public void requestLatestTetheringEntitlementResult(int type, @NonNull ResultReceiver receiver,
            boolean showEntitlementUi) {
        try {
            mConnector.requestLatestTetheringEntitlementResult(type, receiver, showEntitlementUi);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Register tethering event callback.
     *
     * {@hide}
     */
    public void registerTetheringEventCallback(@NonNull ITetheringEventCallback callback) {
        mTetheringEventCallbacks.register(callback);
    }

    /**
     * Unregister tethering event callback.
     *
     * {@hide}
     */
    public void unregisterTetheringEventCallback(@NonNull ITetheringEventCallback callback) {
        mTetheringEventCallbacks.unregister(callback);
    }

    /**
     * Get a more detailed error code after a Tethering or Untethering
     * request asynchronously failed.
     *
     * {@hide}
     */
    public int getLastTetherError(@NonNull String iface) {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
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
     * {@hide}
     */
    public @NonNull String[] getTetherableUsbRegexs() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        return mTetheringConfiguration.tetherableUsbRegexs;
    }

    /**
     * Get the list of regular expressions that define any tetherable
     * Wifi network interfaces.  If Wifi tethering is not supported by the
     * device, this list should be empty.
     *
     * {@hide}
     */
    public @NonNull String[] getTetherableWifiRegexs() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        return mTetheringConfiguration.tetherableWifiRegexs;
    }

    /**
     * Get the list of regular expressions that define any tetherable
     * Bluetooth network interfaces.  If Bluetooth tethering is not supported by the
     * device, this list should be empty.
     *
     * {@hide}
     */
    public @NonNull String[] getTetherableBluetoothRegexs() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        return mTetheringConfiguration.tetherableBluetoothRegexs;
    }

    /**
     * Get the set of tetherable, available interfaces.  This list is limited by
     * device configuration and current interface existence.
     *
     * {@hide}
     */
    public @NonNull String[] getTetherableIfaces() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        if (mTetherStatesParcel == null) return new String[0];
        return mTetherStatesParcel.availableList;
    }

    /**
     * Get the set of tethered interfaces.
     *
     * {@hide}
     */
    public @NonNull String[] getTetheredIfaces() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        if (mTetherStatesParcel == null) return new String[0];
        return mTetherStatesParcel.tetheredList;
    }

    /**
     * Get the set of interface names which attempted to tether but
     * failed.
     *
     * {@hide}
     */
    public @NonNull String[] getTetheringErroredIfaces() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        if (mTetherStatesParcel == null) return new String[0];
        return mTetherStatesParcel.erroredIfaceList;
    }

    /**
     * Get the set of tethered dhcp ranges.
     *
     * @deprecated This API just return the default value which is not used in DhcpServer.
     * {@hide}
     */
    @Deprecated
    public @NonNull String[] getTetheredDhcpRanges() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        return mTetheringConfiguration.legacyDhcpRanges;
    }

    /**
     * Check if the device allows for tethering.
     *
     * {@hide}
     */
    public boolean hasTetherableConfiguration() {
        if (!mCallback.awaitCallbackCreation()) {
            throw new NullPointerException("callback was not ready yet");
        }
        final boolean hasDownstreamConfiguration =
                (mTetheringConfiguration.tetherableUsbRegexs.length != 0)
                || (mTetheringConfiguration.tetherableWifiRegexs.length != 0)
                || (mTetheringConfiguration.tetherableBluetoothRegexs.length != 0);
        final boolean hasUpstreamConfiguration =
                (mTetheringConfiguration.preferredUpstreamIfaceTypes.length != 0)
                || mTetheringConfiguration.chooseUpstreamAutomatically;

        return hasDownstreamConfiguration && hasUpstreamConfiguration;
    }

    /**
     * Log a message in the local log.
     */
    private void log(@NonNull String message) {
        synchronized (mLog) {
            mLog.log(message);
        }
    }

    /**
     * Log a condition that should never happen.
     */
    private void logWtf(@NonNull String message, @Nullable Throwable e) {
        Slog.wtf(TAG, message);
        synchronized (mLog) {
            mLog.e(message, e);
        }
    }

    /**
     * Log a ERROR level message in the local and system logs.
     */
    private void loge(@NonNull String message, @Nullable Throwable e) {
        synchronized (mLog) {
            mLog.e(message, e);
        }
    }

    /**
     * Log a INFO level message in the local and system logs.
     */
    private void logi(@NonNull String message) {
        synchronized (mLog) {
            mLog.i(message);
        }
    }

    /**
     * Dump TetheringManager logs to the specified {@link PrintWriter}.
     */
    public void dump(@NonNull PrintWriter pw) {
        // dump is thread-safe on SharedLog
        mLog.dump(null, pw, null);

        pw.print("subId: ");
        pw.println(mTetheringConfiguration.subId);

        dumpStringArray(pw, "tetherableUsbRegexs",
                mTetheringConfiguration.tetherableUsbRegexs);
        dumpStringArray(pw, "tetherableWifiRegexs",
                mTetheringConfiguration.tetherableWifiRegexs);
        dumpStringArray(pw, "tetherableBluetoothRegexs",
                mTetheringConfiguration.tetherableBluetoothRegexs);

        pw.print("isDunRequired: ");
        pw.println(mTetheringConfiguration.isDunRequired);

        pw.print("chooseUpstreamAutomatically: ");
        pw.println(mTetheringConfiguration.chooseUpstreamAutomatically);

        dumpStringArray(pw, "legacyDhcpRanges", mTetheringConfiguration.legacyDhcpRanges);
        dumpStringArray(pw, "defaultIPv4DNS", mTetheringConfiguration.defaultIPv4DNS);

        dumpStringArray(pw, "provisioningApp", mTetheringConfiguration.provisioningApp);
        pw.print("provisioningAppNoUi: ");
        pw.println(mTetheringConfiguration.provisioningAppNoUi);

        pw.print("enableLegacyDhcpServer: ");
        pw.println(mTetheringConfiguration.enableLegacyDhcpServer);

        pw.println();
    }

    private static void dumpStringArray(@NonNull PrintWriter pw, @NonNull String label,
            @Nullable String[] values) {
        pw.print(label);
        pw.print(": ");

        if (values != null) {
            final StringJoiner sj = new StringJoiner(", ", "[", "]");
            for (String value : values) sj.add(value);

            pw.print(sj.toString());
        } else {
            pw.print("null");
        }

        pw.println();
    }
}
