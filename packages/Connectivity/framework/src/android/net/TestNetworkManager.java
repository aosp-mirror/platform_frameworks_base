/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Class that allows creation and management of per-app, test-only networks
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class TestNetworkManager {
    /**
     * Prefix for tun interfaces created by this class.
     * @hide
     */
    public static final String TEST_TUN_PREFIX = "testtun";

    /**
     * Prefix for tap interfaces created by this class.
     */
    public static final String TEST_TAP_PREFIX = "testtap";

    @NonNull private static final String TAG = TestNetworkManager.class.getSimpleName();

    @NonNull private final ITestNetworkManager mService;

    /** @hide */
    public TestNetworkManager(@NonNull ITestNetworkManager service) {
        mService = Objects.requireNonNull(service, "missing ITestNetworkManager");
    }

    /**
     * Teardown the capability-limited, testing-only network for a given interface
     *
     * @param network The test network that should be torn down
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void teardownTestNetwork(@NonNull Network network) {
        try {
            mService.teardownTestNetwork(network.netId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void setupTestNetwork(
            @NonNull String iface,
            @Nullable LinkProperties lp,
            boolean isMetered,
            @NonNull int[] administratorUids,
            @NonNull IBinder binder) {
        try {
            mService.setupTestNetwork(iface, lp, isMetered, administratorUids, binder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets up a capability-limited, testing-only network for a given interface
     *
     * @param lp The LinkProperties for the TestNetworkService to use for this test network. Note
     *     that the interface name and link addresses will be overwritten, and the passed-in values
     *     discarded.
     * @param isMetered Whether or not the network should be considered metered.
     * @param binder A binder object guarding the lifecycle of this test network.
     * @hide
     */
    public void setupTestNetwork(
            @NonNull LinkProperties lp, boolean isMetered, @NonNull IBinder binder) {
        Objects.requireNonNull(lp, "Invalid LinkProperties");
        setupTestNetwork(lp.getInterfaceName(), lp, isMetered, new int[0], binder);
    }

    /**
     * Sets up a capability-limited, testing-only network for a given interface
     *
     * @param iface the name of the interface to be used for the Network LinkProperties.
     * @param binder A binder object guarding the lifecycle of this test network.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void setupTestNetwork(@NonNull String iface, @NonNull IBinder binder) {
        setupTestNetwork(iface, null, true, new int[0], binder);
    }

    /**
     * Sets up a capability-limited, testing-only network for a given interface with the given
     * administrator UIDs.
     *
     * @param iface the name of the interface to be used for the Network LinkProperties.
     * @param administratorUids The administrator UIDs to be used for the test-only network
     * @param binder A binder object guarding the lifecycle of this test network.
     * @hide
     */
    public void setupTestNetwork(
            @NonNull String iface, @NonNull int[] administratorUids, @NonNull IBinder binder) {
        setupTestNetwork(iface, null, true, administratorUids, binder);
    }

    /**
     * Create a tun interface for testing purposes
     *
     * @param linkAddrs an array of LinkAddresses to assign to the TUN interface
     * @return A ParcelFileDescriptor of the underlying TUN interface. Close this to tear down the
     *     TUN interface.
     * @deprecated Use {@link #createTunInterface(Collection)} instead.
     * @hide
     */
    @Deprecated
    @NonNull
    public TestNetworkInterface createTunInterface(@NonNull LinkAddress[] linkAddrs) {
        return createTunInterface(Arrays.asList(linkAddrs));
    }

    /**
     * Create a tun interface for testing purposes
     *
     * @param linkAddrs an array of LinkAddresses to assign to the TUN interface
     * @return A ParcelFileDescriptor of the underlying TUN interface. Close this to tear down the
     *     TUN interface.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public TestNetworkInterface createTunInterface(@NonNull Collection<LinkAddress> linkAddrs) {
        try {
            final LinkAddress[] arr = new LinkAddress[linkAddrs.size()];
            return mService.createTunInterface(linkAddrs.toArray(arr));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Create a tap interface for testing purposes
     *
     * @return A ParcelFileDescriptor of the underlying TAP interface. Close this to tear down the
     *     TAP interface.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public TestNetworkInterface createTapInterface() {
        try {
            return mService.createTapInterface();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
