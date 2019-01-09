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

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * Service used to communicate with the network stack, which is running in a separate module.
 * @hide
 */
@SystemService(Context.NETWORK_STACK_SERVICE)
public class NetworkStack {
    private static final String TAG = NetworkStack.class.getSimpleName();

    @NonNull
    @GuardedBy("mPendingNetStackRequests")
    private final ArrayList<NetworkStackRequest> mPendingNetStackRequests = new ArrayList<>();
    @Nullable
    @GuardedBy("mPendingNetStackRequests")
    private INetworkStackConnector mConnector;

    private interface NetworkStackRequest {
        void onNetworkStackConnected(INetworkStackConnector connector);
    }

    public NetworkStack() { }

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

    private class NetworkStackConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            registerNetworkStackService(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // TODO: crash/reboot the system ?
            Slog.wtf(TAG, "Lost network stack connector");
        }
    };

    private void registerNetworkStackService(@NonNull IBinder service) {
        final INetworkStackConnector connector = INetworkStackConnector.Stub.asInterface(service);

        ServiceManager.addService(Context.NETWORK_STACK_SERVICE, service, false /* allowIsolated */,
                DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);

        final ArrayList<NetworkStackRequest> requests;
        synchronized (mPendingNetStackRequests) {
            requests = new ArrayList<>(mPendingNetStackRequests);
            mPendingNetStackRequests.clear();
            mConnector = connector;
        }

        for (NetworkStackRequest r : requests) {
            r.onNetworkStackConnected(connector);
        }
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
        // Try to bind in-process if the library is available
        IBinder connector = null;
        try {
            final Class service = Class.forName(
                    "com.android.server.NetworkStackService",
                    true /* initialize */,
                    context.getClassLoader());
            connector = (IBinder) service.getMethod("makeConnector").invoke(null);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Slog.wtf(TAG, "Could not create network stack connector from NetworkStackService");
            // TODO: crash/reboot system here ?
            return;
        } catch (ClassNotFoundException e) {
            // Normal behavior if stack is provided by the app: fall through
        }

        // In-process network stack. Add the service to the service manager here.
        if (connector != null) {
            registerNetworkStackService(connector);
            return;
        }
        // Start the network stack process. The service will be added to the service manager in
        // NetworkStackConnection.onServiceConnected().
        final Intent intent = new Intent(INetworkStackConnector.class.getName());
        final ComponentName comp = intent.resolveSystemService(context.getPackageManager(), 0);
        intent.setComponent(comp);

        if (comp == null || !context.bindServiceAsUser(intent, new NetworkStackConnection(),
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.SYSTEM)) {
            Slog.wtf(TAG,
                    "Could not bind to network stack in-process, or in app with " + intent);
            // TODO: crash/reboot system server if no network stack after a timeout ?
        }
    }

    // TODO: use this method to obtain the connector when implementing network stack operations
    private void requestConnector(@NonNull NetworkStackRequest request) {
        // TODO: PID check.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            // Don't even attempt to obtain the connector and give a nice error message
            throw new SecurityException(
                    "Only the system server should try to bind to the network stack.");
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
