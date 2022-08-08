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

import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Service used to communicate with the network stack, which is running in a separate module.
 * @hide
 */
public class NetworkStackClient {
    private static final String TAG = NetworkStackClient.class.getSimpleName();

    private static final int NETWORKSTACK_TIMEOUT_MS = 10_000;

    private static NetworkStackClient sInstance;

    @NonNull
    private final Dependencies mDependencies;

    @NonNull
    @GuardedBy("mPendingNetStackRequests")
    private final ArrayList<NetworkStackCallback> mPendingNetStackRequests = new ArrayList<>();
    @Nullable
    @GuardedBy("mPendingNetStackRequests")
    private INetworkStackConnector mConnector;

    private volatile boolean mWasSystemServerInitialized = false;

    private interface NetworkStackCallback {
        void onNetworkStackConnected(INetworkStackConnector connector);
    }

    @VisibleForTesting
    protected NetworkStackClient(@NonNull Dependencies dependencies) {
        mDependencies = dependencies;
    }

    private NetworkStackClient() {
        this(new DependenciesImpl());
    }

    @VisibleForTesting
    protected interface Dependencies {
        void addToServiceManager(@NonNull IBinder service);
        void checkCallerUid();
        ConnectivityModuleConnector getConnectivityModuleConnector();
    }

    private static class DependenciesImpl implements Dependencies {
        @Override
        public void addToServiceManager(@NonNull IBinder service) {
            ServiceManager.addService(Context.NETWORK_STACK_SERVICE, service,
                    false /* allowIsolated */, DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
        }

        @Override
        public void checkCallerUid() {
            final int caller = Binder.getCallingUid();
            // This is a client lib so "caller" is the current UID in most cases. The check is done
            // here in the caller's process just to provide a nicer error message to clients; more
            // generic checks are also done in NetworkStackService.
            // See PermissionUtil in NetworkStack for the actual check on the service side - the
            // checks here should be kept in sync with PermissionUtil.
            if (caller != Process.SYSTEM_UID
                    && caller != Process.NETWORK_STACK_UID
                    && UserHandle.getAppId(caller) != Process.BLUETOOTH_UID) {
                throw new SecurityException(
                        "Only the system server should try to bind to the network stack.");
            }
        }

        @Override
        public ConnectivityModuleConnector getConnectivityModuleConnector() {
            return ConnectivityModuleConnector.getInstance();
        }
    }

    /**
     * Get the NetworkStackClient singleton instance.
     */
    public static synchronized NetworkStackClient getInstance() {
        if (sInstance == null) {
            sInstance = new NetworkStackClient();
        }
        return sInstance;
    }

