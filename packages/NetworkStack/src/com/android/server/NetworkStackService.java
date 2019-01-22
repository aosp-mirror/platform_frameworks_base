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

import static android.net.dhcp.IDhcpServer.STATUS_INVALID_ARGUMENT;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.dhcp.IDhcpServer.STATUS_UNKNOWN_ERROR;

import static com.android.server.util.PermissionUtil.checkDumpPermission;
import static com.android.server.util.PermissionUtil.checkNetworkStackCallingPermission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkStackConnector;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.PrivateDnsConfigParcel;
import android.net.dhcp.DhcpServer;
import android.net.dhcp.DhcpServingParams;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.shared.PrivateDnsConfig;
import android.net.util.SharedLog;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.connectivity.NetworkMonitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;

/**
 * Android service used to start the network stack when bound to via an intent.
 *
 * <p>The service returns a binder for the system server to communicate with the network stack.
 */
public class NetworkStackService extends Service {
    private static final String TAG = NetworkStackService.class.getSimpleName();

    /**
     * Create a binder connector for the system server to communicate with the network stack.
     *
     * <p>On platforms where the network stack runs in the system server process, this method may
     * be called directly instead of obtaining the connector by binding to the service.
     */
    public static IBinder makeConnector(Context context) {
        return new NetworkStackConnector(context);
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        return makeConnector(this);
    }

    private static class NetworkStackConnector extends INetworkStackConnector.Stub {
        private static final int NUM_VALIDATION_LOG_LINES = 20;
        private final Context mContext;
        private final ConnectivityManager mCm;

        private static final int MAX_VALIDATION_LOGS = 10;
        @GuardedBy("mValidationLogs")
        private final ArrayDeque<SharedLog> mValidationLogs = new ArrayDeque<>(MAX_VALIDATION_LOGS);

        private SharedLog addValidationLogs(Network network, String name) {
            final SharedLog log = new SharedLog(NUM_VALIDATION_LOG_LINES, network + " - " + name);
            synchronized (mValidationLogs) {
                while (mValidationLogs.size() >= MAX_VALIDATION_LOGS) {
                    mValidationLogs.removeLast();
                }
                mValidationLogs.addFirst(log);
            }
            return log;
        }

        NetworkStackConnector(Context context) {
            mContext = context;
            mCm = context.getSystemService(ConnectivityManager.class);
        }

        @NonNull
        private final SharedLog mLog = new SharedLog(TAG);

        @Override
        public void makeDhcpServer(@NonNull String ifName, @NonNull DhcpServingParamsParcel params,
                @NonNull IDhcpServerCallbacks cb) throws RemoteException {
            checkNetworkStackCallingPermission();
            final DhcpServer server;
            try {
                server = new DhcpServer(
                        ifName,
                        DhcpServingParams.fromParcelableObject(params),
                        mLog.forSubComponent(ifName + ".DHCP"));
            } catch (DhcpServingParams.InvalidParameterException e) {
                mLog.e("Invalid DhcpServingParams", e);
                cb.onDhcpServerCreated(STATUS_INVALID_ARGUMENT, null);
                return;
            } catch (Exception e) {
                mLog.e("Unknown error starting DhcpServer", e);
                cb.onDhcpServerCreated(STATUS_UNKNOWN_ERROR, null);
                return;
            }
            cb.onDhcpServerCreated(STATUS_SUCCESS, server);
        }

        @Override
        public void makeNetworkMonitor(int netId, String name, INetworkMonitorCallbacks cb)
                throws RemoteException {
            final Network network = new Network(netId, false /* privateDnsBypass */);
            final NetworkRequest defaultRequest = mCm.getDefaultRequest();
            final SharedLog log = addValidationLogs(network, name);
            final NetworkMonitor nm = new NetworkMonitor(
                    mContext, cb, network, defaultRequest, log);
            cb.onNetworkMonitorCreated(new NetworkMonitorImpl(nm));
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            checkDumpPermission();
            final IndentingPrintWriter pw = new IndentingPrintWriter(fout, "  ");
            pw.println("NetworkStack logs:");
            mLog.dump(fd, pw, args);

            pw.println();
            pw.println("Validation logs (most recent first):");
            synchronized (mValidationLogs) {
                for (SharedLog p : mValidationLogs) {
                    pw.println(p.getTag());
                    pw.increaseIndent();
                    p.dump(fd, pw, args);
                    pw.decreaseIndent();
                }
            }
        }
    }

    private static class NetworkMonitorImpl extends INetworkMonitor.Stub {
        private final NetworkMonitor mNm;

        NetworkMonitorImpl(NetworkMonitor nm) {
            mNm = nm;
        }

        @Override
        public void start() {
            checkNetworkStackCallingPermission();
            mNm.start();
        }

        @Override
        public void launchCaptivePortalApp() {
            checkNetworkStackCallingPermission();
            mNm.launchCaptivePortalApp();
        }

        @Override
        public void forceReevaluation(int uid) {
            checkNetworkStackCallingPermission();
            mNm.forceReevaluation(uid);
        }

        @Override
        public void notifyPrivateDnsChanged(PrivateDnsConfigParcel config) {
            checkNetworkStackCallingPermission();
            mNm.notifyPrivateDnsSettingsChanged(PrivateDnsConfig.fromParcel(config));
        }

        @Override
        public void notifyDnsResponse(int returnCode) {
            checkNetworkStackCallingPermission();
            mNm.notifyDnsResponse(returnCode);
        }

        @Override
        public void notifySystemReady() {
            checkNetworkStackCallingPermission();
            mNm.notifySystemReady();
        }

        @Override
        public void notifyNetworkConnected() {
            checkNetworkStackCallingPermission();
            mNm.notifyNetworkConnected();
        }

        @Override
        public void notifyNetworkDisconnected() {
            checkNetworkStackCallingPermission();
            mNm.notifyNetworkDisconnected();
        }

        @Override
        public void notifyLinkPropertiesChanged() {
            checkNetworkStackCallingPermission();
            mNm.notifyLinkPropertiesChanged();
        }

        @Override
        public void notifyNetworkCapabilitiesChanged() {
            checkNetworkStackCallingPermission();
            mNm.notifyNetworkCapabilitiesChanged();
        }
    }
}
