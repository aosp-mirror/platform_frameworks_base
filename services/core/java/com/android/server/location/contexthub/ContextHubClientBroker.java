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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.hardware.location.ContextHubManager.AUTHORIZATION_DENIED;
import static android.hardware.location.ContextHubManager.AUTHORIZATION_DENIED_GRACE_PERIOD;
import static android.hardware.location.ContextHubManager.AUTHORIZATION_GRANTED;

import android.Manifest;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.chre.flags.Flags;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.content.Intent;
import android.hardware.contexthub.ErrorCode;
import android.hardware.contexthub.HostEndpointInfo;
import android.hardware.contexthub.MessageDeliveryStatus;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubManager;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.IContextHubTransactionCallback;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.ClientBrokerProto;

import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A class that acts as a broker for the ContextHubClient, which handles messaging and life-cycle
 * notification callbacks. This class implements the IContextHubClient object, and the implemented
 * APIs must be thread-safe.
 *
 * Additionally, this class is responsible for enforcing permissions usage and attribution are
 * handled appropriately for a given client. In general, this works as follows:
 *
 * Client sending a message to a nanoapp:
 * 1) When initially sending a message to nanoapps, clients are by default in a grace period state
 *    which allows them to always send their first message to nanoapps. This is done to allow
 *    clients (especially callback clients) to reset their conection to the nanoapp if they are
 *    killed / restarted (e.g. following a permission revocation).
 * 2) After the initial message is sent, a check of permissions state is performed. If the
 *    client doesn't have permissions to communicate, it is placed into the denied grace period
 *    state and notified so that it can clean up its communication before it is completely denied
 *    access.
 * 3) For subsequent messages, the auth state is checked synchronously and messages are denied if
 *    the client is denied authorization
 *
 * Client receiving a message from a nanoapp:
 * 1) If a nanoapp sends a message to the client, the authentication state is checked synchronously.
 *    If there has been no message between the two before, the auth state is assumed granted.
 * 2) The broker then checks that the client has all permissions the nanoapp requires and attributes
 *    all permissions required to consume the message being sent. If both of those checks pass, then
 *    the message is delivered. Otherwise, it's dropped.
 *
 * Client losing or gaining permissions (callback client):
 * 1) Clients are killed when they lose permissions. This will cause callback clients to completely
 *    disconnect from the service. When they are restarted, their initial message will still be
 *    be allowed through and their permissions will be rechecked at that time.
 * 2) If they gain a permission, the broker will notify them if that permission allows them to
 *    communicate with a nanoapp again.
 *
 * Client losing or gaining permissions (PendingIntent client):
 * 1) Unlike callback clients, PendingIntent clients are able to maintain their connection to the
 *    service when they are killed. In their case, they will receive notifications of the broker
 *    that they have been denied required permissions or gain required permissions.
 *
 * TODO: Consider refactoring this class via inheritance
 *
 * @hide
 */
