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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;
import android.net.util.SharedLog;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Service used to communicate with the network stack, which is running in a separate module.
 * @hide
 */
public class NetworkStackClient {
    private static final String TAG = NetworkStackClient.class.getSimpleName();

    private static final int NETWORKSTACK_TIMEOUT_MS = 10_000;
    private static final String IN_PROCESS_SUFFIX = ".InProcess";

    private static NetworkStackClient sInstance;

    @NonNull
    @GuardedBy("mPendingNetStackRequests")
    private final ArrayList<NetworkStackCallback> mPendingNetStackRequests = new ArrayList<>();
    @Nullable
    @GuardedBy("mPendingNetStackRequests")
    private INetworkStackConnector mConnector;

    @GuardedBy("mLog")
    private final SharedLog mLog = new SharedLog(TAG);

    private volatile boolean mNetworkStackStartRequested = false;

    private interface NetworkStackCallback {
        void onNetworkStackConnected(INetworkStackConnector connector);
    }

    private NetworkStackClient() { }

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

    private class NetworkStackConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            log("Network stack service connected");
            registerNetworkStackService(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // TODO: crash/reboot the system ?
            logWtf("Lost network stack connector", null);
        }
    };

    private void registerNetworkStackService(@NonNull IBinder service) {
        final INetworkStackConnector connector = INetworkStackConnector.Stub.asInterface(service);

        ServiceManager.addService(Context.NETWORK_STACK_SERVICE, service, false /* allowIsolated */,
                DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
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
        mNetworkStackStartRequested = true;
    }

    /**
     * Start the network stack. Should be called only once on device startup.
     *
     * <p>This method will start the network stack either in the network stack process, or inside
     * the system server on devices that do not support the network stack module. The network stack
     * connector will then be delivered asynchronously to clients that requested it before it was
     * started.
     */
    public void start(Context context) {
        log("Starting network stack");
        final PackageManager pm = context.getPackageManager();

        // Try to bind in-process if the device was shipped with an in-process version
        Intent intent = getNetworkStackIntent(pm, true /* inSystemProcess */);

        // Otherwise use the updatable module version
        if (intent == null) {
            intent = getNetworkStackIntent(pm, false /* inSystemProcess */);
            log("Starting network stack process");
        } else {
            log("Starting network stack in-process");
        }

        if (intent == null) {
            logWtf("Could not resolve the network stack", null);
            // TODO: crash/reboot system server ?
            return;
        }

        // Start the network stack. The service will be added to the service manager in
        // NetworkStackConnection.onServiceConnected().
        if (!context.bindServiceAsUser(intent, new NetworkStackConnection(),
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.SYSTEM)) {
            logWtf("Could not bind to network stack with " + intent, null);
            return;
            // TODO: crash/reboot system server if no network stack after a timeout ?
        }

        log("Network stack service start requested");
    }

    @Nullable
    private Intent getNetworkStackIntent(@NonNull PackageManager pm, boolean inSystemProcess) {
        final String baseAction = INetworkStackConnector.class.getName();
        final Intent intent =
                new Intent(inSystemProcess ? baseAction + IN_PROCESS_SUFFIX : baseAction);
        final ComponentName comp = intent.resolveSystemService(pm, 0);

        if (comp == null) {
            return null;
        }
        intent.setComponent(comp);

        int uid = -1;
        try {
            uid = pm.getPackageUidAsUser(comp.getPackageName(), UserHandle.USER_SYSTEM);
        } catch (PackageManager.NameNotFoundException e) {
            logWtf("Network stack package not found", e);
            // Fall through
        }

        final int expectedUid = inSystemProcess ? Process.SYSTEM_UID : Process.NETWORK_STACK_UID;
        if (uid != expectedUid) {
            throw new SecurityException("Invalid network stack UID: " + uid);
        }

        if (!inSystemProcess) {
            checkNetworkStackPermission(pm, comp);
        }

        return intent;
    }

    private void checkNetworkStackPermission(
            @NonNull PackageManager pm, @NonNull ComponentName comp) {
        final int hasPermission =
                pm.checkPermission(PERMISSION_MAINLINE_NETWORK_STACK, comp.getPackageName());
        if (hasPermission != PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Network stack does not have permission " + PERMISSION_MAINLINE_NETWORK_STACK);
        }
    }

    private void log(@NonNull String message) {
        synchronized (mLog) {
            mLog.log(message);
        }
    }

    private void logWtf(@NonNull String message, @Nullable Throwable e) {
        Slog.wtf(TAG, message);
        synchronized (mLog) {
            mLog.e(message, e);
        }
    }

    private void loge(@NonNull String message, @Nullable Throwable e) {
        synchronized (mLog) {
            mLog.e(message, e);
        }
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
        // TODO: PID check.
        final int caller = Binder.getCallingUid();
        if (caller != Process.SYSTEM_UID && !UserHandle.isSameApp(caller, Process.BLUETOOTH_UID)
                && !UserHandle.isSameApp(caller, Process.PHONE_UID)) {
            // Don't even attempt to obtain the connector and give a nice error message
            throw new SecurityException(
                    "Only the system server should try to bind to the network stack.");
        }

        if (!mNetworkStackStartRequested) {
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

    /**
     * Dump NetworkStackClient logs to the specified {@link PrintWriter}.
     */
    public void dump(PrintWriter pw) {
        // dump is thread-safe on SharedLog
        mLog.dump(null, pw, null);

        final int requestsQueueLength;
        synchronized (mPendingNetStackRequests) {
            requestsQueueLength = mPendingNetStackRequests.size();
        }

        pw.println();
        pw.println("pendingNetStackRequests length: " + requestsQueueLength);
    }
}
