/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.NetworkStack;
import android.net.Uri;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.net.module.util.DnsSdTxtRecord;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
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
    private static final long CLEANUP_DELAY_MS = 3000;

    private final Context mContext;
    private final NsdSettings mNsdSettings;
    private final NsdStateMachine mNsdStateMachine;
    private final DaemonConnection mDaemon;
    private final NativeCallbackReceiver mDaemonCallback;

    /**
     * Clients receiving asynchronous messages
     */
    private final HashMap<Messenger, ClientInfo> mClients = new HashMap<>();

    /* A map from unique id to client info */
    private final SparseArray<ClientInfo> mIdToClientInfoMap= new SparseArray<>();

    private final AsyncChannel mReplyChannel = new AsyncChannel();
    private final long mCleanupDelayMs;

    private static final int INVALID_ID = 0;
    private int mUniqueId = 1;

    private class NsdStateMachine extends StateMachine {

        private final DefaultState mDefaultState = new DefaultState();
        private final DisabledState mDisabledState = new DisabledState();
        private final EnabledState mEnabledState = new EnabledState();

        @Override
        protected String getWhatToString(int what) {
            return NsdManager.nameOf(what);
        }

        void maybeStartDaemon() {
            mDaemon.maybeStart();
            maybeScheduleStop();
        }

        void maybeScheduleStop() {
            if (!isAnyRequestActive()) {
                cancelStop();
                sendMessageDelayed(NsdManager.DAEMON_CLEANUP, mCleanupDelayMs);
            }
        }

        void cancelStop() {
            this.removeMessages(NsdManager.DAEMON_CLEANUP);
        }

        /**
         * Observes the NSD on/off setting, and takes action when changed.
         */
        private void registerForNsdSetting() {
            final ContentObserver contentObserver = new ContentObserver(this.getHandler()) {
                @Override
                public void onChange(boolean selfChange) {
                    notifyEnabled(isNsdEnabled());
                }
            };

            final Uri uri = Settings.Global.getUriFor(Settings.Global.NSD_ON);
            mNsdSettings.registerContentObserver(uri, contentObserver);
        }

        NsdStateMachine(String name, Handler handler) {
            super(name, handler);
            addState(mDefaultState);
                addState(mDisabledState, mDefaultState);
                addState(mEnabledState, mDefaultState);
            State initialState = isNsdEnabled() ? mEnabledState : mDisabledState;
            setInitialState(initialState);
            setLogRecSize(25);
            registerForNsdSetting();
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                ClientInfo cInfo = null;
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            AsyncChannel c = (AsyncChannel) msg.obj;
                            if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                            c.sendMessage(AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED);
                            cInfo = new ClientInfo(c, msg.replyTo);
                            mClients.put(msg.replyTo, cInfo);
                        } else {
                            Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        switch (msg.arg1) {
                            case AsyncChannel.STATUS_SEND_UNSUCCESSFUL:
                                Slog.e(TAG, "Send failed, client connection lost");
                                break;
                            case AsyncChannel.STATUS_REMOTE_DISCONNECTION:
                                if (DBG) Slog.d(TAG, "Client disconnected");
                                break;
                            default:
                                if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                                break;
                        }
                        cInfo = mClients.get(msg.replyTo);
                        if (cInfo != null) {
                            cInfo.expungeAllRequests();
                            mClients.remove(msg.replyTo);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                        AsyncChannel ac = new AsyncChannel();
                        ac.connect(mContext, getHandler(), msg.replyTo);
                        break;
                    case NsdManager.DISCOVER_SERVICES:
                        replyToMessage(msg, NsdManager.DISCOVER_SERVICES_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR);
                       break;
                    case NsdManager.STOP_DISCOVERY:
                       replyToMessage(msg, NsdManager.STOP_DISCOVERY_FAILED,
                               NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        replyToMessage(msg, NsdManager.REGISTER_SERVICE_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NsdManager.UNREGISTER_SERVICE:
                        replyToMessage(msg, NsdManager.UNREGISTER_SERVICE_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        replyToMessage(msg, NsdManager.RESOLVE_SERVICE_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case NsdManager.DAEMON_CLEANUP:
                        mDaemon.maybeStop();
                        break;
                    case NsdManager.NATIVE_DAEMON_EVENT:
                    default:
                        Slog.e(TAG, "Unhandled " + msg);
                        return NOT_HANDLED;
                }
                return HANDLED;
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
                maybeScheduleStop();
            }

            private boolean requestLimitReached(ClientInfo clientInfo) {
                if (clientInfo.mClientIds.size() >= ClientInfo.MAX_LIMIT) {
                    if (DBG) Slog.d(TAG, "Exceeded max outstanding requests " + clientInfo);
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
                ClientInfo clientInfo;
                NsdServiceInfo servInfo;
                int id;
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        return NOT_HANDLED;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        return NOT_HANDLED;
                }

                switch (msg.what) {
                    case NsdManager.DISABLE:
                        //TODO: cleanup clients
                        transitionTo(mDisabledState);
                        break;
                    case NsdManager.DISCOVER_SERVICES:
                        if (DBG) Slog.d(TAG, "Discover services");
                        servInfo = (NsdServiceInfo) msg.obj;
                        clientInfo = mClients.get(msg.replyTo);

                        if (requestLimitReached(clientInfo)) {
                            replyToMessage(msg, NsdManager.DISCOVER_SERVICES_FAILED,
                                    NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        maybeStartDaemon();
                        id = getUniqueId();
                        if (discoverServices(id, servInfo.getServiceType())) {
                            if (DBG) {
                                Slog.d(TAG, "Discover " + msg.arg2 + " " + id +
                                        servInfo.getServiceType());
                            }
                            storeRequestMap(msg.arg2, id, clientInfo, msg.what);
                            replyToMessage(msg, NsdManager.DISCOVER_SERVICES_STARTED, servInfo);
                        } else {
                            stopServiceDiscovery(id);
                            replyToMessage(msg, NsdManager.DISCOVER_SERVICES_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.STOP_DISCOVERY:
                        if (DBG) Slog.d(TAG, "Stop service discovery");
                        clientInfo = mClients.get(msg.replyTo);

                        try {
                            id = clientInfo.mClientIds.get(msg.arg2);
                        } catch (NullPointerException e) {
                            replyToMessage(msg, NsdManager.STOP_DISCOVERY_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                            break;
                        }
                        removeRequestMap(msg.arg2, id, clientInfo);
                        if (stopServiceDiscovery(id)) {
                            replyToMessage(msg, NsdManager.STOP_DISCOVERY_SUCCEEDED);
                        } else {
                            replyToMessage(msg, NsdManager.STOP_DISCOVERY_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        if (DBG) Slog.d(TAG, "Register service");
                        clientInfo = mClients.get(msg.replyTo);
                        if (requestLimitReached(clientInfo)) {
                            replyToMessage(msg, NsdManager.REGISTER_SERVICE_FAILED,
                                    NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        maybeStartDaemon();
                        id = getUniqueId();
                        if (registerService(id, (NsdServiceInfo) msg.obj)) {
                            if (DBG) Slog.d(TAG, "Register " + msg.arg2 + " " + id);
                            storeRequestMap(msg.arg2, id, clientInfo, msg.what);
                            // Return success after mDns reports success
                        } else {
                            unregisterService(id);
                            replyToMessage(msg, NsdManager.REGISTER_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.UNREGISTER_SERVICE:
                        if (DBG) Slog.d(TAG, "unregister service");
                        clientInfo = mClients.get(msg.replyTo);
                        try {
                            id = clientInfo.mClientIds.get(msg.arg2);
                        } catch (NullPointerException e) {
                            replyToMessage(msg, NsdManager.UNREGISTER_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                            break;
                        }
                        removeRequestMap(msg.arg2, id, clientInfo);
                        if (unregisterService(id)) {
                            replyToMessage(msg, NsdManager.UNREGISTER_SERVICE_SUCCEEDED);
                        } else {
                            replyToMessage(msg, NsdManager.UNREGISTER_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        if (DBG) Slog.d(TAG, "Resolve service");
                        servInfo = (NsdServiceInfo) msg.obj;
                        clientInfo = mClients.get(msg.replyTo);


                        if (clientInfo.mResolvedService != null) {
                            replyToMessage(msg, NsdManager.RESOLVE_SERVICE_FAILED,
                                    NsdManager.FAILURE_ALREADY_ACTIVE);
                            break;
                        }

                        maybeStartDaemon();
                        id = getUniqueId();
                        if (resolveService(id, servInfo)) {
                            clientInfo.mResolvedService = new NsdServiceInfo();
                            storeRequestMap(msg.arg2, id, clientInfo, msg.what);
                        } else {
                            replyToMessage(msg, NsdManager.RESOLVE_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
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
                    Slog.e(TAG, String.format("id %d for %s has no client mapping", id, name));
                    return false;
                }

                /* This goes in response as msg.arg2 */
                int clientId = clientInfo.getClientId(id);
                if (clientId < 0) {
                    // This can happen because of race conditions. For example,
                    // SERVICE_FOUND may race with STOP_SERVICE_DISCOVERY,
                    // and we may get in this situation.
                    String name = NativeResponseCode.nameOf(code);
                    Slog.d(TAG, String.format(
                            "Notification %s for listener id %d that is no longer active",
                            name, id));
                    return false;
                }
                if (DBG) {
                    String name = NativeResponseCode.nameOf(code);
                    Slog.d(TAG, String.format("Native daemon message %s: %s", name, raw));
                }
                switch (code) {
                    case NativeResponseCode.SERVICE_FOUND:
                        /* NNN uniqueId serviceName regType domain */
                        servInfo = new NsdServiceInfo(cooked[2], cooked[3]);
                        clientInfo.mChannel.sendMessage(NsdManager.SERVICE_FOUND, 0,
                                clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_LOST:
                        /* NNN uniqueId serviceName regType domain */
                        servInfo = new NsdServiceInfo(cooked[2], cooked[3]);
                        clientInfo.mChannel.sendMessage(NsdManager.SERVICE_LOST, 0,
                                clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_DISCOVERY_FAILED:
                        /* NNN uniqueId errorCode */
                        clientInfo.mChannel.sendMessage(NsdManager.DISCOVER_SERVICES_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                        break;
                    case NativeResponseCode.SERVICE_REGISTERED:
                        /* NNN regId serviceName regType */
                        servInfo = new NsdServiceInfo(cooked[2], null);
                        clientInfo.mChannel.sendMessage(NsdManager.REGISTER_SERVICE_SUCCEEDED,
                                id, clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_REGISTRATION_FAILED:
                        /* NNN regId errorCode */
                        clientInfo.mChannel.sendMessage(NsdManager.REGISTER_SERVICE_FAILED,
                               NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                        break;
                    case NativeResponseCode.SERVICE_UPDATED:
                        /* NNN regId */
                        break;
                    case NativeResponseCode.SERVICE_UPDATE_FAILED:
                        /* NNN regId errorCode */
                        break;
                    case NativeResponseCode.SERVICE_RESOLVED:
                        /* NNN resolveId fullName hostName port txtlen txtdata */
                        int index = 0;
                        while (index < cooked[2].length() && cooked[2].charAt(index) != '.') {
                            if (cooked[2].charAt(index) == '\\') {
                                ++index;
                            }
                            ++index;
                        }
                        if (index >= cooked[2].length()) {
                            Slog.e(TAG, "Invalid service found " + raw);
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

                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);

                        int id2 = getUniqueId();
                        if (getAddrInfo(id2, cooked[3])) {
                            storeRequestMap(clientId, id2, clientInfo, NsdManager.RESOLVE_SERVICE);
                        } else {
                            clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    case NativeResponseCode.SERVICE_RESOLUTION_FAILED:
                        /* NNN resolveId errorCode */
                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.mResolvedService = null;
                        clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                        break;
                    case NativeResponseCode.SERVICE_GET_ADDR_FAILED:
                        /* NNN resolveId errorCode */
                        stopGetAddrInfo(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.mResolvedService = null;
                        clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                        break;
                    case NativeResponseCode.SERVICE_GET_ADDR_SUCCESS:
                        /* NNN resolveId hostname ttl addr */
                        try {
                            clientInfo.mResolvedService.setHost(InetAddress.getByName(cooked[4]));
                            clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_SUCCEEDED,
                                   0, clientId, clientInfo.mResolvedService);
                        } catch (java.net.UnknownHostException e) {
                            clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR, clientId);
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

    private boolean isAnyRequestActive() {
        return mIdToClientInfoMap.size() != 0;
    }

    private String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (++i >= s.length()) {
                    Slog.e(TAG, "Unexpected end of escape sequence in: " + s);
                    break;
                }
                c = s.charAt(i);
                if (c != '.' && c != '\\') {
                    if (i + 2 >= s.length()) {
                        Slog.e(TAG, "Unexpected end of escape sequence in: " + s);
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
    NsdService(Context ctx, NsdSettings settings, Handler handler,
            DaemonConnectionSupplier fn, long cleanupDelayMs) {
        mCleanupDelayMs = cleanupDelayMs;
        mContext = ctx;
        mNsdSettings = settings;
        mNsdStateMachine = new NsdStateMachine(TAG, handler);
        mNsdStateMachine.start();
        mDaemonCallback = new NativeCallbackReceiver();
        mDaemon = fn.get(mDaemonCallback);
    }

    public static NsdService create(Context context) throws InterruptedException {
        NsdSettings settings = NsdSettings.makeDefault(context);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        NsdService service = new NsdService(context, settings, handler,
                DaemonConnection::new, CLEANUP_DELAY_MS);
        service.mDaemonCallback.awaitConnection();
        return service;
    }

    public Messenger getMessenger() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET, "NsdService");
        return new Messenger(mNsdStateMachine.getHandler());
    }

    public void setEnabled(boolean isEnabled) {
        NetworkStack.checkNetworkStackPermission(mContext);
        mNsdSettings.putEnabledStatus(isEnabled);
        notifyEnabled(isEnabled);
    }

    private void notifyEnabled(boolean isEnabled) {
        mNsdStateMachine.sendMessage(isEnabled ? NsdManager.ENABLE : NsdManager.DISABLE);
    }

    private void sendNsdStateChangeBroadcast(boolean isEnabled) {
        final Intent intent = new Intent(NsdManager.ACTION_NSD_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        int nsdState = isEnabled ? NsdManager.NSD_STATE_ENABLED : NsdManager.NSD_STATE_DISABLED;
        intent.putExtra(NsdManager.EXTRA_NSD_STATE, nsdState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private boolean isNsdEnabled() {
        boolean ret = mNsdSettings.isEnabled();
        if (DBG) {
            Slog.d(TAG, "Network service discovery is " + (ret ? "enabled" : "disabled"));
        }
        return ret;
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
                Slog.d(TAG, "mdnssd " + Arrays.toString(args));
            }
            try {
                mNativeConnector.execute("mdnssd", args);
            } catch (NativeDaemonConnectorException e) {
                Slog.e(TAG, "Failed to execute mdnssd " + Arrays.toString(args), e);
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
            Slog.d(TAG, "registerService: " + regId + " " + service);
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

    private boolean discoverServices(int discoveryId, String serviceType) {
        return mDaemon.execute("discover", discoveryId, serviceType);
    }

    private boolean stopServiceDiscovery(int discoveryId) {
        return mDaemon.execute("stop-discover", discoveryId);
    }

    private boolean resolveService(int resolveId, NsdServiceInfo service) {
        String name = service.getServiceName();
        String type = service.getServiceType();
        return mDaemon.execute("resolve", resolveId, name, type, "local.");
    }

    private boolean stopResolveService(int resolveId) {
        return mDaemon.execute("stop-resolve", resolveId);
    }

    private boolean getAddrInfo(int resolveId, String hostname) {
        return mDaemon.execute("getaddrinfo", resolveId, hostname);
    }

    private boolean stopGetAddrInfo(int resolveId) {
        return mDaemon.execute("stop-getaddrinfo", resolveId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        for (ClientInfo client : mClients.values()) {
            pw.println("Client Info");
            pw.println(client);
        }

        mNsdStateMachine.dump(fd, pw, args);
    }

    /* arg2 on the source message has an id that needs to be retained in replies
     * see NsdManager for details */
    private Message obtainMessage(Message srcMsg) {
        Message msg = Message.obtain();
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /* Information tracked per client */
    private class ClientInfo {

        private static final int MAX_LIMIT = 10;
        private final AsyncChannel mChannel;
        private final Messenger mMessenger;
        /* Remembers a resolved service until getaddrinfo completes */
        private NsdServiceInfo mResolvedService;

        /* A map from client id to unique id sent to mDns */
        private final SparseIntArray mClientIds = new SparseIntArray();

        /* A map from client id to the type of the request we had received */
        private final SparseIntArray mClientRequests = new SparseIntArray();

        private ClientInfo(AsyncChannel c, Messenger m) {
            mChannel = c;
            mMessenger = m;
            if (DBG) Slog.d(TAG, "New client, channel: " + c + " messenger: " + m);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("mChannel ").append(mChannel).append("\n");
            sb.append("mMessenger ").append(mMessenger).append("\n");
            sb.append("mResolvedService ").append(mResolvedService).append("\n");
            for(int i = 0; i< mClientIds.size(); i++) {
                int clientID = mClientIds.keyAt(i);
                sb.append("clientId ").append(clientID).
                    append(" mDnsId ").append(mClientIds.valueAt(i)).
                    append(" type ").append(mClientRequests.get(clientID)).append("\n");
            }
            return sb.toString();
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
                if (DBG) Slog.d(TAG, "Terminating client-ID " + clientId +
                        " global-ID " + globalId + " type " + mClientRequests.get(clientId));
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
            mNsdStateMachine.maybeScheduleStop();
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
    }

    /**
     * Interface which encapsulates dependencies of NsdService that are hard to mock, hard to
     * override, or have side effects on global state in unit tests.
     */
    @VisibleForTesting
    public interface NsdSettings {
        boolean isEnabled();
        void putEnabledStatus(boolean isEnabled);
        void registerContentObserver(Uri uri, ContentObserver observer);

        static NsdSettings makeDefault(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            return new NsdSettings() {
                @Override
                public boolean isEnabled() {
                    return Settings.Global.getInt(resolver, Settings.Global.NSD_ON, 1) == 1;
                }

                @Override
                public void putEnabledStatus(boolean isEnabled) {
                    Settings.Global.putInt(resolver, Settings.Global.NSD_ON, isEnabled ? 1 : 0);
                }

                @Override
                public void registerContentObserver(Uri uri, ContentObserver observer) {
                    resolver.registerContentObserver(uri, false, observer);
                }
            };
        }
    }
}
