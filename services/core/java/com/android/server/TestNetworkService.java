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

package com.android.server;

import static android.net.TestNetworkManager.TEST_TAP_PREFIX;
import static android.net.TestNetworkManager.TEST_TUN_PREFIX;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.ITestNetworkManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkStack;
import android.net.RouteInfo;
import android.net.StringNetworkSpecifier;
import android.net.TestNetworkInterface;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.NetdUtils;
import com.android.net.module.util.NetworkStackConstants;

import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** @hide */
class TestNetworkService extends ITestNetworkManager.Stub {
    @NonNull private static final String TEST_NETWORK_LOGTAG = "TestNetworkAgent";
    @NonNull private static final String TEST_NETWORK_PROVIDER_NAME = "TestNetworkProvider";
    @NonNull private static final AtomicInteger sTestTunIndex = new AtomicInteger();

    @NonNull private final Context mContext;
    @NonNull private final INetd mNetd;

    @NonNull private final HandlerThread mHandlerThread;
    @NonNull private final Handler mHandler;

    @NonNull private final ConnectivityManager mCm;
    @NonNull private final NetworkProvider mNetworkProvider;

    // Native method stubs
    private static native int jniCreateTunTap(boolean isTun, @NonNull String iface);

    @VisibleForTesting
    protected TestNetworkService(@NonNull Context context) {
        mHandlerThread = new HandlerThread("TestNetworkServiceThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mContext = Objects.requireNonNull(context, "missing Context");
        mNetd = Objects.requireNonNull(NetdService.getInstance(), "could not get netd instance");
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mNetworkProvider = new NetworkProvider(mContext, mHandler.getLooper(),
                TEST_NETWORK_PROVIDER_NAME);
        mCm.registerNetworkProvider(mNetworkProvider);
    }

    /**
     * Create a TUN or TAP interface with the given interface name and link addresses
     *
     * <p>This method will return the FileDescriptor to the interface. Close it to tear down the
     * interface.
     */
    private TestNetworkInterface createInterface(boolean isTun, LinkAddress[] linkAddrs) {
        enforceTestNetworkPermissions(mContext);

        Objects.requireNonNull(linkAddrs, "missing linkAddrs");

        String ifacePrefix = isTun ? TEST_TUN_PREFIX : TEST_TAP_PREFIX;
        String iface = ifacePrefix + sTestTunIndex.getAndIncrement();
        final long token = Binder.clearCallingIdentity();
        try {
            ParcelFileDescriptor tunIntf =
                    ParcelFileDescriptor.adoptFd(jniCreateTunTap(isTun, iface));
            for (LinkAddress addr : linkAddrs) {
                mNetd.interfaceAddAddress(
                        iface,
                        addr.getAddress().getHostAddress(),
                        addr.getPrefixLength());
            }

            return new TestNetworkInterface(tunIntf, iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Create a TUN interface with the given interface name and link addresses
     *
     * <p>This method will return the FileDescriptor to the TUN interface. Close it to tear down the
     * TUN interface.
     */
    @Override
    public TestNetworkInterface createTunInterface(@NonNull LinkAddress[] linkAddrs) {
        return createInterface(true, linkAddrs);
    }

    /**
     * Create a TAP interface with the given interface name
     *
     * <p>This method will return the FileDescriptor to the TAP interface. Close it to tear down the
     * TAP interface.
     */
    @Override
    public TestNetworkInterface createTapInterface() {
        return createInterface(false, new LinkAddress[0]);
    }

    // Tracker for TestNetworkAgents
    @GuardedBy("mTestNetworkTracker")
    @NonNull
    private final SparseArray<TestNetworkAgent> mTestNetworkTracker = new SparseArray<>();

    public class TestNetworkAgent extends NetworkAgent implements IBinder.DeathRecipient {
        private static final int NETWORK_SCORE = 1; // Use a low, non-zero score.

        private final int mUid;

        @GuardedBy("mBinderLock")
        @NonNull
        private IBinder mBinder;

        @NonNull private final Object mBinderLock = new Object();

        private TestNetworkAgent(
                @NonNull Context context,
                @NonNull Looper looper,
                @NonNull NetworkCapabilities nc,
                @NonNull LinkProperties lp,
                @NonNull NetworkAgentConfig config,
                int uid,
                @NonNull IBinder binder,
                @NonNull NetworkProvider np)
                throws RemoteException {
            super(context, looper, TEST_NETWORK_LOGTAG, nc, lp, NETWORK_SCORE, config, np);
            mUid = uid;
            synchronized (mBinderLock) {
                mBinder = binder; // Binder null-checks in create()

                try {
                    mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                    throw e; // Abort, signal failure up the stack.
                }
            }
        }

        /**
         * If the Binder object dies, this function is called to free the resources of this
         * TestNetworkAgent
         */
        @Override
        public void binderDied() {
            teardown();
        }

        @Override
        protected void unwanted() {
            teardown();
        }

        private void teardown() {
            unregister();

            // Synchronize on mBinderLock to ensure that unlinkToDeath is never called more than
            // once (otherwise it could throw an exception)
            synchronized (mBinderLock) {
                // If mBinder is null, this Test Network has already been cleaned up.
                if (mBinder == null) return;
                mBinder.unlinkToDeath(this, 0);
                mBinder = null;
            }

            // Has to be in TestNetworkAgent to ensure all teardown codepaths properly clean up
            // resources, even for binder death or unwanted calls.
            synchronized (mTestNetworkTracker) {
                mTestNetworkTracker.remove(getNetwork().getNetId());
            }
        }
    }

    private TestNetworkAgent registerTestNetworkAgent(
            @NonNull Looper looper,
            @NonNull Context context,
            @NonNull String iface,
            @Nullable LinkProperties lp,
            boolean isMetered,
            int callingUid,
            @NonNull int[] administratorUids,
            @NonNull IBinder binder)
            throws RemoteException, SocketException {
        Objects.requireNonNull(looper, "missing Looper");
        Objects.requireNonNull(context, "missing Context");
        // iface and binder validity checked by caller

        // Build narrow set of NetworkCapabilities, useful only for testing
        NetworkCapabilities nc = new NetworkCapabilities();
        nc.clearAll(); // Remove default capabilities.
        nc.addTransportType(NetworkCapabilities.TRANSPORT_TEST);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
        nc.setNetworkSpecifier(new StringNetworkSpecifier(iface));
        nc.setAdministratorUids(administratorUids);
        if (!isMetered) {
            nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        // Build LinkProperties
        if (lp == null) {
            lp = new LinkProperties();
        } else {
            lp = new LinkProperties(lp);
            // Use LinkAddress(es) from the interface itself to minimize how much the caller
            // is trusted.
            lp.setLinkAddresses(new ArrayList<>());
        }
        lp.setInterfaceName(iface);

        // Find the currently assigned addresses, and add them to LinkProperties
        boolean allowIPv4 = false, allowIPv6 = false;
        NetworkInterface netIntf = NetworkInterface.getByName(iface);
        Objects.requireNonNull(netIntf, "No such network interface found: " + netIntf);

        for (InterfaceAddress intfAddr : netIntf.getInterfaceAddresses()) {
            lp.addLinkAddress(
                    new LinkAddress(intfAddr.getAddress(), intfAddr.getNetworkPrefixLength()));

            if (intfAddr.getAddress() instanceof Inet6Address) {
                allowIPv6 |= !intfAddr.getAddress().isLinkLocalAddress();
            } else if (intfAddr.getAddress() instanceof Inet4Address) {
                allowIPv4 = true;
            }
        }

        // Add global routes (but as non-default, non-internet providing network)
        if (allowIPv4) {
            lp.addRoute(new RouteInfo(new IpPrefix(
                    NetworkStackConstants.IPV4_ADDR_ANY, 0), null, iface));
        }
        if (allowIPv6) {
            lp.addRoute(new RouteInfo(new IpPrefix(
                    NetworkStackConstants.IPV6_ADDR_ANY, 0), null, iface));
        }

        final TestNetworkAgent agent = new TestNetworkAgent(context, looper, nc, lp,
                new NetworkAgentConfig.Builder().build(), callingUid, binder,
                mNetworkProvider);
        agent.register();
        agent.markConnected();
        return agent;
    }

    /**
     * Sets up a Network with extremely limited privileges, guarded by the MANAGE_TEST_NETWORKS
     * permission.
     *
     * <p>This method provides a Network that is useful only for testing.
     */
    @Override
    public void setupTestNetwork(
            @NonNull String iface,
            @Nullable LinkProperties lp,
            boolean isMetered,
            @NonNull int[] administratorUids,
            @NonNull IBinder binder) {
        enforceTestNetworkPermissions(mContext);

        Objects.requireNonNull(iface, "missing Iface");
        Objects.requireNonNull(binder, "missing IBinder");

        if (!(iface.startsWith(INetd.IPSEC_INTERFACE_PREFIX)
                || iface.startsWith(TEST_TUN_PREFIX))) {
            throw new IllegalArgumentException(
                    "Cannot create network for non ipsec, non-testtun interface");
        }

        try {
            final long token = Binder.clearCallingIdentity();
            try {
                NetworkStack.checkNetworkStackPermission(mContext);
                NetdUtils.setInterfaceUp(mNetd, iface);
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            // Synchronize all accesses to mTestNetworkTracker to prevent the case where:
            // 1. TestNetworkAgent successfully binds to death of binder
            // 2. Before it is added to the mTestNetworkTracker, binder dies, binderDied() is called
            // (on a different thread)
            // 3. This thread is pre-empted, put() is called after remove()
            synchronized (mTestNetworkTracker) {
                TestNetworkAgent agent =
                        registerTestNetworkAgent(
                                mHandler.getLooper(),
                                mContext,
                                iface,
                                lp,
                                isMetered,
                                Binder.getCallingUid(),
                                administratorUids,
                                binder);

                mTestNetworkTracker.put(agent.getNetwork().getNetId(), agent);
            }
        } catch (SocketException e) {
            throw new UncheckedIOException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Teardown a test network */
    @Override
    public void teardownTestNetwork(int netId) {
        enforceTestNetworkPermissions(mContext);

        final TestNetworkAgent agent;
        synchronized (mTestNetworkTracker) {
            agent = mTestNetworkTracker.get(netId);
        }

        if (agent == null) {
            return; // Already torn down
        } else if (agent.mUid != Binder.getCallingUid()) {
            throw new SecurityException("Attempted to modify other user's test networks");
        }

        // Safe to be called multiple times.
        agent.teardown();
    }

    private static final String PERMISSION_NAME =
            android.Manifest.permission.MANAGE_TEST_NETWORKS;

    public static void enforceTestNetworkPermissions(@NonNull Context context) {
        context.enforceCallingOrSelfPermission(PERMISSION_NAME, "TestNetworkService");
    }
}
