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
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * Class that allows creation and management of per-app, test-only networks
 *
 * @hide
 */
@TestApi
public class TestNetworkManager {
    @NonNull private static final String TAG = TestNetworkManager.class.getSimpleName();

    @NonNull private final ITestNetworkManager mService;
    @NonNull private final Context mContext;

    /** @hide */
    public TestNetworkManager(@NonNull Context context, @NonNull ITestNetworkManager service) {
        mContext = Preconditions.checkNotNull(context, "missing Context");
        mService = Preconditions.checkNotNull(service, "missing ITestNetworkManager");
    }

    /**
     * Teardown the capability-limited, testing-only network for a given interface
     *
     * @param network The test network that should be torn down
     * @hide
     */
    @TestApi
    public void teardownTestNetwork(@NonNull Network network) {
        try {
            mService.teardownTestNetwork(network.netId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets up a capability-limited, testing-only network for a given interface
     *
     * @param iface the name of the interface to be used for the Network LinkProperties.
     * @param binder A binder object guarding the lifecycle of this test network.
     * @hide
     */
    @TestApi
    public void setupTestNetwork(@NonNull String iface, @NonNull IBinder binder) {
        try {
            mService.setupTestNetwork(iface, binder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Create a tun interface for testing purposes
     *
     * @param linkAddrs an array of LinkAddresses to assign to the TUN interface
     * @return A ParcelFileDescriptor of the underlying TUN interface. Close this to tear down the
     *     TUN interface.
     * @hide
     */
    @TestApi
    public TestNetworkInterface createTunInterface(@NonNull LinkAddress[] linkAddrs) {
        try {
            return mService.createTunInterface(linkAddrs);
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
    @TestApi
    public TestNetworkInterface createTapInterface() {
        try {
            return mService.createTapInterface();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
