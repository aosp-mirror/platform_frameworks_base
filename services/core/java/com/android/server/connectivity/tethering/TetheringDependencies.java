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

package com.android.server.connectivity.tethering;

import android.content.Context;
import android.net.INetd;
import android.net.NetworkRequest;
import android.net.ip.RouterAdvertisementDaemon;
import android.net.util.InterfaceParams;
import android.net.util.NetdService;
import android.os.Handler;
import android.net.util.SharedLog;

import com.android.internal.util.StateMachine;

import java.util.ArrayList;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public class TetheringDependencies {
    public OffloadHardwareInterface getOffloadHardwareInterface(Handler h, SharedLog log) {
        return new OffloadHardwareInterface(h, log);
    }

    public UpstreamNetworkMonitor getUpstreamNetworkMonitor(Context ctx, StateMachine target,
            SharedLog log, int what) {
        return new UpstreamNetworkMonitor(ctx, target, log, what);
    }

    public IPv6TetheringCoordinator getIPv6TetheringCoordinator(
            ArrayList<TetherInterfaceStateMachine> notifyList, SharedLog log) {
        return new IPv6TetheringCoordinator(notifyList, log);
    }

    public RouterAdvertisementDaemon getRouterAdvertisementDaemon(InterfaceParams ifParams) {
        return new RouterAdvertisementDaemon(ifParams);
    }

    public InterfaceParams getInterfaceParams(String ifName) {
        return InterfaceParams.getByName(ifName);
    }

    public INetd getNetdService() {
        return NetdService.getInstance();
    }

    public boolean isTetheringSupported() {
        return true;
    }

    public NetworkRequest getDefaultNetworkRequest() {
        return null;
    }
}