public class ContextHubClientBroker extends IContextHubClient.Stub
        implements IBinder.DeathRecipient,
                AppOpsManager.OnOpChangedListener,
                PendingIntent.OnFinished {
    private static final String TAG = "ContextHubClientBroker";

    /**
     * Internal only authorization value used when the auth state is unknown.
     */
    private static final int AUTHORIZATION_UNKNOWN = -1;

    /**
     * Message used by noteOp when this client receives a message from a nanoapp.
     */
    private static final String RECEIVE_MSG_NOTE = "NanoappMessageDelivery ";

    /**
     * For clients targeting S and above, a SecurityException is thrown when they are in the denied
     * authorization state and attempt to send a message to a nanoapp.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long CHANGE_ID_AUTH_STATE_DENIED = 181350407L;
    private static final long WAKELOCK_TIMEOUT_MILLIS = 5 * 1000;

    /*
     * The context of the service.
     */
    private final Context mContext;

    /*
     * The proxy to talk to the Context Hub HAL.
     */
    private final IContextHubWrapper mContextHubProxy;

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
    private IContextHubClientCallback mContextHubClientCallback;

    /*
     * True if the client is still registered with the Context Hub Service, false otherwise.
     */
    private boolean mRegistered = true;

    /**
     * String containing an attribution tag that was denoted in the {@link Context} of the creator
     * of this broker. This is used when attributing the permissions usage of the broker.
     */
    @Nullable private String mAttributionTag;

    /** Wakelock held while nanoapp message are in flight to the client */
    @GuardedBy("mWakeLock")
    private final WakeLock mWakeLock;

    /**
     * True if {@link #mWakeLock} is open for acquisition. It is set to false after the client is
     * unregistered.
     */
    private AtomicBoolean mIsWakelockUsable = new AtomicBoolean(true);

    /*
     * Internal interface used to invoke client callbacks.
     */
    @VisibleForTesting
    interface CallbackConsumer {
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

    /**
     * The PID associated with this client.
     */
    private final int mPid;

    /**
     * The UID associated with this client.
     */
    private final int mUid;

    /**
     * Manager used for noting permissions usage of this broker.
     */
    private final AppOpsManager mAppOpsManager;

    /**
     * Manager used to queue transactions to the context hub.
     */
    private final ContextHubTransactionManager mTransactionManager;

    /*
     * True if a PendingIntent has been cancelled.
     */
    private final AtomicBoolean mIsPendingIntentCancelled = new AtomicBoolean(false);

    /**
     * True if a permissions query has been issued and is being processed. Used to prevent too many
     * queries from being issued by a single client at once.
     */
    private final AtomicBoolean mIsPermQueryIssued = new AtomicBoolean(false);

    /*
     * Map containing all nanoapps this client has a messaging channel with and whether it is
     * allowed to communicate over that channel. A channel is defined to have been opened if the
     * client has sent or received messages from the particular nanoapp.
     */
    private final Map<Long, Integer> mMessageChannelNanoappIdMap = new ConcurrentHashMap<>();

    /**
     * Set containing all nanoapps that have been forcefully transitioned to the denied
     * authorization state (via CLI) to ensure they don't transition back to the granted state
     * later if, for example, a permission check is performed due to another nanoapp
     */
    private final Set<Long> mForceDeniedNapps = new HashSet<>();

    /**
     * Map containing all nanoapps that have active auth state denial timers.
     */
    private final Map<Long, AuthStateDenialTimer> mNappToAuthTimerMap = new ConcurrentHashMap<>();

    /**
     * Callback used to obtain the latest set of nanoapp permissions and verify this client has
     * each nanoapps permissions granted.
     */
    private final IContextHubTransactionCallback mQueryPermsCallback =
            new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {}

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
                mIsPermQueryIssued.set(false);
                if (result != ContextHubTransaction.RESULT_SUCCESS && nanoAppStateList != null) {
                    Log.e(TAG, "Permissions query failed, but still received nanoapp state");
                } else if (nanoAppStateList != null) {
                    for (NanoAppState state : nanoAppStateList) {
                        if (mMessageChannelNanoappIdMap.containsKey(state.getNanoAppId())) {
                            List<String> permissions = state.getNanoAppPermissions();
                            updateNanoAppAuthState(state.getNanoAppId(),
                                    permissions, false /* gracePeriodExpired */);
                        }
                    }
                }
            }
        };

    /*
     * Helper class to manage registered PendingIntent requests from the client.
     */
    private static class PendingIntentRequest {
        /*
         * The PendingIntent object to request, null if there is no open request.
         */
        private PendingIntent mPendingIntent;

        /*
         * The ID of the nanoapp the request is for, invalid if there is no open request.
         */
        private long mNanoAppId;

        private boolean mValid = false;

        PendingIntentRequest() {
        }

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

    private ContextHubClientBroker(
            Context context,
            IContextHubWrapper contextHubProxy,
            ContextHubClientManager clientManager,
            ContextHubInfo contextHubInfo,
            short hostEndPointId,
            IContextHubClientCallback callback,
            String attributionTag,
            ContextHubTransactionManager transactionManager,
            PendingIntent pendingIntent,
            long nanoAppId,
            String packageName) {
        mContext = context;
        mContextHubProxy = contextHubProxy;
        mClientManager = clientManager;
        mAttachedContextHubInfo = contextHubInfo;
        mHostEndPointId = hostEndPointId;
        mContextHubClientCallback = callback;
        if (pendingIntent == null) {
            mPendingIntentRequest = new PendingIntentRequest();
        } else {
            mPendingIntentRequest = new PendingIntentRequest(pendingIntent, nanoAppId);
        }

        if (packageName == null) {
            String[] packages = mContext.getPackageManager().getPackagesForUid(
                    Binder.getCallingUid());
            if (packages != null && packages.length > 0) {
                packageName = packages[0];
            }
            Log.e(TAG, "createClient: Provided package name null. Using first package name "
                    + packageName);
        }

        mPackage = packageName;
        mAttributionTag = attributionTag;
        mTransactionManager = transactionManager;

        mPid = Binder.getCallingPid();
        mUid = Binder.getCallingUid();
        mAppOpsManager = context.getSystemService(AppOpsManager.class);

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setWorkSource(new WorkSource(mUid, mPackage));
        mWakeLock.setReferenceCounted(true);

        startMonitoringOpChanges();
        sendHostEndpointConnectedEvent();
    }

    ContextHubClientBroker(
            Context context,
            IContextHubWrapper contextHubProxy,
            ContextHubClientManager clientManager,
            ContextHubInfo contextHubInfo,
            short hostEndPointId,
            IContextHubClientCallback callback,
            String attributionTag,
            ContextHubTransactionManager transactionManager,
            String packageName) {
        this(
                context,
                contextHubProxy,
                clientManager,
                contextHubInfo,
                hostEndPointId,
                callback,
                attributionTag,
                transactionManager,
                /* pendingIntent= */ null,
                /* nanoAppId= */ 0,
                packageName);
    }

    ContextHubClientBroker(
            Context context,
            IContextHubWrapper contextHubProxy,
            ContextHubClientManager clientManager,
            ContextHubInfo contextHubInfo,
            short hostEndPointId,
            PendingIntent pendingIntent,
            long nanoAppId,
            String attributionTag,
            ContextHubTransactionManager transactionManager) {
        this(
                context,
                contextHubProxy,
                clientManager,
                contextHubInfo,
                hostEndPointId,
                /* callback= */ null,
                attributionTag,
                transactionManager,
                pendingIntent,
                nanoAppId,
                pendingIntent.getCreatorPackage());
    }

    private void startMonitoringOpChanges() {
        mAppOpsManager.startWatchingMode(AppOpsManager.OP_NONE, mPackage, this);
    }

    /**
     * Sends from this client to a nanoapp.
     *
     * @param message the message to send
     * @return the error code of sending the message
     * @throws SecurityException if this client doesn't have permissions to send a message to the
     * nanoapp
     */
    @ContextHubTransaction.Result
    @Override
    public int sendMessageToNanoApp(NanoAppMessage message) {
        return doSendMessageToNanoApp(message, /* transactionCallback= */ null);
    }

    /**
     * Sends a reliable message rom this client to a nanoapp.
     *
     * @param message the message to send
     * @param transactionCallback The callback to use to confirm the delivery of the message for
     *        reliable messages.
     * @return the error code of sending the message
     * @throws SecurityException if this client doesn't have permissions to send a message to the
     * nanoapp
     */
    @ContextHubTransaction.Result
    @Override
    public int sendReliableMessageToNanoApp(NanoAppMessage message,
            IContextHubTransactionCallback transactionCallback) {
        return doSendMessageToNanoApp(message, transactionCallback);
    }

    /**
     * See sendReliableMessageToNanoApp().
     */
    @ContextHubTransaction.Result
    private int doSendMessageToNanoApp(NanoAppMessage message,
            @Nullable IContextHubTransactionCallback transactionCallback) {
        ContextHubServiceUtil.checkPermissions(mContext);

        @ContextHubTransaction.Result int result;
        if (isRegistered()) {
            int authState = mMessageChannelNanoappIdMap.getOrDefault(
                    message.getNanoAppId(), AUTHORIZATION_UNKNOWN);
            if (authState == AUTHORIZATION_DENIED) {
                if (Compatibility.isChangeEnabled(CHANGE_ID_AUTH_STATE_DENIED)) {
                    throw new SecurityException("Client doesn't have valid permissions to send"
                            + " message to " + message.getNanoAppId());
                }
                // Return a bland error code for apps targeting old SDKs since they wouldn't be able
                // to use an error code added in S.
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
            } else if (authState == AUTHORIZATION_UNKNOWN) {
                // Only check permissions the first time a nanoapp is queried since nanoapp
                // permissions don't currently change at runtime. If the host permission changes
                // later, that'll be checked by onOpChanged.
                checkNanoappPermsAsync();
            }

            if (!Flags.reliableMessageImplementation() || transactionCallback == null) {
                try {
                    result = mContextHubProxy.sendMessageToContextHub(mHostEndPointId,
                            mAttachedContextHubInfo.getId(), message);
                } catch (RemoteException e) {
                    Log.e(TAG,
                            "RemoteException in sendMessageToNanoApp (target hub ID = "
                                    + mAttachedContextHubInfo.getId() + ")",
                            e);
                    result = ContextHubTransaction.RESULT_FAILED_UNKNOWN;
                }
            } else {
                result = ContextHubTransaction.RESULT_SUCCESS;
                ContextHubServiceTransaction transaction =
                        mTransactionManager.createMessageTransaction(mHostEndPointId,
                                mAttachedContextHubInfo.getId(), message, transactionCallback,
                                getPackageName());
                try {
                    mTransactionManager.addTransaction(transaction);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Unable to add a transaction in sendMessageToNanoApp "
                            + "(target hub ID = " + mAttachedContextHubInfo.getId() + ")", e);
                    result = ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE;
                }
            }

            ContextHubEventLogger.getInstance().logMessageToNanoapp(
                    mAttachedContextHubInfo.getId(),
                    message,
                    result == ContextHubTransaction.RESULT_SUCCESS);
        } else {
            String messageString = Base64.getEncoder().encodeToString(message.getMessageBody());
            Log.e(TAG, String.format(
                    "Failed to send message (connection closed): hostEndpointId= %1$d payload %2$s",
                    mHostEndPointId, messageString));
            result = ContextHubTransaction.RESULT_FAILED_UNKNOWN;
        }

        return result;
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

    @Override
    public int getId() {
        return mHostEndPointId;
    }

    /**
     * Invoked when the underlying binder of this broker has died at the client process.
     */
    @Override
    public void binderDied() {
        onClientExit();
    }

    @Override
    public void onOpChanged(String op, String packageName) {
        if (packageName.equals(mPackage)) {
            if (!mMessageChannelNanoappIdMap.isEmpty()) {
                checkNanoappPermsAsync();
            }
        }
    }

    String getPackageName() {
        return mPackage;
    }

    @VisibleForTesting
    boolean isWakelockUsable() {
        synchronized (mWakeLock) {
            return mIsWakelockUsable.get();
        }
    }

    @VisibleForTesting
    WakeLock getWakeLock() {
        synchronized (mWakeLock) {
            return mWakeLock;
        }
    }

    /**
     * Used to override the attribution tag with a newer value if a PendingIntent broker is
     * retrieved.
     */
    void setAttributionTag(String attributionTag) {
        mAttributionTag = attributionTag;
    }

    /**
     * @return the attribution tag associated with this broker.
     */
    String getAttributionTag() {
        return mAttributionTag;
    }

    /**
     * @return the ID of the context hub this client is attached to
     */
    int getAttachedContextHubId() {
        return mAttachedContextHubInfo.getId();
    }

    /**
     * @return the host endpoint ID of this client
     */
    short getHostEndPointId() {
        return mHostEndPointId;
    }

    /**
     * Sends a message to the client associated with this object. This function will call
     * onFinishedCallback when the operation is complete if the message is reliable.
     *
     * @param message the message that came from a nanoapp
     * @param nanoappPermissions permissions required to communicate with the nanoapp sending this
     *     message
     * @param messagePermissions permissions required to consume the message being delivered. These
     *     permissions are what will be attributed to the client through noteOp.
     * @return An error from ErrorCode
     */
    byte sendMessageToClient(
            NanoAppMessage message,
            List<String> nanoappPermissions,
            List<String> messagePermissions) {
        long nanoAppId = message.getNanoAppId();

        int authState =
                updateNanoAppAuthState(
                        nanoAppId, nanoappPermissions, false /* gracePeriodExpired */);

        // If in the grace period, the host may not receive any messages containing permissions
        // covered data.
        if (authState == AUTHORIZATION_DENIED_GRACE_PERIOD && !messagePermissions.isEmpty()) {
            Log.e(TAG, "Dropping message from " + Long.toHexString(nanoAppId) + ". " + mPackage
                    + " in grace period and napp msg has permissions");
            return ErrorCode.PERMISSION_DENIED;
        }

        // If in the grace period, don't check permissions state since it'll cause cleanup
        // messages to be dropped.
        if (authState == AUTHORIZATION_DENIED
                || !notePermissions(messagePermissions, RECEIVE_MSG_NOTE + nanoAppId)) {
            Log.e(TAG, "Dropping message from " + Long.toHexString(nanoAppId) + ". " + mPackage
                    + " doesn't have permission");
            return ErrorCode.PERMISSION_DENIED;
        }

        byte errorCode = invokeCallback(callback -> callback.onMessageFromNanoApp(message));
        if (errorCode != ErrorCode.OK) {
            return errorCode;
        }

        Supplier<Intent> supplier =
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_MESSAGE, nanoAppId)
                        .putExtra(ContextHubManager.EXTRA_MESSAGE, message);
        Consumer<Byte> onFinishedCallback = (Byte error) ->
                sendMessageDeliveryStatusToContextHub(message.getMessageSequenceNumber(), error);
        return sendPendingIntent(supplier, nanoAppId,
                Flags.reliableMessageImplementation() && message.isReliable()
                        ? onFinishedCallback
                        : null);
    }

    /**
     * Notifies the client of a nanoapp load event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was loaded.
     */
    void onNanoAppLoaded(long nanoAppId) {
        // Check the latest state to see if the loaded nanoapp's permissions changed such that the
        // host app can communicate with it again.
        checkNanoappPermsAsync();

        invokeCallback(callback -> callback.onNanoAppLoaded(nanoAppId));
        sendPendingIntent(
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_LOADED, nanoAppId), nanoAppId);
    }

    /**
     * Notifies the client of a nanoapp unload event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that was unloaded.
     */
    void onNanoAppUnloaded(long nanoAppId) {
        invokeCallback(callback -> callback.onNanoAppUnloaded(nanoAppId));
        sendPendingIntent(
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_UNLOADED, nanoAppId), nanoAppId);
    }

    /** Notifies the client of a hub reset event if the connection is open. */
    void onHubReset() {
        invokeCallback(IContextHubClientCallback::onHubReset);
        sendPendingIntent(() -> createIntent(ContextHubManager.EVENT_HUB_RESET));

        // Re-send the host endpoint connected event as the Context Hub restarted.
        sendHostEndpointConnectedEvent();
    }

    /**
     * Notifies the client of a nanoapp abort event if the connection is open.
     *
     * @param nanoAppId the ID of the nanoapp that aborted
     * @param abortCode the nanoapp specific abort code
     */
    void onNanoAppAborted(long nanoAppId, int abortCode) {
        invokeCallback(callback -> callback.onNanoAppAborted(nanoAppId, abortCode));

        Supplier<Intent> supplier =
                () -> createIntent(ContextHubManager.EVENT_NANOAPP_ABORTED, nanoAppId)
                        .putExtra(ContextHubManager.EXTRA_NANOAPP_ABORT_CODE, abortCode);
        sendPendingIntent(supplier, nanoAppId);
    }

    /**
     * @param intent the PendingIntent to compare to
     * @param nanoAppId the ID of the nanoapp of the PendingIntent to compare to
     * @return true if the given PendingIntent is currently registered, false otherwise
     */
    boolean hasPendingIntent(PendingIntent intent, long nanoAppId) {
        PendingIntent pendingIntent;
        long intentNanoAppId;
        synchronized (this) {
            pendingIntent = mPendingIntentRequest.getPendingIntent();
            intentNanoAppId = mPendingIntentRequest.getNanoAppId();
        }
        return (pendingIntent != null)
                && pendingIntent.equals(intent)
                && intentNanoAppId == nanoAppId;
    }

    /**
     * Attaches the death recipient to the callback interface object, if any.
     *
     * @throws RemoteException if the client process already died
     */
    void attachDeathRecipient() throws RemoteException {
        if (mContextHubClientCallback != null) {
            mContextHubClientCallback.asBinder().linkToDeath(this, 0 /* flags */);
        }
    }

    /**
     * Checks that this client has all of the provided permissions.
     *
     * @param permissions list of permissions to check
     * @return true if the client has all of the permissions granted
     */
    boolean hasPermissions(List<String> permissions) {
        for (String permission : permissions) {
            if (mContext.checkPermission(permission, mPid, mUid) != PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Attributes the provided permissions to the package of this client.
     *
     * @param permissions list of permissions covering data the client is about to receive
     * @param noteMessage message that should be noted alongside permissions attribution to
     *     facilitate debugging
     * @return true if client has ability to use all of the provided permissions
     */
    boolean notePermissions(List<String> permissions, String noteMessage) {
        for (String permission : permissions) {
            int opCode = AppOpsManager.permissionToOpCode(permission);
            if (opCode != AppOpsManager.OP_NONE) {
                try {
                    if (mAppOpsManager.noteOp(opCode, mUid, mPackage, mAttributionTag, noteMessage)
                            != AppOpsManager.MODE_ALLOWED) {
                        return false;
                    }
                } catch (SecurityException e) {
                    Log.e(
                            TAG,
                            "SecurityException: noteOp for pkg "
                                    + mPackage
                                    + " opcode "
                                    + opCode
                                    + ": "
                                    + e.getMessage());
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * @return true if the client is a PendingIntent client that has been cancelled.
     */
    boolean isPendingIntentCancelled() {
        return mIsPendingIntentCancelled.get();
    }

    /**
     * Handles timer expiry for a client whose auth state with a nanoapp was previously in the grace
     * period.
     */
    void handleAuthStateTimerExpiry(long nanoAppId) {
        AuthStateDenialTimer timer;
        synchronized (mMessageChannelNanoappIdMap) {
            timer = mNappToAuthTimerMap.remove(nanoAppId);
        }

        if (timer != null) {
            updateNanoAppAuthState(
                    nanoAppId,
                    Collections.emptyList() /* nanoappPermissions */,
                    true /* gracePeriodExpired */);
        }
    }

    /**
     * Verifies this client has the permissions to communicate with all of the nanoapps it has
     * communicated with in the past.
     */
    private void checkNanoappPermsAsync() {
        if (!mIsPermQueryIssued.getAndSet(true)) {
            ContextHubServiceTransaction transaction = mTransactionManager.createQueryTransaction(
                    mAttachedContextHubInfo.getId(), mQueryPermsCallback, mPackage);
            mTransactionManager.addTransaction(transaction);
        }
    }

    private int updateNanoAppAuthState(
            long nanoAppId, List<String> nanoappPermissions, boolean gracePeriodExpired) {
        return updateNanoAppAuthState(
                nanoAppId, nanoappPermissions, gracePeriodExpired,
                false /* forceDenied */);
    }

    /**
     * Updates the latest authenticatication state for the given nanoapp.
     *
     * @param nanoAppId the nanoapp that's auth state is being updated
     * @param nanoappPermissions the Android permissions required to communicate with the nanoapp
     * @param gracePeriodExpired indicates whether this invocation is a result of the grace period
     *         expiring
     * @param forceDenied indicates that no matter what auth state is asssociated with this nanoapp
     *         it should transition to denied
     * @return the latest auth state as of the completion of this method.
     */
    int updateNanoAppAuthState(
            long nanoAppId,
            List<String> nanoappPermissions,
            boolean gracePeriodExpired,
            boolean forceDenied) {
        int curAuthState;
        int newAuthState;
        synchronized (mMessageChannelNanoappIdMap) {
            // Check permission granted state synchronously since this method can be invoked from
            // multiple threads.
            boolean hasPermissions = hasPermissions(nanoappPermissions);

            curAuthState = mMessageChannelNanoappIdMap.getOrDefault(
                    nanoAppId, AUTHORIZATION_UNKNOWN);
            if (curAuthState == AUTHORIZATION_UNKNOWN) {
                // If there's never been an auth check performed, start the state as granted so the
                // appropriate state transitions occur below and clients don't receive a granted
                // callback if they're determined to be in the granted state initially.
                curAuthState = AUTHORIZATION_GRANTED;
                mMessageChannelNanoappIdMap.put(nanoAppId, AUTHORIZATION_GRANTED);
            }

            newAuthState = curAuthState;
            // The below logic ensures that only the following transitions are possible:
            // GRANTED -> DENIED_GRACE_PERIOD only if permissions have been lost
            // DENIED_GRACE_PERIOD -> DENIED only if the grace period expires
            // DENIED/DENIED_GRACE_PERIOD -> GRANTED only if permissions are granted again
            // any state -> DENIED if "forceDenied" is true
            if (forceDenied || mForceDeniedNapps.contains(nanoAppId)) {
                newAuthState = AUTHORIZATION_DENIED;
                mForceDeniedNapps.add(nanoAppId);
            } else if (gracePeriodExpired) {
                if (curAuthState == AUTHORIZATION_DENIED_GRACE_PERIOD) {
                    newAuthState = AUTHORIZATION_DENIED;
                }
            } else {
                if (curAuthState == AUTHORIZATION_GRANTED && !hasPermissions) {
                    newAuthState = AUTHORIZATION_DENIED_GRACE_PERIOD;
                } else if (curAuthState != AUTHORIZATION_GRANTED && hasPermissions) {
                    newAuthState = AUTHORIZATION_GRANTED;
                }
            }

            if (newAuthState != AUTHORIZATION_DENIED_GRACE_PERIOD) {
                AuthStateDenialTimer timer = mNappToAuthTimerMap.remove(nanoAppId);
                if (timer != null) {
                    timer.cancel();
                }
            } else if (curAuthState == AUTHORIZATION_GRANTED) {
                AuthStateDenialTimer timer =
                        new AuthStateDenialTimer(this, nanoAppId, Looper.getMainLooper());
                mNappToAuthTimerMap.put(nanoAppId, timer);
                timer.start();
            }

            if (curAuthState != newAuthState) {
                mMessageChannelNanoappIdMap.put(nanoAppId, newAuthState);
            }
        }
        if (curAuthState != newAuthState) {
            // Don't send the callback in the synchronized block or it could end up in a deadlock.
            sendAuthStateCallback(nanoAppId, newAuthState);
        }
        return newAuthState;
    }

    private void sendAuthStateCallback(long nanoAppId, int authState) {
        invokeCallback(callback -> callback.onClientAuthorizationChanged(nanoAppId, authState));

        Supplier<Intent> supplier =
                () -> createIntent(ContextHubManager.EVENT_CLIENT_AUTHORIZATION, nanoAppId)
                        .putExtra(ContextHubManager.EXTRA_CLIENT_AUTHORIZATION_STATE, authState);
        sendPendingIntent(supplier, nanoAppId);
    }

    /**
     * Helper function to invoke a specified client callback, if the connection is open.
     *
     * @param consumer the consumer specifying the callback to invoke
     * @return the ErrorCode for this operation
     */
    private synchronized byte invokeCallback(CallbackConsumer consumer) {
        if (mContextHubClientCallback != null) {
            try {
                acquireWakeLock();
                consumer.accept(mContextHubClientCallback);
            } catch (RemoteException e) {
                Log.e(
                        TAG,
                        "RemoteException while invoking client callback (host endpoint ID = "
                                + mHostEndPointId
                                + ")",
                        e);
                return ErrorCode.PERMANENT_ERROR;
            }
        }
        return ErrorCode.OK;
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
     * Sends an intent to any existing PendingIntent.
     *
     * @param supplier method to create the extra Intent.
     * @return the ErrorCode indicating the status of sending the intent.
     * ErrorCode.TRANSIENT_ERROR indicates there is no intent.
     */
    private synchronized byte sendPendingIntent(Supplier<Intent> supplier) {
        if (mPendingIntentRequest.hasPendingIntent()) {
            return doSendPendingIntent(mPendingIntentRequest.getPendingIntent(), supplier.get(),
                    this);
        }
        return ErrorCode.OK;
    }

    /**
     * Sends an intent to any existing PendingIntent.
     *
     * @param supplier method to create the extra Intent.
     * @param nanoAppId the ID of the nanoapp which this event is for.
     * @return the ErrorCode indicating the status of sending the intent.
     * ErrorCode.TRANSIENT_ERROR indicates there is no intent.
     */
    private synchronized byte sendPendingIntent(Supplier<Intent> supplier, long nanoAppId) {
        return sendPendingIntent(supplier, nanoAppId, null);
    }

    /**
     * Sends an intent to any existing PendingIntent. This function will set the onFinishedCallback
     * to be called when the pending intent is sent or upon a failure.
     *
     * @param supplier method to create the extra Intent.
     * @param nanoAppId the ID of the nanoapp which this event is for.
     * @param onFinishedCallback the callback called when the operation is finished.
     * @return the ErrorCode indicating the status of sending the intent.
     * ErrorCode.TRANSIENT_ERROR indicates there is no intent.
     */
    private synchronized byte sendPendingIntent(Supplier<Intent> supplier, long nanoAppId,
            Consumer<Byte> onFinishedCallback) {
        if (mPendingIntentRequest.hasPendingIntent()
                && mPendingIntentRequest.getNanoAppId() == nanoAppId) {
            ContextHubClientBroker broker = this;
            PendingIntent.OnFinished onFinished = new PendingIntent.OnFinished() {
                @Override
                public void onSendFinished(
                        PendingIntent pendingIntent,
                        Intent intent,
                        int resultCode,
                        String resultData,
                        Bundle resultExtras) {
                    if (onFinishedCallback != null) {
                        onFinishedCallback.accept(resultCode == 0
                                ? ErrorCode.OK
                                : ErrorCode.TRANSIENT_ERROR);
                    }

                    broker.onSendFinished(pendingIntent, intent, resultCode, resultData,
                            resultExtras);
                }
            };

            return doSendPendingIntent(mPendingIntentRequest.getPendingIntent(), supplier.get(),
                    onFinished);
        }
        return ErrorCode.OK;
    }

    /**
     * Sends a PendingIntent with extra Intent data.
     *
     * @param pendingIntent the PendingIntent.
     * @param intent the extra Intent data.
     * @return the ErrorCode indicating the status of sending the intent.
     */
    @VisibleForTesting
    byte doSendPendingIntent(
            PendingIntent pendingIntent,
            Intent intent,
            PendingIntent.OnFinished onFinishedCallback) {
        try {
            String requiredPermission = Manifest.permission.ACCESS_CONTEXT_HUB;
            acquireWakeLock();
            pendingIntent.send(
                    mContext,
                    /* code= */ 0,
                    intent,
                    /* onFinished= */ onFinishedCallback,
                    /* handler= */ null,
                    requiredPermission,
                    /* options= */ null);
            return ErrorCode.OK;
        } catch (PendingIntent.CanceledException e) {
            mIsPendingIntentCancelled.set(true);
            // The PendingIntent is no longer valid
            Log.w(
                    TAG,
                    "PendingIntent has been canceled, unregistering from client"
                            + " (host endpoint ID "
                            + mHostEndPointId
                            + ")");
            close();
            return ErrorCode.PERMANENT_ERROR;
        }
    }

    /**
     * @return true if the client is still registered with the service, false otherwise
     */
    private synchronized boolean isRegistered() {
        return mRegistered;
    }

    /** Invoked when a client exits either explicitly or by binder death. */
    private synchronized void onClientExit() {
        if (mContextHubClientCallback != null) {
            mContextHubClientCallback.asBinder().unlinkToDeath(this, 0 /* flags */);
            mContextHubClientCallback = null;
        }
        // The client is only unregistered and cleared when there is NOT any PendingIntent
        if (!mPendingIntentRequest.hasPendingIntent() && mRegistered) {
            mClientManager.unregisterClient(mHostEndPointId);
            mRegistered = false;
            mAppOpsManager.stopWatchingMode(this);
            mContextHubProxy.onHostEndpointDisconnected(mHostEndPointId);
            releaseWakeLockOnExit();
        }
    }

    private String authStateToString(@ContextHubManager.AuthorizationState int state) {
        switch (state) {
            case AUTHORIZATION_DENIED:
                return "DENIED";
            case AUTHORIZATION_DENIED_GRACE_PERIOD:
                return "DENIED_GRACE_PERIOD";
            case AUTHORIZATION_GRANTED:
                return "GRANTED";
            default:
                return "UNKNOWN";
        }
    }

    private void sendHostEndpointConnectedEvent() {
        HostEndpointInfo info = new HostEndpointInfo();
        info.hostEndpointId = (char) mHostEndPointId;
        info.packageName = mPackage;
        info.attributionTag = mAttributionTag;
        info.type = (mUid == Process.SYSTEM_UID)
             ? HostEndpointInfo.Type.FRAMEWORK
             : HostEndpointInfo.Type.APP;
        mContextHubProxy.onHostEndpointConnected(info);
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
        StringBuilder out = new StringBuilder("[ContextHubClient ");
        out.append("endpointID: ").append(getHostEndPointId()).append(", ");
        out.append("contextHub: ").append(getAttachedContextHubId()).append(", ");
        if (mAttributionTag != null) {
            out.append("attributionTag: ").append(getAttributionTag()).append(", ");
        }
        if (mPendingIntentRequest.isValid()) {
            out.append("intentCreatorPackage: ").append(mPackage).append(", ");
            out.append("nanoAppId: 0x")
                    .append(Long.toHexString(mPendingIntentRequest.getNanoAppId()));
        } else {
            out.append("package: ").append(mPackage);
        }
        if (mMessageChannelNanoappIdMap.size() > 0) {
            out.append(" messageChannelNanoappSet: (");
            Iterator<Map.Entry<Long, Integer>> it =
                    mMessageChannelNanoappIdMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Integer> entry = it.next();
                out.append("0x")
                        .append(Long.toHexString(entry.getKey()))
                        .append(" auth state: ")
                        .append(authStateToString(entry.getValue()));
                if (it.hasNext()) {
                    out.append(",");
                }
            }
            out.append(")");
        }
        synchronized (mWakeLock) {
            out.append("wakelock: ").append(mWakeLock);
        }
        out.append("]");
        return out.toString();
    }

    /** Callback that arrives when direct-call message callback delivery completed */
    @Override
    public void callbackFinished() {
        releaseWakeLock();
    }

    /**
     * Callback that arrives when direct-call message callback delivery completed.
     * Used for reliable messages.
     */
    @Override
    public void reliableMessageCallbackFinished(int messageSequenceNumber, byte errorCode) {
        sendMessageDeliveryStatusToContextHub(messageSequenceNumber, errorCode);
        callbackFinished();
    }

    @Override
    public void onSendFinished(
            PendingIntent pendingIntent,
            Intent intent,
            int resultCode,
            String resultData,
            Bundle resultExtras) {
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        Binder.withCleanCallingIdentity(
                () -> {
                    if (mIsWakelockUsable.get()) {
                        mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
                    }
                });
    }

    /**
     * Releases the wakelock.
     *
     * <p>The check-and-release operation should be atomic to avoid overly release.
     */
    private void releaseWakeLock() {
        Binder.withCleanCallingIdentity(
                () -> {
                    if (mWakeLock.isHeld()) {
                        try {
                            mWakeLock.release();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Releasing the wakelock fails - ", e);
                        }
                    }
                });
    }

    /**
     * Releases the wakelock for all the acquisitions during cleanup.
     *
     * <p>The check-and-release operation should be atomic to avoid overly release.
     */
    private void releaseWakeLockOnExit() {
        Binder.withCleanCallingIdentity(
                () -> {
                    mIsWakelockUsable.set(false);
                    while (mWakeLock.isHeld()) {
                        try {
                            mWakeLock.release();
                        } catch (RuntimeException e) {
                            Log.e(
                                    TAG,
                                    "Releasing the wakelock for all acquisitions fails - ",
                                    e);
                            break;
                        }
                    }
                });
    }

    private void sendMessageDeliveryStatusToContextHub(int messageSequenceNumber, byte errorCode) {
        if (!Flags.reliableMessageImplementation()) {
            return;
        }

        MessageDeliveryStatus status = new MessageDeliveryStatus();
        status.messageSequenceNumber = messageSequenceNumber;
        status.errorCode = errorCode;
        if (mContextHubProxy.sendMessageDeliveryStatusToContextHub(mAttachedContextHubInfo.getId(),
                status) != ContextHubTransaction.RESULT_SUCCESS) {
            Log.e(TAG, "Failed to send the reliable message status");
        }
    }
}
