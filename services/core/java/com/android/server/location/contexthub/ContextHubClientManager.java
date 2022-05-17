/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.annotation.IntDef;
import android.app.PendingIntent;
import android.content.Context;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.NanoAppMessage;
import android.os.RemoteException;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.server.location.ClientManagerProto;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A class that manages registration/unregistration of clients and manages messages to/from clients.
 *
 * @hide
 */
/* package */ class ContextHubClientManager {
    private static final String TAG = "ContextHubClientManager";

    /*
     * The DateFormat for printing RegistrationRecord.
     */
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd HH:mm:ss.SSS");

    /*
     * The maximum host endpoint ID value that a client can be assigned.
     */
    private static final int MAX_CLIENT_ID = 0x7fff;

    /*
     * Local flag to enable debug logging.
     */
    private static final boolean DEBUG_LOG_ENABLED = false;

    /*
     * The context of the service.
     */
    private final Context mContext;

    /*
     * The proxy to talk to the Context Hub.
     */
    private final IContextHubWrapper mContextHubProxy;

    /*
     * A mapping of host endpoint IDs to the ContextHubClientBroker object of registered clients.
     * A concurrent data structure is used since the registration/unregistration can occur in
     * multiple threads.
     */
    private final ConcurrentHashMap<Short, ContextHubClientBroker> mHostEndPointIdToClientMap =
            new ConcurrentHashMap<>();

    /*
     * The next host endpoint ID to start iterating for the next available host endpoint ID.
     */
    private int mNextHostEndPointId = 0;

    /*
     * The list of previous registration records.
     */
    private static final int NUM_CLIENT_RECORDS = 20;
    private final ConcurrentLinkedEvictingDeque<RegistrationRecord> mRegistrationRecordDeque =
            new ConcurrentLinkedEvictingDeque<>(NUM_CLIENT_RECORDS);

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ACTION_" }, value = {
            ACTION_REGISTERED,
            ACTION_UNREGISTERED,
            ACTION_CANCELLED,
    })
    public @interface Action {}
    public static final int ACTION_REGISTERED = 0;
    public static final int ACTION_UNREGISTERED = 1;
    public static final int ACTION_CANCELLED = 2;

    /**
     * A container class to store a record of ContextHubClient registration.
     */
    private class RegistrationRecord {
        private final String mBroker;
        private final int mAction;
        private final long mTimestamp;

        RegistrationRecord(String broker, @Action int action) {
            mBroker = broker;
            mAction = action;
            mTimestamp = System.currentTimeMillis();
        }

        void dump(ProtoOutputStream proto) {
            proto.write(ClientManagerProto.RegistrationRecord.TIMESTAMP_MS, mTimestamp);
            proto.write(ClientManagerProto.RegistrationRecord.ACTION, mAction);
            proto.write(ClientManagerProto.RegistrationRecord.BROKER, mBroker);
        }

        @Override
        public String toString() {
            String out = "";
            out += DATE_FORMAT.format(new Date(mTimestamp)) + " ";
            out += mAction == ACTION_REGISTERED ? "+ " : "- ";
            out += mBroker;
            if (mAction == ACTION_CANCELLED) {
                out += " (cancelled)";
            }
            return out;
        }
    }

    /* package */ ContextHubClientManager(Context context, IContextHubWrapper contextHubProxy) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
    }

    /**
     * Registers a new client with the service.
     *
     * @param contextHubInfo the object describing the hub this client is attached to
     * @param clientCallback the callback interface of the client to register
     * @param attributionTag an optional attribution tag within the given package
     *
     * @return the client interface
     *
     * @throws IllegalStateException if max number of clients have already registered
     */
    /* package */ IContextHubClient registerClient(
            ContextHubInfo contextHubInfo, IContextHubClientCallback clientCallback,
            String attributionTag, ContextHubTransactionManager transactionManager,
            String packageName) {
        ContextHubClientBroker broker;
        synchronized (this) {
            short hostEndPointId = getHostEndPointId();
            broker = new ContextHubClientBroker(
                    mContext, mContextHubProxy, this /* clientManager */, contextHubInfo,
                    hostEndPointId, clientCallback, attributionTag, transactionManager,
                    packageName);
            mHostEndPointIdToClientMap.put(hostEndPointId, broker);
            mRegistrationRecordDeque.add(
                    new RegistrationRecord(broker.toString(), ACTION_REGISTERED));
        }

        try {
            broker.attachDeathRecipient();
        } catch (RemoteException e) {
            // The client process has died, so we close the connection and return null
            Log.e(TAG, "Failed to attach death recipient to client");
            broker.close();
            return null;
        }

        Log.d(TAG, "Registered client with host endpoint ID " + broker.getHostEndPointId());
        return IContextHubClient.Stub.asInterface(broker);
    }

    /**
     * Registers a new client with the service.
     *
     * @param pendingIntent  the callback interface of the client to register
     * @param contextHubInfo the object describing the hub this client is attached to
     * @param nanoAppId      the ID of the nanoapp to receive Intent events for
     * @param attributionTag an optional attribution tag within the given package
     *
     * @return the client interface
     *
     * @throws IllegalStateException    if there were too many registered clients at the service
     */
    /* package */ IContextHubClient registerClient(
            ContextHubInfo contextHubInfo, PendingIntent pendingIntent, long nanoAppId,
            String attributionTag, ContextHubTransactionManager transactionManager) {
        ContextHubClientBroker broker;
        String registerString = "Regenerated";
        synchronized (this) {
            broker = getClientBroker(contextHubInfo.getId(), pendingIntent, nanoAppId);

            if (broker == null) {
                short hostEndPointId = getHostEndPointId();
                broker = new ContextHubClientBroker(
                        mContext, mContextHubProxy, this /* clientManager */, contextHubInfo,
                        hostEndPointId, pendingIntent, nanoAppId, attributionTag,
                        transactionManager);
                mHostEndPointIdToClientMap.put(hostEndPointId, broker);
                registerString = "Registered";
                mRegistrationRecordDeque.add(
                        new RegistrationRecord(broker.toString(), ACTION_REGISTERED));
            } else {
                // Update the attribution tag to the latest value provided by the client app in
                // case the app was updated and decided to change its tag.
                broker.setAttributionTag(attributionTag);
            }
        }

        Log.d(TAG, registerString + " client with host endpoint ID " + broker.getHostEndPointId());
        return IContextHubClient.Stub.asInterface(broker);
    }

    /**
     * Handles a message sent from a nanoapp.
     *
     * @param contextHubId the ID of the hub where the nanoapp sent the message from
     * @param hostEndpointId The host endpoint ID of the client that this message is for.
     * @param message the message send by a nanoapp
     * @param nanoappPermissions the set of permissions the nanoapp holds
     * @param messagePermissions the set of permissions that should be used for attributing
     * permissions when this message is consumed by a client
     */
    /* package */ void onMessageFromNanoApp(
            int contextHubId, short hostEndpointId, NanoAppMessage message,
            List<String> nanoappPermissions, List<String> messagePermissions) {
        if (DEBUG_LOG_ENABLED) {
            Log.v(TAG, "Received " + message);
        }

        if (message.isBroadcastMessage()) {
            // Broadcast messages shouldn't be sent with any permissions tagged per CHRE API
            // requirements.
            if (!messagePermissions.isEmpty()) {
                Log.wtf(TAG, "Received broadcast message with permissions from "
                        + message.getNanoAppId());
            }

            broadcastMessage(
                    contextHubId, message, nanoappPermissions, messagePermissions);
        } else {
            ContextHubClientBroker proxy = mHostEndPointIdToClientMap.get(hostEndpointId);
            if (proxy != null) {
                proxy.sendMessageToClient(
                        message, nanoappPermissions, messagePermissions);
            } else {
                Log.e(TAG, "Cannot send message to unregistered client (host endpoint ID = "
                        + hostEndpointId + ")");
            }
        }
    }

    /**
     * Unregisters a client from the service.
     *
     * This method should be invoked as a result of a client calling the ContextHubClient.close(),
     * or if the client process has died.
     *
     * @param hostEndPointId the host endpoint ID of the client that has died
     */
    /* package */ void unregisterClient(short hostEndPointId) {
        ContextHubClientBroker broker = mHostEndPointIdToClientMap.get(hostEndPointId);
        if (broker != null) {
            @Action int action =
                    broker.isPendingIntentCancelled() ? ACTION_CANCELLED : ACTION_UNREGISTERED;
            mRegistrationRecordDeque.add(new RegistrationRecord(broker.toString(), action));
        }

        if (mHostEndPointIdToClientMap.remove(hostEndPointId) != null) {
            Log.d(TAG, "Unregistered client with host endpoint ID " + hostEndPointId);
        } else {
            Log.e(TAG, "Cannot unregister non-existing client with host endpoint ID "
                    + hostEndPointId);
        }
    }

    /**
     * @param contextHubId the ID of the hub where the nanoapp was loaded
     * @param nanoAppId    the ID of the nanoapp that was loaded
     */
    /* package */ void onNanoAppLoaded(int contextHubId, long nanoAppId) {
        forEachClientOfHub(contextHubId, client -> client.onNanoAppLoaded(nanoAppId));
    }

    /**
     * @param contextHubId the ID of the hub where the nanoapp was unloaded
     * @param nanoAppId    the ID of the nanoapp that was unloaded
     */
    /* package */ void onNanoAppUnloaded(int contextHubId, long nanoAppId) {
        forEachClientOfHub(contextHubId, client -> client.onNanoAppUnloaded(nanoAppId));
    }

    /**
     * @param contextHubId the ID of the hub that has reset
     */
    /* package */ void onHubReset(int contextHubId) {
        forEachClientOfHub(contextHubId, client -> client.onHubReset());
    }

    /**
     * @param contextHubId the ID of the hub that contained the nanoapp that aborted
     * @param nanoAppId the ID of the nanoapp that aborted
     * @param abortCode the nanoapp specific abort code
     */
    /* package */ void onNanoAppAborted(int contextHubId, long nanoAppId, int abortCode) {
        forEachClientOfHub(contextHubId, client -> client.onNanoAppAborted(nanoAppId, abortCode));
    }

    /**
     * Runs a command for each client that is attached to a hub with the given ID.
     *
     * @param contextHubId the ID of the hub
     * @param callback     the command to invoke for the client
     */
    /* package */ void forEachClientOfHub(
            int contextHubId, Consumer<ContextHubClientBroker> callback) {
        for (ContextHubClientBroker broker : mHostEndPointIdToClientMap.values()) {
            if (broker.getAttachedContextHubId() == contextHubId) {
                callback.accept(broker);
            }
        }
    }

    /**
     * Returns an available host endpoint ID.
     *
     * @returns an available host endpoint ID
     *
     * @throws IllegalStateException if max number of clients have already registered
     */
    private short getHostEndPointId() {
        if (mHostEndPointIdToClientMap.size() == MAX_CLIENT_ID + 1) {
            throw new IllegalStateException("Could not register client - max limit exceeded");
        }

        int id = mNextHostEndPointId;
        for (int i = 0; i <= MAX_CLIENT_ID; i++) {
            if (!mHostEndPointIdToClientMap.containsKey((short) id)) {
                mNextHostEndPointId = (id == MAX_CLIENT_ID) ? 0 : id + 1;
                break;
            }

            id = (id == MAX_CLIENT_ID) ? 0 : id + 1;
        }

        return (short) id;
    }

    /**
     * Broadcasts a message from a nanoapp to all clients attached to the associated hub.
     *
     * @param contextHubId the ID of the hub where the nanoapp sent the message from
     * @param message      the message send by a nanoapp
     */
    private void broadcastMessage(
            int contextHubId, NanoAppMessage message, List<String> nanoappPermissions,
            List<String> messagePermissions) {
        forEachClientOfHub(contextHubId,
                client -> client.sendMessageToClient(
                        message, nanoappPermissions, messagePermissions));
    }

    /**
     * Retrieves a ContextHubClientBroker object with a matching PendingIntent and Context Hub ID.
     *
     * @param pendingIntent the PendingIntent to match
     * @param contextHubId  the ID of the Context Hub the client is attached to
     * @return the matching ContextHubClientBroker, null if not found
     */
    private ContextHubClientBroker getClientBroker(
            int contextHubId, PendingIntent pendingIntent, long nanoAppId) {
        for (ContextHubClientBroker broker : mHostEndPointIdToClientMap.values()) {
            if (broker.hasPendingIntent(pendingIntent, nanoAppId)
                    && broker.getAttachedContextHubId() == contextHubId) {
                return broker;
            }
        }

        return null;
    }

    /**
     * Dump debugging info as ClientManagerProto
     *
     * If the output belongs to a sub message, the caller is responsible for wrapping this function
     * between {@link ProtoOutputStream#start(long)} and {@link ProtoOutputStream#end(long)}.
     *
     * @param proto the ProtoOutputStream to write to
     */
    void dump(ProtoOutputStream proto) {
        for (ContextHubClientBroker broker : mHostEndPointIdToClientMap.values()) {
            long token = proto.start(ClientManagerProto.CLIENT_BROKERS);
            broker.dump(proto);
            proto.end(token);
        }
        Iterator<RegistrationRecord> it = mRegistrationRecordDeque.descendingIterator();
        while (it.hasNext()) {
            long token = proto.start(ClientManagerProto.REGISTRATION_RECORDS);
            it.next().dump(proto);
            proto.end(token);
        }
    }

    @Override
    public String toString() {
        String out = "";
        for (ContextHubClientBroker broker : mHostEndPointIdToClientMap.values()) {
            out += broker + "\n";
        }

        out += "\nRegistration history:\n";
        Iterator<RegistrationRecord> it = mRegistrationRecordDeque.descendingIterator();
        while (it.hasNext()) {
            out += it.next() + "\n";
        }

        return out;
    }
}
