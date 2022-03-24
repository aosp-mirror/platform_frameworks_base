/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.INsdManager;
import android.net.nsd.INsdManagerCallback;
import android.net.nsd.INsdServiceConnector;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.net.module.util.DnsSdTxtRecord;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Network Service Discovery Service handles remote service discovery operation requests by
 * implementing the INsdManager interface.
 *
 * @hide
 */
public class NsdService extends INsdManager.Stub {
    private static final String TAG = "NsdService";
    private static final String MDNS_TAG = "mDnsConnector";

    private static final boolean DBG = true;
    private static final long CLEANUP_DELAY_MS = 10000;
    private static final int IFACE_IDX_ANY = 0;

    private final Context mContext;
    private final NsdStateMachine mNsdStateMachine;
    private final DaemonConnection mDaemon;
    private final NativeCallbackReceiver mDaemonCallback;

    /**
     * Clients receiving asynchronous messages
     */
    private final HashMap<NsdServiceConnector, ClientInfo> mClients = new HashMap<>();

    /* A map from unique id to client info */
    private final SparseArray<ClientInfo> mIdToClientInfoMap= new SparseArray<>();

    private final long mCleanupDelayMs;

    private static final int INVALID_ID = 0;
    private int mUniqueId = 1;
    // The count of the connected legacy clients.
    private int mLegacyClientCount = 0;

    private class NsdStateMachine extends StateMachine {

        private final DefaultState mDefaultState = new DefaultState();
        private final DisabledState mDisabledState = new DisabledState();
        private final EnabledState mEnabledState = new EnabledState();

        @Override
        protected String getWhatToString(int what) {
            return NsdManager.nameOf(what);
        }

        private void maybeStartDaemon() {
            mDaemon.maybeStart();
            maybeScheduleStop();
        }

        private boolean isAnyRequestActive() {
            return mIdToClientInfoMap.size() != 0;
        }

        private void scheduleStop() {
            sendMessageDelayed(NsdManager.DAEMON_CLEANUP, mCleanupDelayMs);
        }
        private void maybeScheduleStop() {
            // The native daemon should stay alive and can't be cleanup
            // if any legacy client connected.
            if (!isAnyRequestActive() && mLegacyClientCount == 0) {
                scheduleStop();
            }
        }

        private void cancelStop() {
            this.removeMessages(NsdManager.DAEMON_CLEANUP);
        }

        NsdStateMachine(String name, Handler handler) {
            super(name, handler);
            addState(mDefaultState);
                addState(mDisabledState, mDefaultState);
                addState(mEnabledState, mDefaultState);
            State initialState = mEnabledState;
            setInitialState(initialState);
            setLogRecSize(25);
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                final ClientInfo cInfo;
                final int clientId = msg.arg2;
                switch (msg.what) {
                    case NsdManager.REGISTER_CLIENT:
                        final Pair<NsdServiceConnector, INsdManagerCallback> arg =
                                (Pair<NsdServiceConnector, INsdManagerCallback>) msg.obj;
                        final INsdManagerCallback cb = arg.second;
                        try {
                            cb.asBinder().linkToDeath(arg.first, 0);
                            cInfo = new ClientInfo(cb);
                            mClients.put(arg.first, cInfo);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Client " + clientId + " has already died");
                        }
                        break;
                    case NsdManager.UNREGISTER_CLIENT:
                        final NsdServiceConnector connector = (NsdServiceConnector) msg.obj;
                        cInfo = mClients.remove(connector);
                        if (cInfo != null) {
                            cInfo.expungeAllRequests();
                            if (cInfo.isLegacy()) {
                                mLegacyClientCount -= 1;
                            }
                        }
                        maybeScheduleStop();
                        break;
                    case NsdManager.DISCOVER_SERVICES:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onDiscoverServicesFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                       break;
                    case NsdManager.STOP_DISCOVERY:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onStopDiscoveryFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onRegisterServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.UNREGISTER_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onUnregisterServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.DAEMON_CLEANUP:
                        mDaemon.maybeStop();
                        break;
                    // This event should be only sent by the legacy (target SDK < S) clients.
                    // Mark the sending client as legacy.
                    case NsdManager.DAEMON_STARTUP:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cancelStop();
                            cInfo.setLegacy();
                            mLegacyClientCount += 1;
                            maybeStartDaemon();
                        }
                        break;
                    case NsdManager.NATIVE_DAEMON_EVENT:
                    default:
                        Log.e(TAG, "Unhandled " + msg);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            private ClientInfo getClientInfoForReply(Message msg) {
                final ListenerArgs args = (ListenerArgs) msg.obj;
                return mClients.get(args.connector);
            }
        }