    /**
     * Create a DHCP server according to the specified parameters.
     *
     * <p>The server will be returned asynchronously through the provided callbacks.
     */
    public void makeDhcpServer(final String ifName, final DhcpServingParamsParcel params,
            final IDhcpServerCallbacks cb) {
        requestConnector(connector -> {
            try {
                connector.makeDhcpServer(ifName, params, cb);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        });
    }

    /**
     * Create an IpClient on the specified interface.
     *
     * <p>The IpClient will be returned asynchronously through the provided callbacks.
     */
    public void makeIpClient(String ifName, IIpClientCallbacks cb) {
        requestConnector(connector -> {
            try {
                connector.makeIpClient(ifName, cb);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        });
    }

    /**
     * Create a NetworkMonitor.
     *
     * <p>The INetworkMonitor will be returned asynchronously through the provided callbacks.
     */
    public void makeNetworkMonitor(Network network, String name, INetworkMonitorCallbacks cb) {
        requestConnector(connector -> {
            try {
                connector.makeNetworkMonitor(network, name, cb);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        });
    }

    /**
     * Get an instance of the IpMemoryStore.
     *
     * <p>The IpMemoryStore will be returned asynchronously through the provided callbacks.
     */
    public void fetchIpMemoryStore(IIpMemoryStoreCallbacks cb) {
        requestConnector(connector -> {
            try {
                connector.fetchIpMemoryStore(cb);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        });
    }

    private class NetworkStackConnection implements
            ConnectivityModuleConnector.ModuleServiceCallback {
        @Override
        public void onModuleServiceConnected(IBinder service) {
            logi("Network stack service connected");
            registerNetworkStackService(service);
        }
    }

    private void registerNetworkStackService(@NonNull IBinder service) {
        final INetworkStackConnector connector = INetworkStackConnector.Stub.asInterface(service);
        mDependencies.addToServiceManager(service);
        log("Network stack service registered");

        final ArrayList<NetworkStackCallback> requests;
        synchronized (mPendingNetStackRequests) {
            requests = new ArrayList<>(mPendingNetStackRequests);
            mPendingNetStackRequests.clear();
            mConnector = connector;
        }

        for (NetworkStackCallback r : requests) {
            r.onNetworkStackConnected(connector);
        }
    }

    /**
     * Initialize the network stack. Should be called only once on device startup, before any
     * client attempts to use the network stack.
     */
    public void init() {
        log("Network stack init");
        mWasSystemServerInitialized = true;
    }

    /**
     * Start the network stack. Should be called only once on device startup.
     *
     * <p>This method will start the network stack either in the network stack process, or inside
     * the system server on devices that do not support the network stack module. The network stack
     * connector will then be delivered asynchronously to clients that requested it before it was
     * started.
     */
    public void start() {
        mDependencies.getConnectivityModuleConnector().startModuleService(
                INetworkStackConnector.class.getName(), PERMISSION_MAINLINE_NETWORK_STACK,
                new NetworkStackConnection());
        log("Network stack service start requested");
    }

    /**
     * Log a debug message.
     */
    private void log(@NonNull String message) {
        Log.d(TAG, message);
    }

    private void logWtf(@NonNull String message, @Nullable Throwable e) {
        Slog.wtf(TAG, message);
        Log.e(TAG, message, e);
    }

    private void loge(@NonNull String message, @Nullable Throwable e) {
        Log.e(TAG, message, e);
    }

    private void logi(@NonNull String message) {
        Log.i(TAG, message);
    }

    /**
     * For non-system server clients, get the connector registered by the system server.
     */
    private INetworkStackConnector getRemoteConnector() {
        // Block until the NetworkStack connector is registered in ServiceManager.
        // <p>This is only useful for non-system processes that do not have a way to be notified of
        // registration completion. Adding a callback system would be too heavy weight considering
        // that the connector is registered on boot, so it is unlikely that a client would request
        // it before it is registered.
        // TODO: consider blocking boot on registration and simplify much of the logic in this class
        IBinder connector;
        try {
            final long before = System.currentTimeMillis();
            while ((connector = ServiceManager.getService(Context.NETWORK_STACK_SERVICE)) == null) {
                Thread.sleep(20);
                if (System.currentTimeMillis() - before > NETWORKSTACK_TIMEOUT_MS) {
                    loge("Timeout waiting for NetworkStack connector", null);
                    return null;
                }
            }
        } catch (InterruptedException e) {
            loge("Error waiting for NetworkStack connector", e);
            return null;
        }

        return INetworkStackConnector.Stub.asInterface(connector);
    }

    private void requestConnector(@NonNull NetworkStackCallback request) {
        mDependencies.checkCallerUid();

        if (!mWasSystemServerInitialized) {
            // The network stack is not being started in this process, e.g. this process is not
            // the system server. Get a remote connector registered by the system server.
            final INetworkStackConnector connector = getRemoteConnector();
            synchronized (mPendingNetStackRequests) {
                mConnector = connector;
            }
            request.onNetworkStackConnected(connector);
            return;
        }

        final INetworkStackConnector connector;
        synchronized (mPendingNetStackRequests) {
            connector = mConnector;
            if (connector == null) {
                mPendingNetStackRequests.add(request);
                return;
            }
        }

        request.onNetworkStackConnected(connector);
    }
}
