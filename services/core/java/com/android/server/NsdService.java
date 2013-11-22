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

import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.nsd.NsdServiceInfo;
import android.net.nsd.DnsSdTxtRecord;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.am.BatteryStatsService;
import com.android.server.NativeDaemonConnector.Command;
import com.android.internal.R;

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

    private Context mContext;
    private ContentResolver mContentResolver;
    private NsdStateMachine mNsdStateMachine;

    /**
     * Clients receiving asynchronous messages
     */
    private HashMap<Messenger, ClientInfo> mClients = new HashMap<Messenger, ClientInfo>();

    /* A map from unique id to client info */
    private SparseArray<ClientInfo> mIdToClientInfoMap= new SparseArray<ClientInfo>();

    private AsyncChannel mReplyChannel = new AsyncChannel();

    private int INVALID_ID = 0;
    private int mUniqueId = 1;

    private static final int BASE = Protocol.BASE_NSD_MANAGER;
    private static final int CMD_TO_STRING_COUNT = NsdManager.RESOLVE_SERVICE - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];

    static {
        sCmdToString[NsdManager.DISCOVER_SERVICES - BASE] = "DISCOVER";
        sCmdToString[NsdManager.STOP_DISCOVERY - BASE] = "STOP-DISCOVER";
        sCmdToString[NsdManager.REGISTER_SERVICE - BASE] = "REGISTER";
        sCmdToString[NsdManager.UNREGISTER_SERVICE - BASE] = "UNREGISTER";
        sCmdToString[NsdManager.RESOLVE_SERVICE - BASE] = "RESOLVE";
    }

    private static String cmdToString(int cmd) {
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            return sCmdToString[cmd];
        } else {
            return null;
        }
    }

    private class NsdStateMachine extends StateMachine {

        private final DefaultState mDefaultState = new DefaultState();
        private final DisabledState mDisabledState = new DisabledState();
        private final EnabledState mEnabledState = new EnabledState();

        @Override
        protected String getWhatToString(int what) {
            return cmdToString(what);
        }

        /**
         * Observes the NSD on/off setting, and takes action when changed.
         */
        private void registerForNsdSetting() {
            ContentObserver contentObserver = new ContentObserver(this.getHandler()) {
                @Override
                    public void onChange(boolean selfChange) {
                        if (isNsdEnabled()) {
                            mNsdStateMachine.sendMessage(NsdManager.ENABLE);
                        } else {
                            mNsdStateMachine.sendMessage(NsdManager.DISABLE);
                        }
                    }
            };

            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.NSD_ON),
                    false, contentObserver);
        }

        NsdStateMachine(String name) {
            super(name);
            addState(mDefaultState);
                addState(mDisabledState, mDefaultState);
                addState(mEnabledState, mDefaultState);
            if (isNsdEnabled()) {
                setInitialState(mEnabledState);
            } else {
                setInitialState(mDisabledState);
            }
            setLogRecSize(25);
            registerForNsdSetting();
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            AsyncChannel c = (AsyncChannel) msg.obj;
                            if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                            c.sendMessage(AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED);
                            ClientInfo cInfo = new ClientInfo(c, msg.replyTo);
                            mClients.put(msg.replyTo, cInfo);
                        } else {
                            Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                            Slog.e(TAG, "Send failed, client connection lost");
                        } else {
                            if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                        }
                        mClients.remove(msg.replyTo);
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
                if (mClients.size() > 0) {
                    startMDnsDaemon();
                }
            }

            @Override
            public void exit() {
                if (mClients.size() > 0) {
                    stopMDnsDaemon();
                }
            }

            private boolean requestLimitReached(ClientInfo clientInfo) {
                if (clientInfo.mClientIds.size() >= ClientInfo.MAX_LIMIT) {
                    if (DBG) Slog.d(TAG, "Exceeded max outstanding requests " + clientInfo);
                    return true;
                }
                return false;
            }

            private void storeRequestMap(int clientId, int globalId, ClientInfo clientInfo) {
                clientInfo.mClientIds.put(clientId, globalId);
                mIdToClientInfoMap.put(globalId, clientInfo);
            }

            private void removeRequestMap(int clientId, int globalId, ClientInfo clientInfo) {
                clientInfo.mClientIds.remove(clientId);
                mIdToClientInfoMap.remove(globalId);
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo clientInfo;
                NsdServiceInfo servInfo;
                boolean result = HANDLED;
                int id;
                switch (msg.what) {
                  case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        //First client
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL &&
                                mClients.size() == 0) {
                            startMDnsDaemon();
                        }
                        result = NOT_HANDLED;
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        //Last client
                        if (mClients.size() == 1) {
                            stopMDnsDaemon();
                        }
                        result = NOT_HANDLED;
                        break;
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

                        id = getUniqueId();
                        if (discoverServices(id, servInfo.getServiceType())) {
                            if (DBG) {
                                Slog.d(TAG, "Discover " + msg.arg2 + " " + id +
                                        servInfo.getServiceType());
                            }
                            storeRequestMap(msg.arg2, id, clientInfo);
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
                            id = clientInfo.mClientIds.get(msg.arg2).intValue();
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

                        id = getUniqueId();
                        if (registerService(id, (NsdServiceInfo) msg.obj)) {
                            if (DBG) Slog.d(TAG, "Register " + msg.arg2 + " " + id);
                            storeRequestMap(msg.arg2, id, clientInfo);
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
                            id = clientInfo.mClientIds.get(msg.arg2).intValue();
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

                        id = getUniqueId();
                        if (resolveService(id, servInfo)) {
                            clientInfo.mResolvedService = new NsdServiceInfo();
                            storeRequestMap(msg.arg2, id, clientInfo);
                        } else {
                            replyToMessage(msg, NsdManager.RESOLVE_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.NATIVE_DAEMON_EVENT:
                        NativeEvent event = (NativeEvent) msg.obj;
                        if (!handleNativeEvent(event.code, event.raw,
                                NativeDaemonEvent.unescapeArgs(event.raw))) {
                            result = NOT_HANDLED;
                        }
                        break;
                    default:
                        result = NOT_HANDLED;
                        break;
                }
                return result;
            }

            private boolean handleNativeEvent(int code, String raw, String[] cooked) {
                boolean handled = true;
                NsdServiceInfo servInfo;
                int id = Integer.parseInt(cooked[1]);
                ClientInfo clientInfo = mIdToClientInfoMap.get(id);
                if (clientInfo == null) {
                    Slog.e(TAG, "Unique id with no client mapping: " + id);
                    handled = false;
                    return handled;
                }

                /* This goes in response as msg.arg2 */
                int clientId = -1;
                int keyId = clientInfo.mClientIds.indexOfValue(id);
                if (keyId != -1) {
                    clientId = clientInfo.mClientIds.keyAt(keyId);
                } else {
                    // This can happen because of race conditions. For example,
                    // SERVICE_FOUND may race with STOP_SERVICE_DISCOVERY,
                    // and we may get in this situation.
                    Slog.d(TAG, "Notification for a listener that is no longer active: " + id);
                    handled = false;
                    return handled;
                }

                switch (code) {
                    case NativeResponseCode.SERVICE_FOUND:
                        /* NNN uniqueId serviceName regType domain */
                        if (DBG) Slog.d(TAG, "SERVICE_FOUND Raw: " + raw);
                        servInfo = new NsdServiceInfo(cooked[2], cooked[3], null);
                        clientInfo.mChannel.sendMessage(NsdManager.SERVICE_FOUND, 0,
                                clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_LOST:
                        /* NNN uniqueId serviceName regType domain */
                        if (DBG) Slog.d(TAG, "SERVICE_LOST Raw: " + raw);
                        servInfo = new NsdServiceInfo(cooked[2], cooked[3], null);
                        clientInfo.mChannel.sendMessage(NsdManager.SERVICE_LOST, 0,
                                clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_DISCOVERY_FAILED:
                        /* NNN uniqueId errorCode */
                        if (DBG) Slog.d(TAG, "SERVICE_DISC_FAILED Raw: " + raw);
                        clientInfo.mChannel.sendMessage(NsdManager.DISCOVER_SERVICES_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                        break;
                    case NativeResponseCode.SERVICE_REGISTERED:
                        /* NNN regId serviceName regType */
                        if (DBG) Slog.d(TAG, "SERVICE_REGISTERED Raw: " + raw);
                        servInfo = new NsdServiceInfo(cooked[2], null, null);
                        clientInfo.mChannel.sendMessage(NsdManager.REGISTER_SERVICE_SUCCEEDED,
                                id, clientId, servInfo);
                        break;
                    case NativeResponseCode.SERVICE_REGISTRATION_FAILED:
                        /* NNN regId errorCode */
                        if (DBG) Slog.d(TAG, "SERVICE_REGISTER_FAILED Raw: " + raw);
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
                        if (DBG) Slog.d(TAG, "SERVICE_RESOLVED Raw: " + raw);
                        int index = cooked[2].indexOf(".");
                        if (index == -1) {
                            Slog.e(TAG, "Invalid service found " + raw);
                            break;
                        }
                        String name = cooked[2].substring(0, index);
                        String rest = cooked[2].substring(index);
                        String type = rest.replace(".local.", "");

                        clientInfo.mResolvedService.setServiceName(name);
                        clientInfo.mResolvedService.setServiceType(type);
                        clientInfo.mResolvedService.setPort(Integer.parseInt(cooked[4]));

                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);

                        int id2 = getUniqueId();
                        if (getAddrInfo(id2, cooked[3])) {
                            storeRequestMap(clientId, id2, clientInfo);
                        } else {
                            clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                    NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    case NativeResponseCode.SERVICE_RESOLUTION_FAILED:
                        /* NNN resolveId errorCode */
                        if (DBG) Slog.d(TAG, "SERVICE_RESOLVE_FAILED Raw: " + raw);
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
                        if (DBG) Slog.d(TAG, "SERVICE_RESOLVE_FAILED Raw: " + raw);
                        clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                NsdManager.FAILURE_INTERNAL_ERROR, clientId);
                        break;
                    case NativeResponseCode.SERVICE_GET_ADDR_SUCCESS:
                        /* NNN resolveId hostname ttl addr */
                        if (DBG) Slog.d(TAG, "SERVICE_GET_ADDR_SUCCESS Raw: " + raw);
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
                        handled = false;
                        break;
                }
                return handled;
            }
       }
    }

    private NativeDaemonConnector mNativeConnector;
    private final CountDownLatch mNativeDaemonConnected = new CountDownLatch(1);

    private NsdService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        mNativeConnector = new NativeDaemonConnector(new NativeCallbackReceiver(), "mdns", 10,
                MDNS_TAG, 25);

        mNsdStateMachine = new NsdStateMachine(TAG);
        mNsdStateMachine.start();

        Thread th = new Thread(mNativeConnector, MDNS_TAG);
        th.start();
    }

    public static NsdService create(Context context) throws InterruptedException {
        NsdService service = new NsdService(context);
        service.mNativeDaemonConnected.await();
        return service;
    }

    public Messenger getMessenger() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET,
            "NsdService");
        return new Messenger(mNsdStateMachine.getHandler());
    }

    public void setEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "NsdService");
        Settings.Global.putInt(mContentResolver, Settings.Global.NSD_ON, enable ? 1 : 0);
        if (enable) {
            mNsdStateMachine.sendMessage(NsdManager.ENABLE);
        } else {
            mNsdStateMachine.sendMessage(NsdManager.DISABLE);
        }
    }

    private void sendNsdStateChangeBroadcast(boolean enabled) {
        final Intent intent = new Intent(NsdManager.ACTION_NSD_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (enabled) {
            intent.putExtra(NsdManager.EXTRA_NSD_STATE, NsdManager.NSD_STATE_ENABLED);
        } else {
            intent.putExtra(NsdManager.EXTRA_NSD_STATE, NsdManager.NSD_STATE_DISABLED);
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private boolean isNsdEnabled() {
        boolean ret = Settings.Global.getInt(mContentResolver, Settings.Global.NSD_ON, 1) == 1;
        if (DBG) Slog.d(TAG, "Network service discovery enabled " + ret);
        return ret;
    }

    private int getUniqueId() {
        if (++mUniqueId == INVALID_ID) return ++mUniqueId;
        return mUniqueId;
    }

    /* These should be in sync with system/netd/mDnsResponseCode.h */
    class NativeResponseCode {
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
    }

    private class NativeEvent {
        final int code;
        final String raw;

        NativeEvent(int code, String raw) {
            this.code = code;
            this.raw = raw;
        }
    }

    class NativeCallbackReceiver implements INativeDaemonConnectorCallbacks {
        public void onDaemonConnected() {
            mNativeDaemonConnected.countDown();
        }

        public boolean onEvent(int code, String raw, String[] cooked) {
            // TODO: NDC translates a message to a callback, we could enhance NDC to
            // directly interact with a state machine through messages
            NativeEvent event = new NativeEvent(code, raw);
            mNsdStateMachine.sendMessage(NsdManager.NATIVE_DAEMON_EVENT, event);
            return true;
        }
    }

    private boolean startMDnsDaemon() {
        if (DBG) Slog.d(TAG, "startMDnsDaemon");
        try {
            mNativeConnector.execute("mdnssd", "start-service");
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to start daemon" + e);
            return false;
        }
        return true;
    }

    private boolean stopMDnsDaemon() {
        if (DBG) Slog.d(TAG, "stopMDnsDaemon");
        try {
            mNativeConnector.execute("mdnssd", "stop-service");
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to start daemon" + e);
            return false;
        }
        return true;
    }

    private boolean registerService(int regId, NsdServiceInfo service) {
        if (DBG) Slog.d(TAG, "registerService: " + regId + " " + service);
        try {
            //Add txtlen and txtdata
            mNativeConnector.execute("mdnssd", "register", regId, service.getServiceName(),
                    service.getServiceType(), service.getPort());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to execute registerService " + e);
            return false;
        }
        return true;
    }

    private boolean unregisterService(int regId) {
        if (DBG) Slog.d(TAG, "unregisterService: " + regId);
        try {
            mNativeConnector.execute("mdnssd", "stop-register", regId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to execute unregisterService " + e);
            return false;
        }
        return true;
    }

    private boolean updateService(int regId, DnsSdTxtRecord t) {
        if (DBG) Slog.d(TAG, "updateService: " + regId + " " + t);
        try {
            if (t == null) return false;
            mNativeConnector.execute("mdnssd", "update", regId, t.size(), t.getRawData());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to updateServices " + e);
            return false;
        }
        return true;
    }

    private boolean discoverServices(int discoveryId, String serviceType) {
        if (DBG) Slog.d(TAG, "discoverServices: " + discoveryId + " " + serviceType);
        try {
            mNativeConnector.execute("mdnssd", "discover", discoveryId, serviceType);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to discoverServices " + e);
            return false;
        }
        return true;
    }

    private boolean stopServiceDiscovery(int discoveryId) {
        if (DBG) Slog.d(TAG, "stopServiceDiscovery: " + discoveryId);
        try {
            mNativeConnector.execute("mdnssd", "stop-discover", discoveryId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stopServiceDiscovery " + e);
            return false;
        }
        return true;
    }

    private boolean resolveService(int resolveId, NsdServiceInfo service) {
        if (DBG) Slog.d(TAG, "resolveService: " + resolveId + " " + service);
        try {
            mNativeConnector.execute("mdnssd", "resolve", resolveId, service.getServiceName(),
                    service.getServiceType(), "local.");
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to resolveService " + e);
            return false;
        }
        return true;
    }

    private boolean stopResolveService(int resolveId) {
        if (DBG) Slog.d(TAG, "stopResolveService: " + resolveId);
        try {
            mNativeConnector.execute("mdnssd", "stop-resolve", resolveId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stop resolve " + e);
            return false;
        }
        return true;
    }

    private boolean getAddrInfo(int resolveId, String hostname) {
        if (DBG) Slog.d(TAG, "getAdddrInfo: " + resolveId);
        try {
            mNativeConnector.execute("mdnssd", "getaddrinfo", resolveId, hostname);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to getAddrInfo " + e);
            return false;
        }
        return true;
    }

    private boolean stopGetAddrInfo(int resolveId) {
        if (DBG) Slog.d(TAG, "stopGetAdddrInfo: " + resolveId);
        try {
            mNativeConnector.execute("mdnssd", "stop-getaddrinfo", resolveId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stopGetAddrInfo " + e);
            return false;
        }
        return true;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ServiceDiscoverService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

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
        private SparseArray<Integer> mClientIds = new SparseArray<Integer>();

        private ClientInfo(AsyncChannel c, Messenger m) {
            mChannel = c;
            mMessenger = m;
            if (DBG) Slog.d(TAG, "New client, channel: " + c + " messenger: " + m);
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append("mChannel ").append(mChannel).append("\n");
            sb.append("mMessenger ").append(mMessenger).append("\n");
            sb.append("mResolvedService ").append(mResolvedService).append("\n");
            for(int i = 0; i< mClientIds.size(); i++) {
                sb.append("clientId ").append(mClientIds.keyAt(i));
                sb.append(" mDnsId ").append(mClientIds.valueAt(i)).append("\n");
            }
            return sb.toString();
        }
    }
}