        class DisabledState extends State {
            @Override
            public void enter() {
                sendNsdStateChangeBroadcast(false);
            }

            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case NsdManager.ENABLE:
                        transitionTo(mEnabledState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class EnabledState extends State {
            @Override
            public void enter() {
                sendNsdStateChangeBroadcast(true);
            }

            @Override
            public void exit() {
                // TODO: it is incorrect to stop the daemon without expunging all requests
                // and sending error callbacks to clients.
                scheduleStop();
            }

            private boolean requestLimitReached(ClientInfo clientInfo) {
                if (clientInfo.mClientIds.size() >= ClientInfo.MAX_LIMIT) {
                    if (DBG) Log.d(TAG, "Exceeded max outstanding requests " + clientInfo);
                    return true;
                }
                return false;
            }

            private void storeRequestMap(int clientId, int globalId, ClientInfo clientInfo, int what) {
                clientInfo.mClientIds.put(clientId, globalId);
                clientInfo.mClientRequests.put(clientId, what);
                mIdToClientInfoMap.put(globalId, clientInfo);
                // Remove the cleanup event because here comes a new request.
                cancelStop();
            }

            private void removeRequestMap(int clientId, int globalId, ClientInfo clientInfo) {
                clientInfo.mClientIds.delete(clientId);
                clientInfo.mClientRequests.delete(clientId);
                mIdToClientInfoMap.remove(globalId);
                maybeScheduleStop();
            }

            @Override
            public boolean processMessage(Message msg) {
                final ClientInfo clientInfo;
                final int id;
                final int clientId = msg.arg2;
                final ListenerArgs args;
                switch (msg.what) {
                    case NsdManager.DISABLE:
                        //TODO: cleanup clients
                        transitionTo(mDisabledState);
                        break;
                    case NsdManager.DISCOVER_SERVICES:
                        if (DBG) Log.d(TAG, "Discover services");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);

                        if (requestLimitReached(clientInfo)) {
                            clientInfo.onDiscoverServicesFailed(
                                    clientId, NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        maybeStartDaemon();
                        id = getUniqueId();
                        if (discoverServices(id, args.serviceInfo)) {
                            if (DBG) {
                                Log.d(TAG, "Discover " + msg.arg2 + " " + id
                                        + args.serviceInfo.getServiceType());
                            }
                            storeRequestMap(clientId, id, clientInfo, msg.what);
                            clientInfo.onDiscoverServicesStarted(clientId, args.serviceInfo);
                        } else {
                            stopServiceDiscovery(id);
                            clientInfo.onDiscoverServicesFailed(clientId,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.STOP_DISCOVERY:
                        if (DBG) Log.d(TAG, "Stop service discovery");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);

                        try {
                            id = clientInfo.mClientIds.get(clientId);
                        } catch (NullPointerException e) {
                            clientInfo.onStopDiscoveryFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            break;
                        }
                        removeRequestMap(clientId, id, clientInfo);
                        if (stopServiceDiscovery(id)) {
                            clientInfo.onStopDiscoverySucceeded(clientId);
                        } else {
                            clientInfo.onStopDiscoveryFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        if (DBG) Log.d(TAG, "Register service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        if (requestLimitReached(clientInfo)) {
                            clientInfo.onRegisterServiceFailed(
                                    clientId, NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        maybeStartDaemon();
                        id = getUniqueId();
                        if (registerService(id, args.serviceInfo)) {
                            if (DBG) Log.d(TAG, "Register " + clientId + " " + id);
                            storeRequestMap(clientId, id, clientInfo, msg.what);
                            // Return success after mDns reports success
                        } else {
                            unregisterService(id);
                            clientInfo.onRegisterServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.UNREGISTER_SERVICE:
                        if (DBG) Log.d(TAG, "unregister service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in unregistration");
                            break;
                        }
                        id = clientInfo.mClientIds.get(clientId);
                        removeRequestMap(clientId, id, clientInfo);
                        if (unregisterService(id)) {
                            clientInfo.onUnregisterServiceSucceeded(clientId);
                        } else {
                            clientInfo.onUnregisterServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        if (DBG) Log.d(TAG, "Resolve service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);

                        if (clientInfo.mResolvedService != null) {
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_ALREADY_ACTIVE);
                            break;
                        }

                        maybeStartDaemon();
                        id = getUniqueId();
                        if (resolveService(id, args.serviceInfo)) {
                            clientInfo.mResolvedService = new NsdServiceInfo();
                            storeRequestMap(clientId, id, clientInfo, msg.what);
                        } else {
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.NATIVE_DAEMON_EVENT:
                        NativeEvent event = (NativeEvent) msg.obj;
                        if (!handleNativeEvent(event.code, event.raw, event.cooked)) {
                            return NOT_HANDLED;
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            private boolean handleNativeEvent(int code, String raw, String[] cooked) {
                NsdServiceInfo servInfo;
                int id = Integer.parseInt(cooked[1]);
                ClientInfo clientInfo = mIdToClientInfoMap.get(id);
                if (clientInfo == null) {
                    String name = NativeResponseCode.nameOf(code);
                    Log.e(TAG, String.format("id %d for %s has no client mapping", id, name));
                    return false;
                }

                /* This goes in response as msg.arg2 */
                int clientId = clientInfo.getClientId(id);
                if (clientId < 0) {
                    // This can happen because of race conditions. For example,
                    // SERVICE_FOUND may race with STOP_SERVICE_DISCOVERY,
                    // and we may get in this situation.
                    String name = NativeResponseCode.nameOf(code);
                    Log.d(TAG, String.format(
                            "Notification %s for listener id %d that is no longer active",
                            name, id));
                    return false;
                }
                if (DBG) {
                    String name = NativeResponseCode.nameOf(code);
                    Log.d(TAG, String.format("Native daemon message %s: %s", name, raw));
                }
                switch (code) {
                    case NativeResponseCode.SERVICE_FOUND:
                        /* NNN uniqueId serviceName regType domain interfaceIdx netId */
                        servInfo = new NsdServiceInfo(cooked[2], cooked[3]);
                        final int foundNetId;
                        try {
                            foundNetId = Integer.parseInt(cooked[6]);
                        } catch (NumberFormatException e) {
                            Log.wtf(TAG, "Invalid network received from mdnsd: " + cooked[6]);
                            break;
                        }
                        if (foundNetId == 0L) {
                            // Ignore services that do not have a Network: they are not usable
                            // by apps, as they would need privileged permissions to use
                            // interfaces that do not have an associated Network.
                            break;
                        }
                        servInfo.setNetwork(new Network(foundNetId));
                        clientInfo.onServiceFound(clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_LOST:
                        /* NNN uniqueId serviceName regType domain interfaceIdx netId */
                        final int lostNetId;
                        try {
                            lostNetId = Integer.parseInt(cooked[6]);
                        } catch (NumberFormatException e) {
                            Log.wtf(TAG, "Invalid network received from mdnsd: " + cooked[6]);
                            break;
                        }
                        servInfo = new NsdServiceInfo(cooked[2], cooked[3]);
                        // The network could be null if it was torn down when the service is lost
                        // TODO: avoid returning null in that case, possibly by remembering found
                        // services on the same interface index and their network at the time
                        servInfo.setNetwork(lostNetId == 0 ? null : new Network(lostNetId));
                        clientInfo.onServiceLost(clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_DISCOVERY_FAILED:
                        /* NNN uniqueId errorCode */
                        clientInfo.onDiscoverServicesFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NativeResponseCode.SERVICE_REGISTERED:
                        /* NNN regId serviceName regType */
                        servInfo = new NsdServiceInfo(cooked[2], null);
                        clientInfo.onRegisterServiceSucceeded(clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_REGISTRATION_FAILED:
                        /* NNN regId errorCode */
                        clientInfo.onRegisterServiceFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NativeResponseCode.SERVICE_UPDATED:
                        /* NNN regId */
                        break;
                    case NativeResponseCode.SERVICE_UPDATE_FAILED:
                        /* NNN regId errorCode */
                        break;
                    case NativeResponseCode.SERVICE_RESOLVED:
                        /* NNN resolveId fullName hostName port txtlen txtdata interfaceIdx */
                        int index = 0;
                        while (index < cooked[2].length() && cooked[2].charAt(index) != '.') {
                            if (cooked[2].charAt(index) == '\\') {
                                ++index;
                            }
                            ++index;
                        }
                        if (index >= cooked[2].length()) {
                            Log.e(TAG, "Invalid service found " + raw);
                            break;
                        }

                        String name = cooked[2].substring(0, index);
                        String rest = cooked[2].substring(index);
                        String type = rest.replace(".local.", "");

                        name = unescape(name);

                        clientInfo.mResolvedService.setServiceName(name);
                        clientInfo.mResolvedService.setServiceType(type);
                        clientInfo.mResolvedService.setPort(Integer.parseInt(cooked[4]));
                        clientInfo.mResolvedService.setTxtRecords(cooked[6]);
                        // Network will be added after SERVICE_GET_ADDR_SUCCESS

                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);

                        int id2 = getUniqueId();
                        if (getAddrInfo(id2, cooked[3], cooked[7] /* interfaceIdx */)) {
                            storeRequestMap(clientId, id2, clientInfo, NsdManager.RESOLVE_SERVICE);
                        } else {
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    case NativeResponseCode.SERVICE_RESOLUTION_FAILED:
                        /* NNN resolveId errorCode */
                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.mResolvedService = null;
                        clientInfo.onResolveServiceFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NativeResponseCode.SERVICE_GET_ADDR_FAILED:
                        /* NNN resolveId errorCode */
                        stopGetAddrInfo(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.mResolvedService = null;
                        clientInfo.onResolveServiceFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NativeResponseCode.SERVICE_GET_ADDR_SUCCESS:
                        /* NNN resolveId hostname ttl addr interfaceIdx netId */
                        Network network = null;
                        try {
                            final int netId = Integer.parseInt(cooked[6]);
                            network = netId == 0L ? null : new Network(netId);
                        } catch (NumberFormatException e) {
                            Log.wtf(TAG, "Invalid network in GET_ADDR_SUCCESS: " + cooked[6], e);
                        }

                        InetAddress serviceHost = null;
                        try {
                            serviceHost = InetAddress.getByName(cooked[4]);
                        } catch (UnknownHostException e) {
                            Log.wtf(TAG, "Invalid host in GET_ADDR_SUCCESS", e);
                        }

                        // If the resolved service is on an interface without a network, consider it
                        // as a failure: it would not be usable by apps as they would need
                        // privileged permissions.
                        if (network != null && serviceHost != null) {
                            clientInfo.mResolvedService.setHost(serviceHost);
                            clientInfo.mResolvedService.setNetwork(network);
                            clientInfo.onResolveServiceSucceeded(
                                    clientId, clientInfo.mResolvedService);
                        } else {
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        stopGetAddrInfo(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.mResolvedService = null;
                        break;
                    default:
                        return false;
                }
                return true;
            }
       }
    }

    private String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (++i >= s.length()) {
                    Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                    break;
                }
                c = s.charAt(i);
                if (c != '.' && c != '\\') {
                    if (i + 2 >= s.length()) {
                        Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                        break;
                    }
                    c = (char) ((c-'0') * 100 + (s.charAt(i+1)-'0') * 10 + (s.charAt(i+2)-'0'));
                    i += 2;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    @VisibleForTesting
    NsdService(Context ctx, Handler handler, DaemonConnectionSupplier fn, long cleanupDelayMs) {
        mCleanupDelayMs = cleanupDelayMs;
        mContext = ctx;
        mNsdStateMachine = new NsdStateMachine(TAG, handler);
        mNsdStateMachine.start();
        mDaemonCallback = new NativeCallbackReceiver();
        mDaemon = fn.get(mDaemonCallback);
    }

    public static NsdService create(Context context) throws InterruptedException {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        NsdService service =
                new NsdService(context, handler, DaemonConnection::new, CLEANUP_DELAY_MS);
        service.mDaemonCallback.awaitConnection();
        return service;
    }

    @Override
    public INsdServiceConnector connect(INsdManagerCallback cb) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET, "NsdService");
        final INsdServiceConnector connector = new NsdServiceConnector();
        mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                NsdManager.REGISTER_CLIENT, new Pair<>(connector, cb)));
        return connector;
    }

    private static class ListenerArgs {
        public final NsdServiceConnector connector;
        public final NsdServiceInfo serviceInfo;
        ListenerArgs(NsdServiceConnector connector, NsdServiceInfo serviceInfo) {
            this.connector = connector;
            this.serviceInfo = serviceInfo;
        }
    }

    private class NsdServiceConnector extends INsdServiceConnector.Stub
            implements IBinder.DeathRecipient  {
        @Override
        public void registerService(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.REGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void unregisterService(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.UNREGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, null)));
        }

        @Override
        public void discoverServices(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.DISCOVER_SERVICES, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void stopDiscovery(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.STOP_DISCOVERY, 0, listenerKey, new ListenerArgs(this, null)));
        }

        @Override
        public void resolveService(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.RESOLVE_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void startDaemon() {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.DAEMON_STARTUP, new ListenerArgs(this, null)));
        }

        @Override
        public void binderDied() {
            mNsdStateMachine.sendMessage(
                    mNsdStateMachine.obtainMessage(NsdManager.UNREGISTER_CLIENT, this));
        }
    }

    private void sendNsdStateChangeBroadcast(boolean isEnabled) {
        final Intent intent = new Intent(NsdManager.ACTION_NSD_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        int nsdState = isEnabled ? NsdManager.NSD_STATE_ENABLED : NsdManager.NSD_STATE_DISABLED;
        intent.putExtra(NsdManager.EXTRA_NSD_STATE, nsdState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int getUniqueId() {
        if (++mUniqueId == INVALID_ID) return ++mUniqueId;
        return mUniqueId;
    }

    /* These should be in sync with system/netd/server/ResponseCode.h */
    static final class NativeResponseCode {
        public static final int SERVICE_DISCOVERY_FAILED    =   602;
        public static final int SERVICE_FOUND               =   603;
        public static final int SERVICE_LOST                =   604;

        public static final int SERVICE_REGISTRATION_FAILED =   605;
        public static final int SERVICE_REGISTERED          =   606;

        public static final int SERVICE_RESOLUTION_FAILED   =   607;
        public static final int SERVICE_RESOLVED            =   608;

        public static final int SERVICE_UPDATED             =   609;
        public static final int SERVICE_UPDATE_FAILED       =   610;

        public static final int SERVICE_GET_ADDR_FAILED     =   611;
        public static final int SERVICE_GET_ADDR_SUCCESS    =   612;

        private static final SparseArray<String> CODE_NAMES = new SparseArray<>();
        static {
            CODE_NAMES.put(SERVICE_DISCOVERY_FAILED, "SERVICE_DISCOVERY_FAILED");
            CODE_NAMES.put(SERVICE_FOUND, "SERVICE_FOUND");
            CODE_NAMES.put(SERVICE_LOST, "SERVICE_LOST");
            CODE_NAMES.put(SERVICE_REGISTRATION_FAILED, "SERVICE_REGISTRATION_FAILED");
            CODE_NAMES.put(SERVICE_REGISTERED, "SERVICE_REGISTERED");
            CODE_NAMES.put(SERVICE_RESOLUTION_FAILED, "SERVICE_RESOLUTION_FAILED");
            CODE_NAMES.put(SERVICE_RESOLVED, "SERVICE_RESOLVED");
            CODE_NAMES.put(SERVICE_UPDATED, "SERVICE_UPDATED");
            CODE_NAMES.put(SERVICE_UPDATE_FAILED, "SERVICE_UPDATE_FAILED");
            CODE_NAMES.put(SERVICE_GET_ADDR_FAILED, "SERVICE_GET_ADDR_FAILED");
            CODE_NAMES.put(SERVICE_GET_ADDR_SUCCESS, "SERVICE_GET_ADDR_SUCCESS");
        }

        static String nameOf(int code) {
            String name = CODE_NAMES.get(code);
            if (name == null) {
                return Integer.toString(code);
            }
            return name;
        }
    }

    private class NativeEvent {
        final int code;
        final String raw;
        final String[] cooked;

        NativeEvent(int code, String raw, String[] cooked) {
            this.code = code;
            this.raw = raw;
            this.cooked = cooked;
        }
    }

    class NativeCallbackReceiver implements INativeDaemonConnectorCallbacks {
        private final CountDownLatch connected = new CountDownLatch(1);

        public void awaitConnection() throws InterruptedException {
            connected.await();
        }

        @Override
        public void onDaemonConnected() {
            connected.countDown();
        }

        @Override
        public boolean onCheckHoldWakeLock(int code) {
            return false;
        }

        @Override
        public boolean onEvent(int code, String raw, String[] cooked) {
            // TODO: NDC translates a message to a callback, we could enhance NDC to
            // directly interact with a state machine through messages
            NativeEvent event = new NativeEvent(code, raw, cooked);
            mNsdStateMachine.sendMessage(NsdManager.NATIVE_DAEMON_EVENT, event);
            return true;
        }
    }

    interface DaemonConnectionSupplier {
        DaemonConnection get(NativeCallbackReceiver callback);
    }

    @VisibleForTesting
    public static class DaemonConnection {
        final NativeDaemonConnector mNativeConnector;
        boolean mIsStarted = false;

        DaemonConnection(NativeCallbackReceiver callback) {
            mNativeConnector = new NativeDaemonConnector(callback, "mdns", 10, MDNS_TAG, 25, null);
            new Thread(mNativeConnector, MDNS_TAG).start();
        }

        /**
         * Executes the specified cmd on the daemon.
         */
        public boolean execute(Object... args) {
            if (DBG) {
                Log.d(TAG, "mdnssd " + Arrays.toString(args));
            }
            try {
                mNativeConnector.execute("mdnssd", args);
            } catch (NativeDaemonConnectorException e) {
                Log.e(TAG, "Failed to execute mdnssd " + Arrays.toString(args), e);
                return false;
            }
            return true;
        }

        /**
         * Starts the daemon if it is not already started.
         */
        public void maybeStart() {
            if (mIsStarted) {
                return;
            }
            execute("start-service");
            mIsStarted = true;
        }

        /**
         * Stops the daemon if it is started.
         */
        public void maybeStop() {
            if (!mIsStarted) {
                return;
            }
            execute("stop-service");
            mIsStarted = false;
        }
    }

    private boolean registerService(int regId, NsdServiceInfo service) {
        if (DBG) {
            Log.d(TAG, "registerService: " + regId + " " + service);
        }
        String name = service.getServiceName();
        String type = service.getServiceType();
        int port = service.getPort();
        byte[] textRecord = service.getTxtRecord();
        String record = Base64.encodeToString(textRecord, Base64.DEFAULT).replace("\n", "");
        return mDaemon.execute("register", regId, name, type, port, record);
    }

    private boolean unregisterService(int regId) {
        return mDaemon.execute("stop-register", regId);
    }

    private boolean updateService(int regId, DnsSdTxtRecord t) {
        if (t == null) {
            return false;
        }
        return mDaemon.execute("update", regId, t.size(), t.getRawData());
    }

    private boolean discoverServices(int discoveryId, NsdServiceInfo serviceInfo) {
        final Network network = serviceInfo.getNetwork();
        final int discoverInterface = getNetworkInterfaceIndex(network);
        if (network != null && discoverInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to discover service on not found");
            return false;
        }
        return mDaemon.execute("discover", discoveryId, serviceInfo.getServiceType(),
                discoverInterface);
    }

    private boolean stopServiceDiscovery(int discoveryId) {
        return mDaemon.execute("stop-discover", discoveryId);
    }

    private boolean resolveService(int resolveId, NsdServiceInfo service) {
        final String name = service.getServiceName();
        final String type = service.getServiceType();
        final Network network = service.getNetwork();
        final int resolveInterface = getNetworkInterfaceIndex(network);
        if (network != null && resolveInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to resolve service on not found");
            return false;
        }
        return mDaemon.execute("resolve", resolveId, name, type, "local.", resolveInterface);
    }

    /**
     * Guess the interface to use to resolve or discover a service on a specific network.
     *
     * This is an imperfect guess, as for example the network may be gone or not yet fully
     * registered. This is fine as failing is correct if the network is gone, and a client
     * attempting to resolve/discover on a network not yet setup would have a bad time anyway; also
     * this is to support the legacy mdnsresponder implementation, which historically resolved
     * services on an unspecified network.
     */
    private int getNetworkInterfaceIndex(Network network) {
        if (network == null) return IFACE_IDX_ANY;

        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Log.wtf(TAG, "No ConnectivityManager for resolveService");
            return IFACE_IDX_ANY;
        }
        final LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return IFACE_IDX_ANY;

        // Only resolve on non-stacked interfaces
        final NetworkInterface iface;
        try {
            iface = NetworkInterface.getByName(lp.getInterfaceName());
        } catch (SocketException e) {
            Log.e(TAG, "Error querying interface", e);
            return IFACE_IDX_ANY;
        }

        if (iface == null) {
            Log.e(TAG, "Interface not found: " + lp.getInterfaceName());
            return IFACE_IDX_ANY;
        }

        return iface.getIndex();
    }

    private boolean stopResolveService(int resolveId) {
        return mDaemon.execute("stop-resolve", resolveId);
    }

    private boolean getAddrInfo(int resolveId, String hostname, String interfaceIdx) {
        // interfaceIdx is always obtained (as string) from the service resolved callback
        return mDaemon.execute("getaddrinfo", resolveId, hostname, interfaceIdx);
    }

    private boolean stopGetAddrInfo(int resolveId) {
        return mDaemon.execute("stop-getaddrinfo", resolveId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump " + TAG
                    + " due to missing android.permission.DUMP permission");
            return;
        }

        for (ClientInfo client : mClients.values()) {
            pw.println("Client Info");
            pw.println(client);
        }

        mNsdStateMachine.dump(fd, pw, args);
    }

    /* Information tracked per client */
    private class ClientInfo {

        private static final int MAX_LIMIT = 10;
        private final INsdManagerCallback mCb;
        /* Remembers a resolved service until getaddrinfo completes */
        private NsdServiceInfo mResolvedService;

        /* A map from client id to unique id sent to mDns */
        private final SparseIntArray mClientIds = new SparseIntArray();

        /* A map from client id to the type of the request we had received */
        private final SparseIntArray mClientRequests = new SparseIntArray();

        // The target SDK of this client < Build.VERSION_CODES.S
        private boolean mIsLegacy = false;

        private ClientInfo(INsdManagerCallback cb) {
            mCb = cb;
            if (DBG) Log.d(TAG, "New client");
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("mResolvedService ").append(mResolvedService).append("\n");
            sb.append("mIsLegacy ").append(mIsLegacy).append("\n");
            for(int i = 0; i< mClientIds.size(); i++) {
                int clientID = mClientIds.keyAt(i);
                sb.append("clientId ").append(clientID).
                    append(" mDnsId ").append(mClientIds.valueAt(i)).
                    append(" type ").append(mClientRequests.get(clientID)).append("\n");
            }
            return sb.toString();
        }

        private boolean isLegacy() {
            return mIsLegacy;
        }

        private void setLegacy() {
            mIsLegacy = true;
        }

        // Remove any pending requests from the global map when we get rid of a client,
        // and send cancellations to the daemon.
        private void expungeAllRequests() {
            int globalId, clientId, i;
            // TODO: to keep handler responsive, do not clean all requests for that client at once.
            for (i = 0; i < mClientIds.size(); i++) {
                clientId = mClientIds.keyAt(i);
                globalId = mClientIds.valueAt(i);
                mIdToClientInfoMap.remove(globalId);
                if (DBG) {
                    Log.d(TAG, "Terminating client-ID " + clientId
                            + " global-ID " + globalId + " type " + mClientRequests.get(clientId));
                }
                switch (mClientRequests.get(clientId)) {
                    case NsdManager.DISCOVER_SERVICES:
                        stopServiceDiscovery(globalId);
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        stopResolveService(globalId);
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        unregisterService(globalId);
                        break;
                    default:
                        break;
                }
            }
            mClientIds.clear();
            mClientRequests.clear();
        }

        // mClientIds is a sparse array of listener id -> mDnsClient id.  For a given mDnsClient id,
        // return the corresponding listener id.  mDnsClient id is also called a global id.
        private int getClientId(final int globalId) {
            int idx = mClientIds.indexOfValue(globalId);
            if (idx < 0) {
                return idx;
            }
            return mClientIds.keyAt(idx);
        }

        void onDiscoverServicesStarted(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onDiscoverServicesStarted(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesStarted", e);
            }
        }

        void onDiscoverServicesFailed(int listenerKey, int error) {
            try {
                mCb.onDiscoverServicesFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesFailed", e);
            }
        }

        void onServiceFound(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onServiceFound(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceFound(", e);
            }
        }

        void onServiceLost(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onServiceLost(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceLost(", e);
            }
        }

        void onStopDiscoveryFailed(int listenerKey, int error) {
            try {
                mCb.onStopDiscoveryFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoveryFailed", e);
            }
        }

        void onStopDiscoverySucceeded(int listenerKey) {
            try {
                mCb.onStopDiscoverySucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoverySucceeded", e);
            }
        }

        void onRegisterServiceFailed(int listenerKey, int error) {
            try {
                mCb.onRegisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceFailed", e);
            }
        }

        void onRegisterServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onRegisterServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceSucceeded", e);
            }
        }

        void onUnregisterServiceFailed(int listenerKey, int error) {
            try {
                mCb.onUnregisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceFailed", e);
            }
        }

        void onUnregisterServiceSucceeded(int listenerKey) {
            try {
                mCb.onUnregisterServiceSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceSucceeded", e);
            }
        }

        void onResolveServiceFailed(int listenerKey, int error) {
            try {
                mCb.onResolveServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceFailed", e);
            }
        }

        void onResolveServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onResolveServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceSucceeded", e);
            }
        }
    }
}
