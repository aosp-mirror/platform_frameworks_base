/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.location;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubManager;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.NanoAppMessage;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A class that acts as a broker for the ContextHubClient, which handles messaging and life-cycle
 * notification callbacks. This class implements the IContextHubClient object, and the implemented
 * APIs must be thread-safe.
 *
 * TODO: Consider refactoring this class via inheritance
 *
 * @hide
 */
public class ContextHubClientBroker extends IContextHubClient.Stub
        implements IBinder.DeathRecipient {
    private static final String TAG = "ContextHubClientBroker";

    /*
     * The context of the service.
     */
    private final Context mContext;

    /*
     * The proxy to talk to the Context Hub HAL.
     */
    private final IContexthub mContextHubProxy;

    /*
     * The manager that registered this client.
     */
    private final ContextHubClientManager mClientManager;

    /*
     * The object describing the hub that this client is attached to.
     */
    private final ContextHubInfo mAttachedContextHubInfo;

    /*
     * The host end point ID of this client.
     */
    private final short mHostEndPointId;

    /*
     * The remote callback interface for this client. This will be set to null whenever the
     * client connection is closed (either explicitly or via binder death).
     */
    private IContextHubClientCallback mCallbackInterface = null;

    /*
     * True if the client is still registered with the Context Hub Service, false otherwise.
     */
    private boolean mRegistered = true;

    /*
     * Internal interface used to invoke client callbacks.
     */
    private interface CallbackConsumer {
        void accept(IContextHubClientCallback callback) throws RemoteException;
    }

    /*
     * The PendingIntent registered with this client.
     */
    private final PendingIntentRequest mPendingIntentRequest;

    /*
     * The host package associated with this client.
     */
    private final String mPackage;

    /*
     * True if a PendingIntent has been cancelled.
     */
    private AtomicBoolean mIsPendingIntentCancelled = new AtomicBoolean(false);

    /*
     * True if the application creating the client has the ACCESS_CONTEXT_HUB permission.
     */
    private final boolean mHasAccessContextHubPermission;

    /*
     * Helper class to manage registered PendingIntent requests from the client.
     */
    private class PendingIntentRequest {
        /*
         * The PendingIntent object to request, null if there is no open request.
         */
        private PendingIntent mPendingIntent;

        /*
         * The ID of the nanoapp the request is for, invalid if there is no open request.
         */
        private long mNanoAppId;

        private boolean mValid = false;

        PendingIntentRequest() {}

        PendingIntentRequest(PendingIntent pendingIntent, long nanoAppId) {
            mPendingIntent = pendingIntent;
            mNanoAppId = nanoAppId;
            mValid = true;
        }

        public long getNanoAppId() {
            return mNanoAppId;
        }

        public PendingIntent getPendingIntent() {
            return mPendingIntent;
        }

        public boolean hasPendingIntent() {
            return mPendingIntent != null;
        }

        public void clear() {
            mPendingIntent = null;
        }

        public boolean isValid() {
            return mValid;
        }
    }

    /* package */ ContextHubClientBroker(
            Context context, IContexthub contextHubProxy, ContextHubClientManager clientManager,
            ContextHubInfo contextHubInfo, short hostEndPointId,
            IContextHubClientCallback callback) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mClientManager = clientManager;
        mAttachedContextHubInfo = contextHubInfo;
        mHostEndPointId = hostEndPointId;
        mCallbackInterface = callback;
        mPendingIntentRequest = new PendingIntentRequest();
        mPackage = mContext.getPackageManager().getNameForUid(Binder.getCallingUid());

        mHasAccessContextHubPermission = context.checkCallingPermission(
            Manifest.permission.ACCESS_CONTEXT_HUB) == PERMISSION_GRANTED;
    }

    /* package */ ContextHubClientBroker(
            Context context, IContexthub contextHubProxy, ContextHubClientManager clientManager,
            ContextHubInfo contextHubInfo, short hostEndPointId, PendingIntent pendingIntent,
            long nanoAppId) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mClientManager = clientManager;
        mAttachedContextHubInfo = contextHubInfo;
        mHostEndPointId = hostEndPointId;
        mPendingIntentRequest = new PendingIntentRequest(pendingIntent, nanoAppId);
        mPackage = pendingIntent.getCreatorPackage();

        mHasAccessContextHubPermission = context.checkCallingPermission(
            Manifest.permission.ACCESS_CONTEXT_HUB) == PERMISSION_GRANTED;
    }

    /**
     * Sends from this client to a nanoapp.
     *
     * @param message the message to send
     * @return the error code of sending the message
     */
    @ContextHubTransaction.Result
    @Override
    public int sendMessageToNanoApp(NanoAppMessage message) {
        ContextHubServiceUtil.checkPermissions(mContext);

        int result;
        if (isRegistered()) {
            ContextHubMsg messageToNanoApp =
                    ContextHubServiceUtil.createHidlContextHubMessage(mHostEndPointId, message);

            int contextHubId = mAttachedContextHubInfo.getId();
            try {
                result = mContextHubProxy.sendMessageToHub(contextHubId, messageToNanoApp);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in sendMessageToNanoApp (target hub ID = "
                        + contextHubId + ")", e);
                result = Result.UNKNOWN_FAILURE;
            }
        } else {
            Log.e(TAG, "Failed to send message to nanoapp: client connection is closed");
            result = Result.UNKNOWN_FAILURE;
        }

        return ContextHubServiceUtil.toTransactionResult(result);
    }

    /**
     * Closes the connection for this client with the service.
     *
     * If the client has a PendingIntent registered, this method also unregisters it.
     */
    @Override
    public void close() {
        synchronized (this) {
            mPendingIntentRequest.clear();
        }
        onClientExit();
    }

    /**
     * Invoked when the underlying binder of this broker has died at the client process.
     */
    @Override
    public void binderDied() {
        onClientExit();
    }

    /**
     * @return the ID of the context hub this client is attached to
     */
    /* package */ int getAttachedContextHubId() {
        return mAttachedContextHubInfo.getId();
    }

    /**
     * @return the host endpoint ID of this client
     */
    /* package */ short getHostEndPointId() {
        return mHostEndPointId;
    }

    /**
     * Sends a message to the client associated with this object.
     *
     * @param message the message that came from a nanoapp
     */
    /* package */ void sendMessageToClient(NanoAppMessage message) {
        invokeCallback(callback -> callback.onMessageFromNanoApp(message));

        Supplier<Intent> supplier =
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_MESSAGE, message.getNanoAppId())
                        .putExtra(ContextHubManager.EXTRA_MESSAGE, message);
        sendPendingIntent(supplier, message.getNanoAppId());
    }

    /**
     * Notifies the client of a nanoapp load event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was loaded.
     */
    /* package */ void onNanoAppLoaded(long nanoAppId) {
        invokeCallback(callback -> callback.onNanoAppLoaded(nanoAppId));
        sendPendingIntent(
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_LOADED, nanoAppId), nanoAppId);
    }

    /**
     * Notifies the client of a nanoapp unload event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was unloaded.
     */
    /* package */ void onNanoAppUnloaded(long nanoAppId) {
        invokeCallback(callback -> callback.onNanoAppUnloaded(nanoAppId));
        sendPendingIntent(
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_UNLOADED, nanoAppId), nanoAppId);
    }

    /**
     * Notifies the client of a hub reset event if the connection is open.
     */
    /* package */ void onHubReset() {
        invokeCallback(callback -> callback.onHubReset());
        sendPendingIntent(() -> createIntent(ContextHubManager.EVENT_HUB_RESET));
    }

    /**
     * Notifies the client of a nanoapp abort event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that aborted
     * @param abortCode the nanoapp specific abort code
     */
    /* package */ void onNanoAppAborted(long nanoAppId, int abortCode) {
        invokeCallback(callback -> callback.onNanoAppAborted(nanoAppId, abortCode));

        Supplier<Intent> supplier =
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_ABORTED, nanoAppId)
                        .putExtra(ContextHubManager.EXTRA_NANOAPP_ABORT_CODE, abortCode);
        sendPendingIntent(supplier, nanoAppId);
    }

    /**
     * @param intent    the PendingIntent to compare to
     * @param nanoAppId the ID of the nanoapp of the PendingIntent to compare to
     * @return true if the given PendingIntent is currently registered, false otherwise
     */
    /* package */ boolean hasPendingIntent(PendingIntent intent, long nanoAppId) {
        PendingIntent pendingIntent = null;
        long intentNanoAppId;
        synchronized (this) {
            pendingIntent = mPendingIntentRequest.getPendingIntent();
            intentNanoAppId = mPendingIntentRequest.getNanoAppId();
        }
        return (pendingIntent != null) && pendingIntent.equals(intent)
                && intentNanoAppId == nanoAppId;
    }

    /**
     * Attaches the death recipient to the callback interface object, if any.
     *
     * @throws RemoteException if the client process already died
     */
    /* package */ void attachDeathRecipient() throws RemoteException {
        if (mCallbackInterface != null) {
            mCallbackInterface.asBinder().linkToDeath(this, 0 /* flags */);
        }
    }

    /**
     * @return true if the client is a PendingIntent client that has been cancelled.
     */
    /* package */ boolean isPendingIntentCancelled() {
        return mIsPendingIntentCancelled.get();
    }

    /**
     * Helper function to invoke a specified client callback, if the connection is open.
     *
     * @param consumer the consumer specifying the callback to invoke
     */
    private synchronized void invokeCallback(CallbackConsumer consumer) {
        if (mCallbackInterface != null) {
            try {
                consumer.accept(mCallbackInterface);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while invoking client callback (host endpoint ID = "
                        + mHostEndPointId + ")", e);
            }
        }
    }

    /**
     * Creates an Intent object containing the ContextHubManager.EXTRA_EVENT_TYPE extra field
     *
     * @param eventType the ContextHubManager.Event type describing the event
     * @return the Intent object
     */
    private Intent createIntent(int eventType) {
        Intent intent = new Intent();
        intent.putExtra(ContextHubManager.EXTRA_EVENT_TYPE, eventType);
        intent.putExtra(ContextHubManager.EXTRA_CONTEXT_HUB_INFO, mAttachedContextHubInfo);
        return intent;
    }

    /**
     * Creates an Intent object containing the ContextHubManager.EXTRA_EVENT_TYPE and the
     * ContextHubManager.EXTRA_NANOAPP_ID extra fields
     *
     * @param eventType the ContextHubManager.Event type describing the event
     * @param nanoAppId the ID of the nanoapp this event is for
     * @return the Intent object
     */
    private Intent createIntent(int eventType, long nanoAppId) {
        Intent intent = createIntent(eventType);
        intent.putExtra(ContextHubManager.EXTRA_NANOAPP_ID, nanoAppId);
        return intent;
    }

    /**
     * Sends an intent to any existing PendingIntent
     *
     * @param supplier method to create the extra Intent
     */
    private synchronized void sendPendingIntent(Supplier<Intent> supplier) {
        if (mPendingIntentRequest.hasPendingIntent()) {
            doSendPendingIntent(mPendingIntentRequest.getPendingIntent(), supplier.get());
        }
    }

    /**
     * Sends an intent to any existing PendingIntent
     *
     * @param supplier method to create the extra Intent
     * @param nanoAppId the ID of the nanoapp which this event is for
     */
    private synchronized void sendPendingIntent(Supplier<Intent> supplier, long nanoAppId) {
        if (mPendingIntentRequest.hasPendingIntent()
                && mPendingIntentRequest.getNanoAppId() == nanoAppId) {
            doSendPendingIntent(mPendingIntentRequest.getPendingIntent(), supplier.get());
        }
    }

    /**
     * Sends a PendingIntent with extra Intent data
     *
     * @param pendingIntent the PendingIntent
     * @param intent the extra Intent data
     */
    private void doSendPendingIntent(PendingIntent pendingIntent, Intent intent) {
        try {
            String requiredPermission = mHasAccessContextHubPermission
                    ? Manifest.permission.ACCESS_CONTEXT_HUB
                    : Manifest.permission.LOCATION_HARDWARE;
            pendingIntent.send(
                    mContext, 0 /* code */, intent, null /* onFinished */, null /* Handler */,
                    requiredPermission, null /* options */);
        } catch (PendingIntent.CanceledException e) {
            mIsPendingIntentCancelled.set(true);
            // The PendingIntent is no longer valid
            Log.w(TAG, "PendingIntent has been canceled, unregistering from client"
                    + " (host endpoint ID " + mHostEndPointId + ")");
            close();
        }
    }

    /**
     * @return true if the client is still registered with the service, false otherwise
     */
    private synchronized boolean isRegistered() {
        return mRegistered;
    }

    /**
     * Invoked when a client exits either explicitly or by binder death.
     */
    private synchronized void onClientExit() {
        if (mCallbackInterface != null) {
            mCallbackInterface.asBinder().unlinkToDeath(this, 0 /* flags */);
            mCallbackInterface = null;
        }
        if (!mPendingIntentRequest.hasPendingIntent() && mRegistered) {
            mClientManager.unregisterClient(mHostEndPointId);
            mRegistered = false;
        }
    }

    /**
     * Dump debugging info as ClientBrokerProto
     *
     * If the output belongs to a sub message, the caller is responsible for wrapping this function
     * between {@link ProtoOutputStream#start(long)} and {@link ProtoOutputStream#end(long)}.
     *
     * @param proto the ProtoOutputStream to write to
     */
    void dump(ProtoOutputStream proto) {
        proto.write(ClientBrokerProto.ENDPOINT_ID, getHostEndPointId());
        proto.write(ClientBrokerProto.ATTACHED_CONTEXT_HUB_ID, getAttachedContextHubId());
        proto.write(ClientBrokerProto.PACKAGE, mPackage);
        if (mPendingIntentRequest.isValid()) {
            proto.write(ClientBrokerProto.PENDING_INTENT_REQUEST_VALID, true);
            proto.write(ClientBrokerProto.NANO_APP_ID, mPendingIntentRequest.getNanoAppId());
        }
        proto.write(ClientBrokerProto.HAS_PENDING_INTENT, mPendingIntentRequest.hasPendingIntent());
        proto.write(ClientBrokerProto.PENDING_INTENT_CANCELLED, isPendingIntentCancelled());
        proto.write(ClientBrokerProto.REGISTERED, mRegistered);

    }

    @Override
    public String toString() {
        String out = "[ContextHubClient ";
        out += "endpointID: " + getHostEndPointId() + ", ";
        out += "contextHub: " + getAttachedContextHubId() + ", ";
        if (mPendingIntentRequest.isValid()) {
            out += "intentCreatorPackage: " + mPackage + ", ";
            out += "nanoAppId: 0x" + Long.toHexString(mPendingIntentRequest.getNanoAppId());
        } else {
            out += "package: " + mPackage;
        }
        out += "]";

        return out;
    }
}
