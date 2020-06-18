/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkstack.tethering;

import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.INetd;
import android.net.NetworkRequest;
import android.net.ip.IpServer;
import android.net.util.SharedLog;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.internal.util.StateMachine;

import java.util.ArrayList;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public abstract class TetheringDependencies {
    /**
     * Get a reference to the BpfCoordinator to be used by tethering.
     */
    public @NonNull BpfCoordinator getBpfCoordinator(
            @NonNull BpfCoordinator.Dependencies deps) {
        return new BpfCoordinator(deps);
    }

    /**
     * Get a reference to the offload hardware interface to be used by tethering.
     */
    public OffloadHardwareInterface getOffloadHardwareInterface(Handler h, SharedLog log) {
        return new OffloadHardwareInterface(h, log);
    }

    /**
     * Get a reference to the offload controller to be used by tethering.
     */
    @NonNull
    public OffloadController getOffloadController(@NonNull Handler h,
            @NonNull SharedLog log, @NonNull OffloadController.Dependencies deps) {
        final NetworkStatsManager statsManager =
                (NetworkStatsManager) getContext().getSystemService(Context.NETWORK_STATS_SERVICE);
        return new OffloadController(h, getOffloadHardwareInterface(h, log),
                getContext().getContentResolver(), statsManager, log, deps);
    }


    /**
     * Get a reference to the UpstreamNetworkMonitor to be used by tethering.
     */
    public UpstreamNetworkMonitor getUpstreamNetworkMonitor(Context ctx, StateMachine target,
            SharedLog log, int what) {
        return new UpstreamNetworkMonitor(ctx, target, log, what);
    }

    /**
     * Get a reference to the IPv6TetheringCoordinator to be used by tethering.
     */
    public IPv6TetheringCoordinator getIPv6TetheringCoordinator(
            ArrayList<IpServer> notifyList, SharedLog log) {
        return new IPv6TetheringCoordinator(notifyList, log);
    }

    /**
     * Get dependencies to be used by IpServer.
     */
    public abstract IpServer.Dependencies getIpServerDependencies();

    /**
     * Indicates whether tethering is supported on the device.
     */
    public boolean isTetheringSupported() {
        return true;
    }

    /**
     * Get the NetworkRequest that should be fulfilled by the default network.
     */
    public abstract NetworkRequest getDefaultNetworkRequest();

    /**
     * Get a reference to the EntitlementManager to be used by tethering.
     */
    public EntitlementManager getEntitlementManager(Context ctx, Handler h, SharedLog log,
            Runnable callback) {
        return new EntitlementManager(ctx, h, log, callback);
    }

    /**
     * Generate a new TetheringConfiguration according to input sub Id.
     */
    public TetheringConfiguration generateTetheringConfiguration(Context ctx, SharedLog log,
            int subId) {
        return new TetheringConfiguration(ctx, log, subId);
    }

    /**
     * Get a reference to INetd to be used by tethering.
     */
    public INetd getINetd(Context context) {
        return INetd.Stub.asInterface(
                (IBinder) context.getSystemService(Context.NETD_SERVICE));
    }

    /**
     * Get a reference to the TetheringNotificationUpdater to be used by tethering.
     */
    public TetheringNotificationUpdater getNotificationUpdater(@NonNull final Context ctx,
            @NonNull final Looper looper) {
        return new TetheringNotificationUpdater(ctx, looper);
    }

    /**
     * Get tethering thread looper.
     */
    public abstract Looper getTetheringLooper();

    /**
     *  Get Context of TetheringSerice.
     */
    public abstract Context getContext();

    /**
     * Get a reference to BluetoothAdapter to be used by tethering.
     */
    public abstract BluetoothAdapter getBluetoothAdapter();

    /**
     * Get SystemProperties which indicate whether tethering is denied.
     */
    public boolean isTetheringDenied() {
        return TextUtils.equals(SystemProperties.get("ro.tether.denied"), "true");
    }
}
